from __future__ import print_function
# Command related to creation and execution: run, new, clean

from builtins import input
from builtins import str
import sys
import os
import subprocess
import shutil
import getopt
import urllib.request, urllib.error, urllib.parse
import webbrowser
import time
import signal

from play.utils import *

COMMANDS = ['run', 'new', 'clean', '', 'id', 'new,run', 'clean,run', 'modules']

HELP = {
    'id': "Define the framework ID",
    'new': "Create a new application",
    'clean': "Delete temporary files (including the bytecode cache)",
    'run': "Run the application in the current shell",
    'modules': "Display the computed modules list"
}

def execute(**kargs):
    command = kargs.get("command")
    app = kargs.get("app")
    args = kargs.get("args")
    env = kargs.get("env")
    cmdloader = kargs.get("cmdloader")

    if command == 'id':
        id(env)
    if command == 'new' or command == 'new,run':
        new(app, args, env, cmdloader)
    if command == 'clean' or command == 'clean,run':
        clean(app)
    if command == 'new,run' or command == 'clean,run' or command == 'run':
        run(app, args)
    if command == 'test':
        test(app, args)
    if command == 'auto-test' or command == 'autotest':
        autotest(app, args)
    if command == 'modules':
        show_modules(app, args)

def new(app, args, env, cmdloader=None):
    withModules = []
    application_name = None
    frontend = False
    try:
        optlist, args = getopt.getopt(args, '', ['with=', 'name=', 'frontend'])
        for o, a in optlist:
            if o in ('--with'):
                withModules = a.split(',')
            if o in ('--name'):
                application_name = a
            if o in ('--frontend'):
                frontend = True
    except getopt.GetoptError as err:
        print("~ %s" % str(err))
        print("~ Sorry, unrecognized option")
        print("~ ")
        sys.exit(-1)
    if os.path.exists(app.path):
        print("~ Oops. %s already exists" % app.path)
        print("~")
        sys.exit(-1)

    md = []
    for m in withModules:
        dirname = None
        if os.path.exists(os.path.join(env["basedir"], 'modules/%s' % m)) and os.path.isdir(os.path.join(env["basedir"], 'modules/%s' % m)):
            dirname = m
        else:
            for f in os.listdir(os.path.join(env["basedir"], 'modules')):
                if os.path.isdir(os.path.join(env["basedir"], 'modules/%s' % f)) and f.find('%s-' % m) == 0:
                    dirname = f
                    break

        if not dirname:
            print("~ Oops. No module %s found" % m)
            print("~ Try to install it using 'play install %s'" % m)
            print("~")
            sys.exit(-1)

        md.append(dirname)

    print("~ The new application will be created in %s" % os.path.normpath(app.path))
    if application_name is None:
        application_name = input("~ What is the application name? [%s] " % os.path.basename(app.path))
    if application_name == "":
        application_name = os.path.basename(app.path)
    copy_directory(os.path.join(env["basedir"], 'resources/application-skel'), app.path)
    os.mkdir(os.path.join(app.path, 'app/models'))
    os.mkdir(os.path.join(app.path, 'lib'))
    app.check()
    replaceAll(os.path.join(app.path, 'conf/application.conf'), r'%APPLICATION_NAME%', application_name)
    var_name = secretVarName(app.path)
    env_path = writeAppSecret(app.path, secretKey())
    example_path = writeEnvExample(app.path, var_name)
    print("~ %s written to %s" % (var_name, env_path))
    print("~ %s template written to %s (commit this; it documents required env vars)" % (var_name, example_path))
    print("~")

    # Configure modules
    for m in md:
        # Check dependencies.yml of the module
        depsYaml = os.path.join(env["basedir"], 'modules/%s/conf/dependencies.yml' % m)
        if os.path.exists(depsYaml):
            deps = open(depsYaml).read()
            try:
                moduleDefinition = re.search(r'self:\s*(.*)\s*', deps).group(1)
                replaceAll(os.path.join(app.path, 'conf/dependencies.yml'), r'- play\n', '- play\n    - %s\n' % moduleDefinition )
            except Exception:
                pass

    cmdloader.commands['dependencies'].execute(command='dependencies', app=app, args=['--sync'], env=env, cmdloader=cmdloader)

    # Set up Nuxt 3 frontend if requested
    if frontend:
        setup_nuxt_frontend(app, application_name, env)

    print("~ OK, the application is created.")
    print("~ Start it with : play run %s" % sys.argv[2])
    if frontend:
        print("~ Start the frontend with : cd %s/frontend && pnpm install && pnpm dev" % sys.argv[2])
    print("~ Have fun!")
    print("~")

