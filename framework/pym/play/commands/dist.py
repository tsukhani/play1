from __future__ import print_function
from builtins import str
import sys
import os
import getopt
import shutil
import zipfile

from play.utils import *

COMMANDS = ["dist"]

HELP = {
    'dist': 'Package the application as a ZIP distribution'
}

# Directories to exclude from the distribution
EXCLUDED_DIRS = {
    'tmp', 'logs', 'test', 'test-result', 'tests',
    'node_modules', '.nuxt', '.output', '.git',
    'nbproject', '.idea', '.settings', '.classpath', '.project',
}


def execute(**kargs):
    command = kargs.get("command")
    app = kargs.get("app")
    args = kargs.get("args")
    env = kargs.get("env")

    output = None
    try:
        optlist, args = getopt.getopt(args, 'o:', ['output='])
        for o, a in optlist:
            if o in ('-o', '--output'):
                output = os.path.normpath(os.path.abspath(a))
    except getopt.GetoptError as err:
        print("~ %s" % str(err))
        print("~ Please specify an output path using -o or --output")
        print("~ ")
        sys.exit(-1)

    app.check()

    if not output:
        output = os.path.join(app.path, 'dist', '%s.zip' % os.path.basename(app.path))

    output_dir = os.path.dirname(output)
    if not os.path.exists(output_dir):
        os.makedirs(output_dir)

    if not output.endswith('.zip'):
        output = output + '.zip'

    print("~ Packaging application to %s ..." % os.path.normpath(output))

    if os.path.exists(output):
        os.remove(output)

    app_name = os.path.basename(app.path)

    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as zf:
        for root, dirs, files in os.walk(app.path):
            # Skip excluded directories (modifies dirs in-place to prevent descent)
            dirs[:] = [d for d in dirs if d not in EXCLUDED_DIRS and not d.startswith('.')]

            # Skip the dist output directory itself
            if os.path.normpath(root).startswith(os.path.normpath(output_dir)) and output_dir != app.path:
                continue

            for f in files:
                if f.startswith('.') or f.endswith('~'):
                    continue
                filepath = os.path.join(root, f)
                arcname = os.path.join(app_name, os.path.relpath(filepath, app.path))
                zf.write(filepath, arcname)

    print("~")
    print("~ Done! Distribution created at %s" % os.path.normpath(output))
    print("~")
