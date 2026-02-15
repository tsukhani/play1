## Context

The Play CLI is a Python 3 script (`./play`) with command modules in `framework/pym/play/`. Several files use regex patterns in non-raw strings (e.g., `"\d+"` instead of `r"\d+"`). Python 3.12 escalated unrecognized escape sequences from `DeprecationWarning` to `SyntaxWarning` (visible by default), and Python 3.14 will make them `SyntaxError`. Six files contain ~12 problematic lines.

## Goals / Non-Goals

**Goals:**
- Eliminate all `SyntaxWarning` messages when running `./play` on Python 3.12+
- Ensure the CLI will not break on Python 3.14+ when invalid escapes become `SyntaxError`
- Preserve identical runtime behavior — no functional changes

**Non-Goals:**
- Broader Python modernization (f-strings, type hints, etc.)
- Changing regex logic or fixing regex correctness issues (e.g., the `netbeans.py` pattern uses `[.svn|git|...]` which is technically a character class, not alternation — that's a separate bug)
- Adding `from __future__` imports or Python version guards

## Decisions

### 1. Use raw string prefix (`r"..."`) for all regex patterns

**Decision:** Prefix every string passed to `re.match()`, `re.sub()`, `re.search()`, `re.findall()`, or used to build regex patterns with `r"..."`.

**Rationale:** Raw strings are the standard Python idiom for regex. They produce identical bytecode for unrecognized escape sequences, so the change is purely syntactic with zero behavioral impact. The alternative — doubling backslashes (`"\\d+"`) — is harder to read and more error-prone.

### 2. Use raw strings for dynamically constructed regex fragments in `utils.py`

**Decision:** In `utils.py`, lines like `searchExp.replace('$', '\$')` should become `searchExp.replace('$', r'\$')`. The string `'^\.\.(' + sep + '\.\.)*$'` should become `r'^\.\.(' + sep + r'\.\.)*$'`.

**Rationale:** These aren't direct `re.*()` calls but build regex strings that are later compiled. The same raw string fix applies — the replacement strings need the literal backslash character, which raw strings provide correctly.

### 3. Use raw string for non-regex backslash literal in `utils.py` line 222

**Decision:** The expression `root.find('\\.')` contains a valid escape (`\\` → literal backslash), but for consistency and clarity, it can remain as-is since `\\` is a recognized escape sequence and does not produce a warning. Only change it if it triggers a warning.

**Rationale:** `\\` is a valid Python escape sequence (literal backslash). It will not trigger warnings in 3.12+ or errors in 3.14+. Changing it unnecessarily could introduce bugs.

## Risks / Trade-offs

- **Risk: Missed occurrence** → Mitigation: Run `python3 -W error::SyntaxWarning ./play` after the fix to verify zero warnings remain. Also use `python3 -c "import py_compile; py_compile.compile('file.py', doraise=True)"` on each changed file.
- **Risk: Raw string changes semantics** → Mitigation: For unrecognized escape sequences (`\d`, `\s`, `\w`, `\.`), raw strings produce the same result as non-raw strings in Python 3.11 and earlier. The change is safe. Only `\\` (double backslash) has different behavior in raw vs non-raw, and we avoid changing those.
- **Risk: Regex in netbeans.py is incorrect** → Mitigation: Out of scope. The `[.svn|git|...]` pattern is a character class, not alternation — it happens to work by accident. We fix only the escape sequence, not the regex logic.
