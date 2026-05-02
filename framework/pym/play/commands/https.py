from __future__ import print_function
import os
import re
import secrets
import subprocess

from play.utils import *

COMMANDS = ['enable-https', 'disable-https']

HELP = {
    'enable-https': 'Enable HTTPS on port 9443 with HTTP/2 (h2) negotiation; generate a self-signed local keystore if needed (PF-67)',
    'disable-https': 'Disable HTTPS and HTTP/2 but keep the keystore for re-enabling later (PF-67)',
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
    keystore_path = os.path.join(app.path, KEYSTORE_PATH)

    config = _read(config_path)

    # "Fully enabled" requires https.port active, play.http2.enabled=true, AND
    # http.port active (any value — preserves a user's custom http.port=8080 etc).
    # enable-https treats HTTP + HTTPS + HTTP/2 as one feature toggle: a fresh
    # app's commented-out `# http.port=9000` triggers the framework's special-case
    # default-to-9000 fallback ONLY when https.port is also unset (Server.java:56);
    # once we set https.port, that fallback no longer fires, so we must explicitly
    # bind http.port too if we want both listeners.
    https_active = _has_active_line(config, 'https.port')
    http2_true = _is_active_value(config, 'play.http2.enabled', 'true')
    http_active = _has_active_line(config, 'http.port')
    if https_active and http2_true and http_active:
        print("~ HTTPS (with HTTP/2) is already enabled in conf/application.conf.")
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
    # Uncomment-or-append for https.port and play.http2.enabled: a previous
    # disable-https leaves both lines commented, and toggling without using
    # _set_or_uncomment would accumulate stale duplicate lines on every cycle.
    config = _set_or_uncomment(config, 'https.port', HTTPS_PORT)
    config = _set_or_uncomment(config, 'play.http2.enabled', 'true')
    # Bind plain HTTP too — only set http.port=9000 if it isn't already active,
    # so a user's custom http.port=8080 (or http.port=-1 for HTTPS-only) is
    # preserved across enable-https runs.
    if not http_active:
        config = _set_or_uncomment(config, 'http.port', HTTP_PORT)
    _write(config_path, config)

    http_value = _active_value(config, 'http.port')
    if http_value == '-1':
        print("~ HTTP listener stays disabled per existing http.port=-1 setting.")
    else:
        print("~ HTTP enabled on port %s." % http_value)
    print("~ HTTPS enabled on port %s with HTTP/2 (ALPN h2) negotiation." % HTTPS_PORT)
    print("~ Run play run or play start to apply.")
    print("~")


def _disable(app):
    app.check()
    config_path = os.path.join(app.path, 'conf', 'application.conf')
    keystore_path = os.path.join(app.path, KEYSTORE_PATH)

    config = _read(config_path)

    https_active = _has_active_line(config, 'https.port')
    http2_active = _has_active_line(config, 'play.http2.enabled')
    if not https_active and not http2_active:
        print("~ HTTPS is already disabled.")
        print("~")
        return

    # Comment out https.port and play.http2.enabled. Leave keystore.* lines
    # intact so a future enable-https can pick up the same password without
    # prompting.
    if https_active:
        config = re.sub(r'^(https\.port\s*=.*)$', r'# \1', config, flags=re.MULTILINE)
    if http2_active:
        config = re.sub(r'^(play\.http2\.enabled\s*=.*)$', r'# \1', config, flags=re.MULTILINE)
    _write(config_path, config)

    print("~ HTTPS disabled (HTTP/2 negotiation also disabled).")
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


def _is_active_value(config, key, value):
    """Check whether `key` has an active line whose value (case-insensitive,
    stripped) equals `value`. Used by the "already enabled" check to require
    play.http2.enabled to literally be `true`, not just any value."""
    m = re.search(r'^' + re.escape(key) + r'\s*=\s*(.+?)\s*$', config, re.MULTILINE)
    return m is not None and m.group(1).strip().lower() == value.lower()


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
