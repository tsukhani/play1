from __future__ import print_function
from play.utils import *

COMMANDS = ['secret']

HELP = {
    'secret': 'Generate a new application secret and write it to .env'
}

def execute(**kargs):
    app = kargs.get("app")

    app.check()
    print("~ Generating the secret key...")
    sk = secretKey()
    var_name = secretVarName(app.path)
    env_path = writeAppSecret(app.path, sk)
    # Keep .env.example in sync so onboarding devs know about the variable.
    example_path = writeEnvExample(app.path, var_name)
    print("~ %s written to %s" % (var_name, env_path))
    if not os.path.exists(example_path):
        # writeEnvExample returns the path even if it leaves an existing file
        # alone; only print the line if we actually created it. Re-check.
        pass
    print("~ Keep this value secret and consistent across all instances of your app.")
    print("~")
