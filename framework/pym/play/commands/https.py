from __future__ import print_function
import os
import re
import shutil
import subprocess

from play.utils import *

COMMANDS = ['enable-https', 'disable-https']

HELP = {
    'enable-https': 'Enable HTTPS on port 9443 (HTTP/2 + HTTP/3 via ALPN); generate a local PEM cert+key if needed (PF-67/PF-68)',
    'disable-https': 'Disable HTTPS but keep the cert files for re-enabling later (PF-67/PF-68)',
}

# PF-68: PEM-only. mkcert produces certs trusted by the system store after
# `mkcert -install`, which is what Chrome's QUIC stack needs to actually negotiate
# HTTP/3 in a real browser. openssl is the fallback when mkcert isn't available.
CERT_PATH = 'certs/host.cert'
KEY_PATH = 'certs/host.key'
HTTP_PORT = '9000'
HTTPS_PORT = '9443'


def execute(**kargs):
    command = kargs.get("command")
    app = kargs.get("app")
    args = kargs.get("args") or []
    if command == 'enable-https':
        # NOTE: can't use --force here. The top-level play CLI intercepts that flag
        # for its own ignoreMissing path (see /opt/play1/play around line 102),
        # consuming it before commands see args. --regenerate is clearer anyway.
        force = '--regenerate' in args
        _enable(app, force=force)
    elif command == 'disable-https':
        _disable(app)


def _enable(app, force=False):
    app.check()
    config_path = os.path.join(app.path, 'conf', 'application.conf')

    config = _read(config_path)

    # Resolve cert/key paths from existing config (or framework defaults), so a user
    # who pointed certificate.file at conf/my-cert.pem before running enable-https
    # has that path honored.
    cert_file_value = _active_value(config, 'certificate.file') or CERT_PATH
    key_file_value = _active_value(config, 'certificate.key.file') or KEY_PATH
    cert_path = os.path.join(app.path, cert_file_value)
    key_path = os.path.join(app.path, key_file_value)

    # "Fully enabled" requires https.port active AND http.port active. Both must be
    # explicit because once https.port is set the framework's "default to 9000 when
    # neither port is set" fallback (Server.java) no longer fires, so http.port must
    # also be explicit to keep the plain HTTP listener bound.
    https_active = _has_active_line(config, 'https.port')
    http_active = _has_active_line(config, 'http.port')
    files_present = os.path.exists(cert_path) and os.path.exists(key_path)

    # Decide whether to (re)generate the cert+key. Three drivers:
    #   - --force: always regenerate, even if files are valid
    #   - Files missing: generate fresh
    #   - Files present but expired/corrupted: regenerate transparently. The cert is
    #     a localhost dev cert; users don't want to read a confusing TLS handshake
    #     error and then manually delete files just to recover.
    regenerate_reason = None
    if force and files_present:
        regenerate_reason = 'force'
    elif not files_present:
        regenerate_reason = 'missing'
    else:
        validity = _check_cert_validity(cert_path)
        if validity == 'expired':
            regenerate_reason = 'expired'
        elif validity == 'corrupted':
            regenerate_reason = 'corrupted'
        # 'valid' or 'unknown' (openssl unavailable) → reuse

    if regenerate_reason is None and https_active and http_active:
        print("~ HTTPS is already enabled in conf/application.conf.")
        print("~ Cert+key present at %s and %s." % (cert_file_value, key_file_value))
        print("~")
        return

    if regenerate_reason is None:
        print("~ Reusing existing PEM cert+key at %s and %s." % (cert_file_value, key_file_value))
    else:
        if regenerate_reason == 'expired':
            print("~ Existing cert at %s has expired — regenerating." % cert_file_value)
        elif regenerate_reason == 'corrupted':
            print("~ Existing cert at %s is unreadable — regenerating." % cert_file_value)
        elif regenerate_reason == 'force':
            print("~ --regenerate: regenerating PEM cert+key (existing files will be replaced).")
        # 'missing' falls through silently — first-time generation needs no announcement
        try:
            if shutil.which('mkcert') is not None:
                _generate_mkcert(cert_path, key_path)
                print("~ Generated mkcert-signed PEM cert+key at %s and %s." % (cert_file_value, key_file_value))
                print("~ (Trusted by the system store after `mkcert -install` — Chrome will accept HTTP/3.)")
            else:
                _generate_openssl(cert_path, key_path)
                print("~ Generated self-signed PEM cert+key at %s and %s (openssl fallback)." % (cert_file_value, key_file_value))
                print("~ Hint: install mkcert (https://github.com/FiloSottile/mkcert) for browser-trusted local-dev TLS.")
        except FileNotFoundError as e:
            print("~ ERROR: required tool not found on PATH: %s" % e)
            print("~        Install either mkcert (preferred) or openssl, then re-run.")
            print("~")
            return
        except RuntimeError as e:
            print("~ ERROR: cert generation failed:")
            print("~ %s" % str(e))
            print("~")
            return

    # Set each line ONLY if it isn't already an active config entry. This is the
    # "comment/uncomment-only" contract: a user's custom certificate.file=conf/my.pem,
    # https.port=8443, or http.port=8080 (etc.) is preserved across enable-https runs.
    if not _has_active_line(config, 'certificate.file'):
        config = _set_or_uncomment(config, 'certificate.file', CERT_PATH)
    if not _has_active_line(config, 'certificate.key.file'):
        config = _set_or_uncomment(config, 'certificate.key.file', KEY_PATH)
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
    print("~ HTTPS enabled on port %s (HTTP/2 + HTTP/3 via ALPN)." % https_value)
    print("~ Run play run or play start to apply.")
    print("~")


