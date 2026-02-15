## Why

Python 3.12 changed invalid escape sequences in strings from `DeprecationWarning` to `SyntaxWarning`, making them visible by default. Running `./play` now produces many `invalid escape sequence` warnings. Python 3.14 will escalate these to `SyntaxError`, breaking the CLI entirely. The Play CLI must be updated to use raw strings for all regex patterns and string literals containing backslash sequences.

## What Changes

- Convert non-raw strings containing regex escape sequences (`\d`, `\s`, `\w`, `\.`, `\$`, etc.) to raw strings (`r"..."`) across 6 Python files in the CLI tooling
- Fix string escaping in utility functions that build regex patterns dynamically
- No behavioral changes — all fixes are syntactic (raw string prefix), preserving identical runtime behavior

## Capabilities

### New Capabilities
- `python-312-compat`: Ensures the Play CLI Python code uses valid escape sequences compatible with Python 3.12+ (raw strings for regex patterns and backslash-containing literals)

### Modified Capabilities
<!-- None — this is a fix to the CLI tooling, not a change to framework behavior -->

## Impact

- **Files affected** (6 files, ~12 lines):
  - `framework/pym/play/application.py` — regex in config variable expansion
  - `framework/pym/play/commands/check.py` — version number parsing regex
  - `framework/pym/play/commands/help.py` — slug generation regexes
  - `framework/pym/play/commands/modulesrepo.py` — comment detection regex
  - `framework/pym/play/commands/netbeans.py` — VCS directory filtering regex
  - `framework/pym/play/utils.py` — regex construction utilities and Java version parsing
- **No API or behavioral changes** — all modifications are syntactically equivalent
- **Backward compatible** — raw strings produce identical bytecode to their non-raw equivalents when the escape sequences aren't recognized by Python
- **Python version support** — fixes warnings on 3.12+, prevents breakage on 3.14+, remains compatible with 3.6+
