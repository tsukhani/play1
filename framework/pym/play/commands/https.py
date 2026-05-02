from __future__ import print_function
import os
import re
import secrets
import subprocess

from play.utils import *

COMMANDS = ['enable-https', 'disable-https']

HELP = {
    'enable-https': 'Enable HTTPS on port 9443 (HTTP/2 via ALPN); generate a self-signed local keystore if needed (PF-67)',
    'disable-https': 'Disable HTTPS but keep the keystore for re-enabling later (PF-67)',
}

# Defaults match framework conventions (SslHttpServerContextFactory.java:69 etc.)
KEYSTORE_PATH = 'conf/certificate.jks'
HTTP_PORT = '9000'
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

    config = _read(config_path)

    # Resolve keystore path/algorithm from existing config so user customizations
    # (e.g. keystore.file=conf/my-cert.jks, keystore.algorithm=PKCS12) are honored.
    # Falls back to framework defaults when no active line exists. Same pattern is
    # later used to decide whether to write each keystore.* line — only set the
    # default when the user hasn't already declared a value.
    keystore_file_value = _active_value(config, 'keystore.file') or KEYSTORE_PATH
    keystore_algorithm_value = _active_value(config, 'keystore.algorithm') or 'JKS'
    keystore_path = os.path.join(app.path, keystore_file_value)

    # "Fully enabled" requires both https.port AND http.port active (any values —
    # preserves a user's custom http.port=8080). enable-https treats HTTP + HTTPS
    # as one feature toggle: a fresh app's commented-out `# http.port=9000` triggers
    # the framework's special-case default-to-9000 fallback ONLY when https.port is
    # also unset (Server.java:56); once we set https.port, that fallback no longer
    # fires, so we must explicitly bind http.port too if we want both listeners.
    # ALPN h2 negotiation is always on at the framework level when HTTPS is configured
    # (it gracefully falls back to http/1.1 for non-h2 clients), so there's no flag
    # to set here.
    https_active = _has_active_line(config, 'https.port')
    http_active = _has_active_line(config, 'http.port')
    if https_active and http_active:
        print("~ HTTPS is already enabled in conf/application.conf.")
        if os.path.exists(keystore_path):
            print("~ Keystore present at %s." % keystore_file_value)
        print("~")
        return

    if os.path.exists(keystore_path):
        # Reuse existing keystore — pull the password out of application.conf so
        # the framework can actually open it. Without this, an off→on cycle would
        # generate a fresh password but leave the keystore on disk encrypted with
        # the old one, breaking startup.
        password = _existing_password(config)
        if password is None:
            print("~ ERROR: keystore exists at %s but keystore.password is not set in conf/application.conf." % keystore_file_value)
            print("~        Either delete the keystore (it will be regenerated) or restore the password manually.")
            print("~")
            return
        print("~ Reusing existing keystore at %s." % keystore_file_value)
    else:
        # Only know how to generate JKS via keytool. If the user has explicitly
        # declared a non-JKS keystore.algorithm, refuse rather than silently
        # produce a JKS file at a path the user expected to hold (say) PKCS12.
        if keystore_algorithm_value.upper() != 'JKS':
            print("~ ERROR: keystore.algorithm=%s is configured but no keystore exists at %s." % (keystore_algorithm_value, keystore_file_value))
            print("~        play enable-https only generates JKS keystores. Generate a %s keystore" % keystore_algorithm_value)
            print("~        manually (e.g. via keytool -storetype %s) at %s, then re-run." % (keystore_algorithm_value, keystore_file_value))
            print("~")
            return
        # Honor an existing keystore.password if already set (e.g. user wrote it
        # out before generating the keystore); otherwise generate a random one.
        password = _existing_password(config) or secrets.token_urlsafe(16)
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
        print("~ Generated self-signed keystore at %s" % keystore_file_value)
        print("~ (RSA 2048, %s, valid %s days)" % (KEYSTORE_DNAME, KEYSTORE_VALIDITY_DAYS))

    # Set each line ONLY if it isn't already an active config entry. This is the
    # symmetric "comment/uncomment-only" contract the user expects: their custom
    # keystore.file=conf/my-cert.jks, keystore.algorithm=PKCS12, https.port=8443,
    # http.port=8080 (etc.) are preserved across enable-https runs. Lines that
    # are absent or only present commented get set to our defaults.
    if not _has_active_line(config, 'keystore.algorithm'):
        config = _set_or_uncomment(config, 'keystore.algorithm', 'JKS')
    if not _has_active_line(config, 'keystore.file'):
        config = _set_or_uncomment(config, 'keystore.file', KEYSTORE_PATH)
    if not _has_active_line(config, 'keystore.password'):
        config = _set_or_uncomment(config, 'keystore.password', password)
    if not https_active:
        config = _set_or_uncomment(config, 'https.port', HTTPS_PORT)
    if not http_active:
        config = _set_or_uncomment(config, 'http.port', HTTP_PORT)
    _write(config_path, config)

    http_value = _active_value(config, 'http.port')
    https_value = _active_value(config, 'https.port')
    if http_value == '-1':
        print("~ HTTP listener stays disabled per existing http.port=-1 setting.")
    else:
        print("~ HTTP enabled on port %s." % http_value)
    print("~ HTTPS enabled on port %s (HTTP/2 via ALPN)." % https_value)
    print("~ Run play run or play start to apply.")
    print("~")


def _disable(app):
    app.check()
    config_path = os.path.join(app.path, 'conf', 'application.conf')

    config = _read(config_path)

    # Resolve the keystore path the same way _enable does, so the "preserved"
    # message reflects the user's actual keystore.file rather than the default.
    keystore_file_value = _active_value(config, 'keystore.file') or KEYSTORE_PATH
    keystore_path = os.path.join(app.path, keystore_file_value)

    if not _has_active_line(config, 'https.port'):
        print("~ HTTPS is already disabled.")
        print("~")
        return

    # Comment out only https.port. Leave keystore.* lines and http.port
    # intact so a future enable-https can pick up the same password without
    # prompting and HTTP keeps working.
    config = re.sub(r'^(https\.port\s*=.*)$', r'# \1', config, flags=re.MULTILINE)
    _write(config_path, config)

    print("~ HTTPS disabled.")
    if os.path.exists(keystore_path):
        print("~ The keystore at %s is preserved — re-run play enable-https to reactivate." % keystore_file_value)
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


def _active_value(config, key):
    """Return the trimmed value of the active line `key=value`, or None if
    no active line exists. Lets the success message reflect a user's custom
    http.port=8080 etc. instead of always reporting our default."""
    m = re.search(r'^' + re.escape(key) + r'\s*=\s*(.+?)\s*$', config, re.MULTILINE)
    return m.group(1).strip() if m else None


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
