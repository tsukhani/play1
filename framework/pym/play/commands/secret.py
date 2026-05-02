from __future__ import print_function
from play.utils import *

COMMANDS = ['secret']

HELP = {
    'secret': 'Generate a new application secret and write it to certs/.env'
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
    print("~ Keep this value secret and consistent across all instances of your app.")
    print("~")
