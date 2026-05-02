from __future__ import print_function
# Command related to execution: auto-test

import sys
import os, os.path
import shutil
import urllib.request, urllib.parse, urllib.error, urllib.request, urllib.error, urllib.parse
import subprocess
import webbrowser
import time
import signal

from play.utils import *

COMMANDS = ['autotest','auto-test']

HELP = {
    'autotest': "Automatically run all application tests"
}

def _firephoqueHttpPort(app):
    """Resolve the port FirePhoque should connect to. PF-73: always plain HTTP,
    so we only consult http.port (resolved through the %test prefix as usual).
    Falls back to the framework's default 9000 if neither %test.http.port nor
    http.port is set. Returns a string because http_port flows into URL string
    formatting downstream."""
    return app.readConf('http.port') or '9000'

def execute(**kargs):
    command = kargs.get("command")
    app = kargs.get("app")
    args = kargs.get("args")
    env = kargs.get("env")
    cmdloader = kargs.get("cmdloader")

    autotest(app, args)
        
def autotest(app, args):
    app.check()
    print("~ Running in test mode")
    print("~ Ctrl+C to stop")
    print("~ ")

    # Tests are hermetic — generate an ephemeral secret if none was supplied via
    # .env / host env / -D flag, so a fresh checkout can run `play auto-test`
    # without first running `play secret`.
    generated = ensureTestSecret(app.path)
    if generated is not None:
        print("~ Generated ephemeral %s for this test run" % generated)
        print("~ ")

    print("~ Deleting %s" % os.path.normpath(os.path.join(app.path, 'tmp')))
    if os.path.exists(os.path.join(app.path, 'tmp')):
        shutil.rmtree(os.path.join(app.path, 'tmp'))
    print("~")

    # PF-73: FirePhoque always connects over plain HTTP. Tests don't need TLS —
    # the /@tests endpoint serves identically over either, and forcing HTTPS just
    # surfaces JDK-truststore complexity (mkcert-signed certs aren't trusted by
    # the JDK by default) and triggers port-collision risk. The pre-PF-73 code
    # used `if app.readConf('https.port'):` — but that's truthy for the literal
    # "-1" sentinel that PF-72 writes to %test.https.port, so autotest started
    # building https://localhost:-1/@tests, which is unreachable. Hardcoding
    # http here makes the code immune to that sentinel and to any future
    # https.port shenanigans.
    http_port = _firephoqueHttpPort(app)
    try:
        proxy_handler = urllib.request.ProxyHandler({})
        opener = urllib.request.build_opener(proxy_handler)
        opener.open('http://localhost:%s/@kill' % http_port)
    except Exception as e:
        pass


    # read parameters
    add_options = []        
    if args.count('--unit'):
        args.remove('--unit')
        add_options.append('-DrunUnitTests')
            
    if args.count('--functional'):
        args.remove('--functional')
        add_options.append('-DrunFunctionalTests')
            
    if args.count('--selenium'):
        args.remove('--selenium')
        add_options.append('-DrunSeleniumTests')
      
    # Handle timeout parameter
    webclient_timeout = None
    if app.readConf('webclient.timeout'):
        webclient_timeout = app.readConf('webclient.timeout')
    
    for arg in args:
        if arg.startswith('--timeout='):
            args.remove(arg)
            webclient_timeout = arg[10:]
          
    if webclient_timeout is not None:
        add_options.append('-DwebclientTimeout=' + webclient_timeout)
            
    # Run app
    test_result = os.path.join(app.path, 'test-result')
    if os.path.exists(test_result):
        shutil.rmtree(test_result)
    sout = open(os.path.join(app.log_path(), 'system.out'), 'w')
    app.play_env['disable_jpda'] = True
    java_cmd = app.java_cmd(args)
    try:
        # Redirect both stdout and stderr to system.out so Play's plugin-shutdown
        # log lines (which can stream out for 30-90s after autotest.py exits via
        # /@kill) don't appear in the user's terminal after the shell prompt
        # returns. Without stderr capture, Play writes shutdown traces to the
        # inherited stderr long after the user sees "All tests passed".
        play_process = subprocess.Popen(java_cmd, env=os.environ, stdout=sout, stderr=subprocess.STDOUT)
    except OSError:
        print("Could not execute the java executable, please make sure the JAVA_HOME environment variable is set properly (the java executable should reside at JAVA_HOME/bin/java). ")
        sys.exit(-1)
    soutint = open(os.path.join(app.log_path(), 'system.out'), 'r')
    while True:
        if play_process.poll():
            print("~")
            print("~ Oops, application has not started?")
            print("~")
            sys.exit(-1)
        line = soutint.readline().strip()
        if line:
            print(line)
            if line.find('Server is up and running') > -1: # This line is written out by Server.java to system.out and is not log file dependent
                soutint.close()
                break

    # Run FirePhoque
    print("~")
    print("~ Starting FirePhoque...")

    headless_browser = ''
    if app.readConf('headlessBrowser'):
        headless_browser = app.readConf('headlessBrowser')

    fpcp = []
    fpcp.append(os.path.normpath(os.path.join(app.play_env["basedir"], 'modules/testrunner/conf')))
    fpcp.append(os.path.join(app.play_env["basedir"], 'modules/testrunner/lib/play-testrunner.jar'))
    fpcp_libs = os.path.join(app.play_env["basedir"], 'modules/testrunner/firephoque')
    for jar in os.listdir(fpcp_libs):
        if jar.endswith('.jar'):
           fpcp.append(os.path.normpath(os.path.join(fpcp_libs, jar)))
    cp_args = ':'.join(fpcp)
    if os.name == 'nt':
        cp_args = ';'.join(fpcp)
    java_cmd = [java_path(), '--enable-native-access=ALL-UNNAMED'] + add_options + ['-Djava.util.logging.config.file=logging.properties', '-classpath', cp_args, '-Dapplication.url=http://localhost:%s' % http_port, '-DheadlessBrowser=%s' % (headless_browser), 'play.modules.testrunner.FirePhoque']
    # PF-73: no -Djavax.net.ssl.trustStore flag because FirePhoque is hardcoded to
    # plain HTTP. Tests that legitimately need TLS should run through a different
    # path (FunctionalTest fork, dedicated integration testapp, etc.), not through
    # play autotest.
    try:
        subprocess.call(java_cmd, env=os.environ)
    except OSError:
        print("Could not execute the headless browser. ")
        sys.exit(-1)

    print("~")

    # Read result files first — they were written by TestRunner.run before each
    # /@tests/<class> HTTP response, so they're already on disk now that
    # FirePhoque has exited. Printing the verdict before /@kill means the user
    # sees pass/fail immediately rather than waiting for Play's plugin shutdown
    # (HikariCP/c3p0/JPA close + Netty event-loop drain can take 30-90s on apps
    # with heavy plugin chains).
    testCompleted = False
    testFailed = False
    if os.path.exists(os.path.join(app.path, 'test-result/result.passed')):
        testCompleted = True
        print("~ All tests passed")
        print("~")
        testspassed = True
    if os.path.exists(os.path.join(app.path, 'test-result/result.failed')):
        testCompleted = True
        testFailed = True
        print("~ Some tests have failed. See file://%s for results" % test_result)
        print("~")

    # Send /@kill with a short timeout. The server-side handler in
    # PlayStatusPlugin.rawInvocation calls System.exit(0) within milliseconds,
    # but never writes an HTTP response because System.exit aborts the request
    # handler. urlopen would otherwise block on the open socket until JVM
    # shutdown hooks finish, which is what causes the long post-test wait.
    # The timeout only bounds the read wait — connect+send is synchronous and
    # well under 500ms on localhost, so the request is guaranteed to reach
    # the server. Server dies on its own regardless via System.exit; we just
    # don't wait around for plugin-shutdown to finish before exiting.
    try:
        proxy_handler = urllib.request.ProxyHandler({})
        opener = urllib.request.build_opener(proxy_handler)
        opener.open('http://localhost:%s/@kill' % http_port, timeout=0.5)
    except Exception as e:
        pass

    if testFailed:
        sys.exit(1)
    if not testCompleted:
        print("~ Tests did not successfully complete.")
        print("~")
        sys.exit(-1)
