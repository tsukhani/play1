### Requirement: Regex patterns use raw strings
All string literals passed to `re.match()`, `re.sub()`, `re.search()`, `re.findall()`, or `re.compile()` in the CLI codebase SHALL use raw string syntax (`r"..."`) when they contain backslash escape sequences.

#### Scenario: No warnings on Python 3.12+
- **WHEN** the `./play` command is executed on Python 3.12 or later
- **THEN** no `SyntaxWarning: invalid escape sequence` messages SHALL be emitted

#### Scenario: No errors on Python 3.14+
- **WHEN** the `./play` command is executed on Python 3.14 or later
- **THEN** no `SyntaxError` SHALL be raised due to invalid escape sequences

#### Scenario: Regex behavior unchanged
- **WHEN** a regex pattern is converted from a non-raw string to a raw string
- **THEN** the compiled regex SHALL match the same inputs as before the conversion

### Requirement: Dynamically constructed regex fragments use raw strings
String literals used as fragments for building regex patterns (e.g., replacement strings passed to `str.replace()` that insert backslash-escaped characters for later regex compilation) SHALL use raw string syntax (`r"..."`).

#### Scenario: Regex construction in utils produces valid patterns
- **WHEN** `play.utils` builds a regex pattern by escaping special characters (e.g., `$`, `{`, `}`, `.`)
- **THEN** the constructed pattern SHALL contain literal backslash characters before the special characters
- **AND** no `SyntaxWarning` SHALL be emitted during module import

#### Scenario: Path traversal detection regex works correctly
- **WHEN** `play.utils` checks a path for directory traversal using the `^\.\.` pattern
- **THEN** the regex SHALL correctly match paths starting with `..`
- **AND** the pattern string SHALL use raw string syntax

### Requirement: Valid escape sequences are not modified
String literals containing recognized Python escape sequences (e.g., `\\`, `\n`, `\t`) SHALL NOT be converted to raw strings, as this would change their runtime behavior.

#### Scenario: Double-backslash literals preserved
- **WHEN** a string contains `\\` (a recognized escape producing a literal backslash)
- **THEN** it SHALL remain as `\\` and NOT be changed to `r"\\"`
- **AND** the runtime value SHALL be a single backslash character
