from __future__ import print_function
from builtins import str
from builtins import range
import sys
import os, os.path
import re
import random
import fileinput
import getopt
import shutil
import subprocess

def playVersion(play_env):
    play_version_file = os.path.join(play_env["basedir"], 'framework', 'src', 'play', 'version')
    return open(play_version_file).readline().strip()

def replaceAll(file, searchExp, replaceExp, regexp=False):
    if not regexp:
        replaceExp = replaceExp.replace('\\', r'\\')
        searchExp = searchExp.replace('$', r'\$')
        searchExp = searchExp.replace('{', r'\{')
        searchExp = searchExp.replace('}', r'\}')
        searchExp = searchExp.replace('.', r'\.')
    for line in fileinput.input(file, inplace=1):
        line = re.sub(searchExp, replaceExp, line)
        sys.stdout.write(line)

def fileHas(file, searchExp):
    # The file doesn't get closed if we don't iterate to the end, so
    # we must continue even after we found the match.
    found = False
    for line in fileinput.input(file):
        if line.find(searchExp) > -1:
            found = True
    return found

def secretKey():
    return ''.join([random.choice('ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789') for i in range(64)])

DEFAULT_SECRET_VAR = 'PLAY_SECRET'

def secretVarName(app_path):
    """Read application.conf and find the variable name in
    `application.secret=${VARNAME}`. Returns DEFAULT_SECRET_VAR if the file is
    missing or the line uses an unparseable form (the validator on the Java side
    will reject anything other than a single `${VARNAME}` placeholder, so this
    only needs the happy-path regex)."""
    conf_path = os.path.join(app_path, 'conf', 'application.conf')
    if not os.path.exists(conf_path):
        return DEFAULT_SECRET_VAR
    pattern = re.compile(r'^\s*application\.secret\s*=\s*\$\{([^}:]+)\}\s*$')
    try:
        with open(conf_path, 'r') as f:
            for line in f:
                stripped = line.lstrip()
                if stripped.startswith('#') or stripped.startswith('!'):
                    continue
                m = pattern.match(line)
                if m:
                    return m.group(1)
    except Exception:
        pass
    return DEFAULT_SECRET_VAR

SECRET_ENV_RELPATH = os.path.join('certs', '.env')

def writeAppSecret(app_path, key):
    """Write <VARNAME>=<key> into <app_path>/certs/.env, preserving any other
    lines. The variable name is read from `application.conf` (so a user who
    renamed the conf line to ${MY_SECRET} gets MY_SECRET=... automatically).
    Creates the file if missing; replaces any existing line for that variable
    in place. Returns the .env path."""
    var_name = secretVarName(app_path)
    return writeEnvVar(app_path, var_name, key)

def writeEnvVar(app_path, var_name, value, env_filename=SECRET_ENV_RELPATH):
    """Write <var_name>=<value> into <app_path>/<env_filename>, preserving any
    other lines. Replaces any existing line for that variable in place. Creates
    the file (and any missing parent dirs) if needed. Returns the file path.
    The file is chmod'd to 0600 — it carries production secrets, so umask-default
    644 would leak it to anything running under the same UID."""
    env_path = os.path.join(app_path, env_filename)
    parent = os.path.dirname(env_path)
    if parent and not os.path.isdir(parent):
        os.makedirs(parent, exist_ok=True)
    new_line = '%s=%s\n' % (var_name, value)
    prefix_eq = var_name + '='
    prefix_sp = var_name + ' '
    if os.path.exists(env_path):
        with open(env_path, 'r') as f:
            lines = f.readlines()
        replaced = False
        for i, line in enumerate(lines):
            stripped = line.lstrip()
            if stripped.startswith(prefix_eq) or stripped.startswith(prefix_sp):
                lines[i] = new_line
                replaced = True
                break
        if not replaced:
            if lines and not lines[-1].endswith('\n'):
                lines[-1] = lines[-1] + '\n'
            lines.append(new_line)
        with open(env_path, 'w') as f:
            f.writelines(lines)
    else:
        with open(env_path, 'w') as f:
            f.write(new_line)
    try:
        os.chmod(env_path, 0o600)
    except OSError:
        # Filesystems without POSIX modes (e.g. SMB shares on Windows hosts)
        # silently no-op chmod — not worth blocking on.
        pass
    return env_path

