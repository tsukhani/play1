from __future__ import print_function
import os
import re
import secrets
import subprocess

from play.utils import *

COMMANDS = ['enable-https', 'disable-https']

HELP = {
    'enable-https': 'Enable HTTPS on port 9443; generate a self-signed local keystore if needed (PF-67)',
    'disable-https': 'Disable HTTPS but keep the keystore for re-enabling later (PF-67)',
}

# Defaults match framework conventions (SslHttpServerContextFactory.java:69 etc.)
KEYSTORE_PATH = 'conf/certificate.jks'
HTTPS_PORT = '9443'
KEYSTORE_ALIAS = 'play'
KEYSTORE_DNAME = 'CN=localhost, OU=Development, O=Play, L=Local, ST=Local, C=US'
KEYSTORE_VALIDITY_DAYS = '3650'


def execute(**kargs):
    command = kargs.get("command")
    app = kargs.get("app")
    if command == 'enable-https':
        _enable(app)
    elif command == 'disable-https':
        _disable(app)


def _enable(app):
    app.check()
    config_path = os.path.join(app.path, 'conf', 'application.conf')
    keystore_path = os.path.join(app.path, KEYSTORE_PATH)

    config = _read(config_path)

    if _has_active_line(config, 'https.port'):
        print("~ HTTPS is already enabled in conf/application.conf.")
        if os.path.exists(keystore_path):
            print("~ Keystore present at %s." % KEYSTORE_PATH)
        print("~")
        return

    if os.path.exists(keystore_path):
        # Reuse existing keystore — pull the password out of application.conf so
        # the framework can actually open it. Without this, an off→on cycle would
        # generate a fresh password but leave the keystore on disk encrypted with
        # the old one, breaking startup.
        password = _existing_password(config)
        if password is None:
            print("~ ERROR: keystore exists at %s but keystore.password is not set in conf/application.conf." % KEYSTORE_PATH)
            print("~        Either delete the keystore (it will be regenerated) or restore the password manually.")
            print("~")
            return
        print("~ Reusing existing keystore at %s." % KEYSTORE_PATH)
    else:
        password = secrets.token_urlsafe(16)
        try:
            _generate_keystore(keystore_path, password)
        except FileNotFoundError:
            print("~ ERROR: keytool not found on PATH. Install a JDK 25+ and ensure java/keytool are on PATH.")
            print("~")
            return
        except RuntimeError as e:
            print("~ ERROR: keytool failed:")
            print("~ %s" % str(e))
            print("~")
            return
        print("~ Generated self-signed keystore at %s" % KEYSTORE_PATH)
        print("~ (RSA 2048, %s, valid %s days)" % (KEYSTORE_DNAME, KEYSTORE_VALIDITY_DAYS))

    config = _set_or_uncomment(config, 'keystore.algorithm', 'JKS')
    config = _set_or_uncomment(config, 'keystore.file', KEYSTORE_PATH)
    config = _set_or_uncomment(config, 'keystore.password', password)
    # Uncomment-or-append for https.port too: a previous disable-https leaves
    # a commented "# https.port=9443" behind, and toggling without this would
    # accumulate stale duplicate lines on every cycle.
    config = _set_or_uncomment(config, 'https.port', HTTPS_PORT)
    _write(config_path, config)

    print("~ HTTPS enabled on port %s." % HTTPS_PORT)
    print("~ Run play run or play start to apply.")
    print("~")


def _disable(app):
    app.check()
    config_path = os.path.join(app.path, 'conf', 'application.conf')
    keystore_path = os.path.join(app.path, KEYSTORE_PATH)

    config = _read(config_path)

    if not _has_active_line(config, 'https.port'):
        print("~ HTTPS is already disabled.")
        print("~")
        return

    # Comment out https.port; leave keystore.* lines intact so a future
    # enable-https can pick up the same password without prompting.
    config = re.sub(r'^(https\.port\s*=.*)$', r'# \1', config, flags=re.MULTILINE)
    _write(config_path, config)

    print("~ HTTPS disabled.")
    if os.path.exists(keystore_path):
        print("~ The keystore at %s is preserved — re-run play enable-https to reactivate." % KEYSTORE_PATH)
    print("~")


def _generate_keystore(path, password):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    cmd = [
        'keytool', '-genkeypair',
        '-alias', KEYSTORE_ALIAS,
        '-keyalg', 'RSA',
        '-keysize', '2048',
        '-keystore', path,
        '-storepass', password,
        '-keypass', password,
        '-storetype', 'JKS',
        '-dname', KEYSTORE_DNAME,
        '-validity', KEYSTORE_VALIDITY_DAYS,
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        # keytool's stderr is usually the only useful diagnostic here
        raise RuntimeError(result.stderr.strip() or result.stdout.strip())


def _has_active_line(config, key):
    return re.search(r'^' + re.escape(key) + r'\s*=', config, re.MULTILINE) is not None


def _existing_password(config):
    m = re.search(r'^keystore\.password\s*=\s*(.+)$', config, re.MULTILINE)
    return m.group(1).strip() if m else None


def _set_or_uncomment(config, key, value):
    """Ensure `key=value` exists as an active line. If a commented form
    `# key=...` exists in the skeleton, replace that line so the file's
    structure is preserved; if an active line already exists, replace
    its value; otherwise append at end."""
    active = re.compile(r'^' + re.escape(key) + r'\s*=.*$', re.MULTILINE)
    if active.search(config):
        return active.sub('%s=%s' % (key, value), config, count=1)
    commented = re.compile(r'^#\s*' + re.escape(key) + r'\s*=.*$', re.MULTILINE)
    if commented.search(config):
        return commented.sub('%s=%s' % (key, value), config, count=1)
    if not config.endswith('\n'):
        config += '\n'
    return config + '%s=%s\n' % (key, value)


def _read(path):
    with open(path, 'r') as f:
        return f.read()


def _write(path, contents):
    with open(path, 'w') as f:
        f.write(contents)