def setup_nuxt_frontend(app, application_name, env):
    """Set up a Nuxt 3 + Tailwind CSS frontend alongside the Play app."""
    nuxt_skel = os.path.join(env["basedir"], 'resources/nuxt-skel')
    frontend_path = os.path.join(app.path, 'frontend')

    print("~")
    print("~ Setting up Nuxt 3 frontend...")

    # Copy Nuxt skeleton to frontend/
    copy_directory(nuxt_skel, frontend_path)

    # Remove the ApiController.java from frontend/ (it was copied with the skeleton)
    api_controller_src = os.path.join(frontend_path, 'ApiController.java')
    api_controller_dst = os.path.join(app.path, 'app/controllers/ApiController.java')
    if os.path.exists(api_controller_src):
        shutil.move(api_controller_src, api_controller_dst)

    # Replace application name placeholders
    for root, dirs, files in os.walk(frontend_path):
        for f in files:
            filepath = os.path.join(root, f)
            try:
                replaceAll(filepath, r'%APPLICATION_NAME%', application_name)
            except Exception:
                pass

    # Add API routes before the catch-all route
    routes_file = os.path.join(app.path, 'conf/routes')
    with open(routes_file, 'r') as f:
        routes = f.read()
    api_routes = "# API endpoints for Nuxt frontend\nGET     /api/status                ApiController.status\n\n"
    routes = routes.replace("# Catch all\n", api_routes + "# Catch all\n")
    with open(routes_file, 'w') as f:
        f.write(routes)

    # Add .gitignore for Nuxt build artifacts
    with open(os.path.join(frontend_path, '.gitignore'), 'w') as f:
        f.write("node_modules\n.nuxt\n.output\ndist\n")

    print("~ Nuxt 3 frontend created in %s/frontend" % os.path.normpath(app.path))
    print("~")

process = None

def handle_sigterm(signum, frame):
    global process
    if 'process' in globals():
        process.terminate()
        sys.exit(0)

first_sigint = True

def handle_sigint(signum, frame):
    global process
    global first_sigint
    if 'process' in globals():
        if first_sigint:
            # Prefix with new line because ^C usually appears on the terminal
            print("\nTerminating Java process")
            process.terminate()
            first_sigint = False
        else:
            print("\nKilling Java process")
            process.kill()

def run(app, args):
    global process
    app.check()

    print("~ Ctrl+C to stop")
    print("~ ")
    java_cmd = app.java_cmd(args)
    try:
        process = subprocess.Popen (java_cmd, env=os.environ)
        signal.signal(signal.SIGTERM, handle_sigterm)
        signal.signal(signal.SIGINT, handle_sigint)
        return_code = process.wait()
        if 0 != return_code:
            sys.exit(return_code)
    except OSError:
        print("Could not execute the java executable, please make sure the JAVA_HOME environment variable is set properly (the java executable should reside at JAVA_HOME/bin/java). ")
        sys.exit(-1)
    print("~")

def clean(app):
    app.check()
    tmp = app.readConf('play.tmp')
    if tmp is None or not tmp.strip():
        tmp = 'tmp'
    print("~ Deleting %s" % os.path.normpath(os.path.join(app.path, tmp)))
    if os.path.exists(os.path.join(app.path, tmp)):
        shutil.rmtree(os.path.join(app.path, tmp))
    print("~")

def show_modules(app, args):
    app.check()
    modules = app.modules()
    if len(modules):
        print("~ Application modules are:")
        print("~ ")
        for module in modules:
            print("~ %s" % module)
    else:
        print("~ No modules installed in this application")
    print("~")
    sys.exit(0)

def id(play_env):
    if not play_env["id"]:
        print("~ framework ID is not set")
    new_id = input("~ What is the new framework ID (or blank to unset)? ")
    if new_id:
        print("~")
        print("~ OK, the framework ID is now %s" % new_id)
        print("~")
        open(play_env["id_file"], 'w').write(new_id)
    else:
        print("~")
        print("~ OK, the framework ID is unset")
        print("~")
        if os.path.exists(play_env["id_file"]):
            os.remove(play_env["id_file"])

# ~~~~~~~~~ UTILS

def kill(pid):
    if os.name == 'nt':
        import ctypes
        handle = ctypes.windll.kernel32.OpenProcess(1, False, int(pid))
        if not ctypes.windll.kernel32.TerminateProcess(handle, 0):
            print("~ Cannot kill the process with pid %s (ERROR %s)" % (pid, ctypes.windll.kernel32.GetLastError()))
            print("~ ")
            sys.exit(-1)
    else:
        try:
            os.kill(int(pid), 15)
        except OSError:
            print("~ Play was not running (Process id %s not found)" % pid)
            print("~")
            sys.exit(-1)