def ensureTestSecret(app_path):
    """Test commands (`play test`, `play auto-test`) run hermetically and don't
    care about a stable secret value across runs. If the secret variable named
    by application.conf isn't already set in the environment (via .env, host
    env, or a -D flag), generate a fresh one for this process so the launcher
    isn't blocked on the user running `play secret` first. Returns the var name
    when generation occurred, None otherwise."""
    var_name = secretVarName(app_path)
    if var_name in os.environ:
        return None
    os.environ[var_name] = secretKey()
    return var_name

EXAMPLE_ENV_RELPATH = os.path.join('certs', '.env.example')

def writeEnvExample(app_path, var_name=None):
    """Generate `certs/.env.example` — the committed schema/template that
    documents which environment variables the app needs. Devs onboarding the
    project run `cp certs/.env.example certs/.env` and fill in real values.
    Idempotent: if the file already exists, leaves it alone (so manual
    additions aren't clobbered). Lives next to the (gitignored) certs/.env so
    the template + the populated file are in the same directory."""
    if var_name is None:
        var_name = secretVarName(app_path) or DEFAULT_SECRET_VAR
    example_path = os.path.join(app_path, EXAMPLE_ENV_RELPATH)
    if os.path.exists(example_path):
        return example_path
    parent = os.path.dirname(example_path)
    if parent and not os.path.isdir(parent):
        os.makedirs(parent, exist_ok=True)
    content = (
        "# Environment variables for this Play application.\n"
        "#\n"
        "# Copy this file to `certs/.env` (which is gitignored) and fill in real values:\n"
        "#     cp certs/.env.example certs/.env\n"
        "#\n"
        "# This template lists every variable the app needs at startup. Keep it in\n"
        "# version control so onboarding teammates know what to set. Do NOT put real\n"
        "# secrets here — only placeholders or empty values.\n"
        "#\n"
        "# At runtime, the Play CLI loads `certs/.env` into the process environment\n"
        "# before starting the JVM. Values already set in the host environment (or\n"
        "# via `-D<NAME>=...` JVM flags) take precedence over `certs/.env`.\n"
        "\n"
        "# The application secret used for HMAC signing (sessions, CSRF) and\n"
        "# AES encryption. Generate with `play secret`.\n"
        + var_name + "=\n"
    )
    with open(example_path, 'w') as f:
        f.write(content)
    return example_path

def loadDotEnv(app_path):
    """Load <app_path>/certs/.env into os.environ. Existing environment values
    win, so a host env var or `-D` flag can still override the .env entry."""
    env_path = os.path.join(app_path, SECRET_ENV_RELPATH)
    if not os.path.exists(env_path):
        return
    with open(env_path, 'r') as f:
        for raw in f:
            line = raw.strip()
            if not line or line.startswith('#'):
                continue
            if '=' not in line:
                continue
            k, _, v = line.partition('=')
            k = k.strip()
            v = v.strip()
            if not k:
                continue
            # Strip optional surrounding quotes around the value.
            if len(v) >= 2 and ((v[0] == '"' and v[-1] == '"') or (v[0] == "'" and v[-1] == "'")):
                v = v[1:-1]
            if k not in os.environ:
                os.environ[k] = v

def isParentOf(path1, path2):
    try:
        relpath = os.path.relpath(path1, path2)
        sep = os.sep
        if sep == '\\':
            sep = '\\\\'
        ptn = r'^\.\.(' + sep + r'\.\.)*$'
        return re.match(ptn, relpath) != None
    except:
        return False

def isExcluded(path, exclusion_list = None):
    if exclusion_list is None:
        return False
    for exclusion in exclusion_list:
        if isParentOf(exclusion, path) or exclusion == path:
            return True
    return False

def getWithModules(args, env):
    withModules = []
    try:
        optlist, newargs = getopt.getopt(args, '', ['with=', 'name='])
        for o, a in optlist:
            if o in ('--with='):
                withModules = a.split(',')
    except getopt.GetoptError:
        pass # Other argument that --with= has been passed (which is OK)
    md = []
    for m in withModules:
        dirname = None
        candidate = os.path.join(env["basedir"], 'modules/%s' % m)
        if os.path.exists(candidate) and os.path.isdir(candidate):
            dirname = candidate
        else:
            for f in os.listdir(os.path.join(env["basedir"], 'modules')):
                if os.path.isdir(os.path.join(env["basedir"], 'modules/%s' % f)) and f.find('%s-' % m) == 0:
                    dirname = os.path.join(env["basedir"], 'modules/%s' % f)
                    break
        if not dirname:
            print("~ Oops. Module " + m + " not found (try running `play install " + m + "`)")
            print("~")
            sys.exit(-1)
        
        md.append(dirname)
    
    return md