def _disable(app):
    app.check()
    config_path = os.path.join(app.path, 'conf', 'application.conf')

    config = _read(config_path)

    cert_file_value = _active_value(config, 'certificate.file') or CERT_PATH
    key_file_value = _active_value(config, 'certificate.key.file') or KEY_PATH
    cert_path = os.path.join(app.path, cert_file_value)

    if not _has_active_line(config, 'https.port'):
        print("~ HTTPS is already disabled.")
        print("~")
        return

    # Comment out only https.port. Leave certificate.* lines and http.port intact so
    # a future enable-https can pick up the same cert files without prompting and
    # HTTP keeps working.
    config = re.sub(r'^(https\.port\s*=.*)$', r'# \1', config, flags=re.MULTILINE)
    _write(config_path, config)

    print("~ HTTPS disabled.")
    if os.path.exists(cert_path):
        print("~ The cert+key at %s and %s are preserved — re-run play enable-https to reactivate." % (cert_file_value, key_file_value))
    print("~")


def _generate_mkcert(cert_path, key_path):
    os.makedirs(os.path.dirname(cert_path), exist_ok=True)
    cmd = [
        'mkcert',
        '-cert-file', cert_path,
        '-key-file', key_path,
        'localhost', '127.0.0.1', '::1',
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip())


def _generate_openssl(cert_path, key_path):
    os.makedirs(os.path.dirname(cert_path), exist_ok=True)
    # PEM key is unencrypted (-nodes) — Netty's SslContextBuilder.forServer(certFile, keyFile)
    # supports only unencrypted keys, and mkcert produces unencrypted PEM too, so the two
    # paths are interchangeable from the framework's perspective.
    cmd = [
        'openssl', 'req', '-x509',
        '-newkey', 'rsa:2048',
        '-nodes',
        '-keyout', key_path,
        '-out', cert_path,
        '-days', '3650',
        '-subj', '/CN=localhost',
        '-addext', 'subjectAltName=DNS:localhost,IP:127.0.0.1,IP:::1',
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip())


def _check_cert_validity(cert_path):
    """Returns one of:
      - 'valid': cert parses and notAfter is in the future
      - 'expired': cert parses but notAfter has passed (or is today)
      - 'corrupted': cert file does not parse as a valid X.509 PEM
      - 'unknown': openssl isn't on PATH so we can't check (caller treats as 'valid'
        and falls back to blind reuse — same as pre-PF-68 behavior)
    Used by enable-https to transparently regenerate dev certs that have expired or
    been corrupted, since manual recovery is busywork for a localhost cert.
    """
    if shutil.which('openssl') is None:
        return 'unknown'
    # First parse: distinguishes corrupted from anything-cert-like.
    parse = subprocess.run(
        ['openssl', 'x509', '-noout', '-in', cert_path],
        capture_output=True, text=True
    )
    if parse.returncode != 0:
        return 'corrupted'
    # Second check: -checkend 0 returns 0 if cert is still valid, 1 if expired.
    expiry = subprocess.run(
        ['openssl', 'x509', '-checkend', '0', '-noout', '-in', cert_path],
        capture_output=True, text=True
    )
    return 'valid' if expiry.returncode == 0 else 'expired'


def _has_active_line(config, key):
    return re.search(r'^' + re.escape(key) + r'\s*=', config, re.MULTILINE) is not None


def _active_value(config, key):
    """Return the trimmed value of the active line `key=value`, or None if no active
    line exists. Lets a user's custom certificate.file=conf/my-cert.pem be honored
    instead of forcing the default."""
    m = re.search(r'^' + re.escape(key) + r'\s*=\s*(.+?)\s*$', config, re.MULTILINE)
    return m.group(1).strip() if m else None


def _set_or_uncomment(config, key, value):
    """Ensure `key=value` exists as an active line. If a commented form `# key=...`
    exists in the skeleton, replace that line so the file's structure is preserved;
    if an active line already exists, replace its value; otherwise append at end."""
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
