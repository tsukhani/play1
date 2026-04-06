from __future__ import print_function
from builtins import str
import subprocess
import sys
import os
import getopt
import zipfile

from play.utils import *

COMMANDS = ["dist"]

HELP = {
    'dist': 'Package the application as a ZIP distribution'
}

# Fallback exclusions when git is not available
EXCLUDED_DIRS = {
    'tmp', 'logs', 'test', 'test-result', 'tests',
    'node_modules', '.nuxt', '.output', '.git',
    'nbproject', '.idea', '.settings', '.classpath', '.project',
}


def _git_list_files(app_path):
    """Use git to list all non-ignored files. Returns a set of relative paths, or None if git is unavailable."""
    try:
        result = subprocess.run(
            ['git', 'ls-files', '--cached', '--others', '--exclude-standard'],
            capture_output=True, text=True, cwd=app_path, timeout=30
        )
        if result.returncode != 0:
            return None
        files = set()
        for line in result.stdout.strip().splitlines():
            if line:
                files.add(line)
        return files
    except (FileNotFoundError, subprocess.TimeoutExpired):
        return None


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
    git_files = _git_list_files(app.path)

    if git_files is not None:
        print("~ Using .gitignore to determine included files")
        _zip_with_git(app.path, output, app_name, output_dir, git_files)
    else:
        print("~ No git repository detected, using default exclusions")
        _zip_with_fallback(app.path, output, app_name, output_dir)

    print("~")
    print("~ Done! Distribution created at %s" % os.path.normpath(output))
    print("~")


def _zip_with_git(app_path, output, app_name, output_dir, git_files):
    """Create ZIP using git ls-files to honor .gitignore."""
    output_rel = os.path.relpath(output_dir, app_path) if output_dir.startswith(app_path) else None

    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as zf:
        for relpath in sorted(git_files):
            # Skip the dist output directory itself
            if output_rel and relpath.startswith(output_rel + os.sep):
                continue

            filepath = os.path.join(app_path, relpath)
            if not os.path.isfile(filepath):
                continue

            arcname = os.path.join(app_name, relpath)
            zf.write(filepath, arcname)


def _zip_with_fallback(app_path, output, app_name, output_dir):
    """Create ZIP using hardcoded exclusion list as fallback."""
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as zf:
        for root, dirs, files in os.walk(app_path):
            # Skip excluded directories (modifies dirs in-place to prevent descent)
            dirs[:] = [d for d in dirs if d not in EXCLUDED_DIRS and not d.startswith('.')]

            # Skip the dist output directory itself
            if os.path.normpath(root).startswith(os.path.normpath(output_dir)) and output_dir != app_path:
                continue

            for f in files:
                if f.startswith('.') or f.endswith('~'):
                    continue
                filepath = os.path.join(root, f)
                arcname = os.path.join(app_name, os.path.relpath(filepath, app_path))
                zf.write(filepath, arcname)