# Recursively delete all files/folders in root whose name equals to filename
# We could pass an "ignore" parameter to copytree, but that's not supported in Python 2.5

def deleteFrom(root, filenames):
    for f in os.listdir(root):
        fullpath = os.path.join(root, f)
        if f in filenames:
            delete(fullpath)
        elif os.path.isdir(fullpath):
            deleteFrom(fullpath, filenames)

def delete(filename):
    if os.path.isdir(filename):
        shutil.rmtree(filename)
    else:
        os.remove(filename)

# Copy a directory, skipping dot-files
def copy_directory(source, target, exclude = None):
    if exclude is None:
        exclude = []
    skip = None

    if not os.path.exists(target):
        os.makedirs(target)
    for root, dirs, files in os.walk(source):
        path_from_source = root[len(source):]
        # Ignore path containing '.' in path
        # But keep those with relative path '..'
        if re.search(r'/\.[^\.]|\\\.[^\.]', path_from_source):
            continue
        for file in files:
            if root.find('/.') > -1 or root.find('\\.') > -1:
                continue
            if file.find('~') == 0 or file.startswith('.'):
                continue

            # Loop to detect files to exclude (coming from exclude list)
            # Search is done only on path for the moment
            skip = 0
            for exclusion in exclude:
                if root.find(exclusion) > -1:
                    skip = 1
            # Skipping the file if exclusion has been found
            if skip == 1:
                continue

            from_ = os.path.join(root, file)
            to_ = from_.replace(source, target, 1)
            to_directory = os.path.split(to_)[0]
            if not os.path.exists(to_directory):
                os.makedirs(to_directory)
            shutil.copyfile(from_, to_)

def isTestFrameworkId( framework_id ):
    return (framework_id == 'test' or (framework_id.startswith('test-') and framework_id.__len__() >= 6 ))

def java_path():
    if 'JAVA_HOME' not in os.environ:
        return "java"
    else:
        return os.path.normpath("%s/bin/java" % os.environ['JAVA_HOME'])

def get_java_version():
    try:
        sp = subprocess.Popen([java_path(), "-version"], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        java_version = sp.communicate()
    except (OSError, FileNotFoundError):
        # java executable not found at all — the enforce helper turns this
        # into a friendly "install JDK N+" message via the empty-string path.
        return ""
    java_version = str(java_version)

    result = re.search(r'version "([a-zA-Z0-9\.\-_]{1,})"', java_version)

    if result:
        return result.group(1)
    else:
        return ""

def get_minimal_supported_java_version():
    # Must match framework/build.xml's build-release property. Bumping this
    # without recompiling, or vice versa, will surface as cryptic
    # UnsupportedClassVersionError at runtime — see PF-64.
    return 25

def is_java_version_supported(java_version):
    try:
        if java_version.startswith("1."):
            num = int(java_version.split('.')[-1])
        elif java_version.count('.') >= 1:
            num = int(java_version.split('.')[0])
        else:
            num = int(java_version)
        return num >= get_minimal_supported_java_version()
    except (ValueError, AttributeError):
        # Fail-closed on unparseable / missing version strings: a sub-25 JVM
        # whose `java -version` output we couldn't parse should not silently
        # be allowed through.
        return False

def enforce_supported_java_version():
    """PF-64: pre-flight Java version check. Called from the top-level `play`
    entry script before any command dispatch (covers `play new`, `play deps`,
    etc.) and from application.py for app-level commands. On unsupported
    runtime, prints a multi-line friendly error and exits non-zero before any
    Java fork or filesystem mutation."""
    java_version = get_java_version()
    if is_java_version_supported(java_version):
        return
    minimum = get_minimal_supported_java_version()
    print("~ ERROR: Play 1.12.x requires Java %d or later." % minimum)
    if java_version:
        print("~        Detected: Java %s at %s" % (java_version, java_path()))
    else:
        print("~        Could not detect Java at %s — is it installed and on PATH?" % java_path())
    print("~        Install JDK %d+ and set JAVA_HOME accordingly, or update your PATH." % minimum)
    sys.exit(1)
