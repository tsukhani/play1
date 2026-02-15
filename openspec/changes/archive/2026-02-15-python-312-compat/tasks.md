## 1. Fix regex patterns in command modules

- [x] 1.1 In `framework/pym/play/application.py` line 446, change `'\${([a-z.]+)}'` to `r'\${([a-z.]+)}'`
- [x] 1.2 In `framework/pym/play/commands/check.py` line 75, change `"\d+[\.\d+]+"` to `r"\d+[\.\d+]+"`
- [x] 1.3 In `framework/pym/play/commands/help.py` line 26, change `'[-\s]+'` to `r'[-\s]+'` and `'[^\w\s-]'` to `r'[^\w\s-]'`
- [x] 1.4 In `framework/pym/play/commands/modulesrepo.py` line 85, change `"^\s*#"` to `r"^\s*#"`
- [x] 1.5 In `framework/pym/play/commands/netbeans.py` line 44, change `"\.[svn|git|hg|scc|vssscc]"` to `r"\.[svn|git|hg|scc|vssscc]"`

## 2. Fix regex patterns in utils.py

- [x] 2.1 In `framework/pym/play/utils.py` line 47, change `'^\.\.(' + sep + '\.\.)*$'` to `r'^\.\.(' + sep + r'\.\.)*$'`
- [x] 2.2 In `framework/pym/play/utils.py` line 258, change `'version "([a-zA-Z0-9\.\-_]{1,})"'` to `r'version "([a-zA-Z0-9\.\-_]{1,})"'`

## 3. Verification

- [x] 3.1 Run `python3 -W error::SyntaxWarning ./play` and confirm zero warnings are emitted
- [x] 3.2 Run `python3 -c "import py_compile; py_compile.compile('<file>', doraise=True)"` for each of the 6 modified files to confirm no syntax warnings
