## Why

Java 25 (GA September 2025) is the latest LTS release. Play Framework 1.11.x targets Java 21 and supports 17+ at runtime, but the Eclipse JDT compiler (ECJ 3.40.0) used for dev-mode compilation only supports up to Java 23. Users running Java 24 or 25 cannot compile their application code in dev mode, making the framework unusable on these JDK versions.

## What Changes

- Upgrade Eclipse JDT/ECJ from 3.40.0 to a version that supports Java 25 class file format and language features
- Add Java 24 and Java 25 entries to the `compatibleJavaVersions` map in `ApplicationCompiler.java` so the dev-mode compiler can target these versions
- Verify ASM 9.8 (used by Commons Javaflow) handles Java 25 class files; upgrade if needed
- Verify Javassist 3.30.2-GA handles Java 25 class files; upgrade if needed
- Update `build.xml` with the new ECJ dependency version

## Capabilities

### New Capabilities
- `java-25-compilation`: Covers the ability to compile and run Play applications on Java 24 and Java 25 runtimes, including dev-mode hot-reload compilation via ECJ

### Modified Capabilities

(none — no existing spec-level requirements change; runtime version gating already allows 17+)

## Impact

- **ApplicationCompiler.java** — version map gains two new entries (Java 24, 25)
- **build.xml** — ECJ/JDT dependency version bumped
- **framework/lib/** — updated ECJ JAR(s) swapped in
- **Bytecode libraries** — ASM and Javassist may need minor version bumps if they don't yet support Java 25 class file version (69.0)
- **No API changes** — no user-facing API additions or removals
- **No breaking changes** — existing applications on Java 17–23 are unaffected
