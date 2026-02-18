## Context

Play Framework 1.11.x uses the Eclipse JDT compiler (ECJ) for dev-mode hot-reload compilation. The current ECJ version (3.40.0) supports Java source/target levels 17–23. Java 24 was released March 2025 and Java 25 (the next LTS) in September 2025. Users on these JDKs cannot set `java.source` to match their runtime, and dev-mode compilation fails when application code uses language features beyond Java 23.

The framework's runtime version gate (blocklist of Java < 17) already permits Java 24 and 25 to run. The only blocker is the ECJ compiler's version map.

Current library versions:
- `ecj-3.40.0.jar` / `org.eclipse.jdt.core-3.40.0.jar` — supports up to Java 23
- `asm-9.8.jar` — supports class file versions through Java 24+
- `javassist-3.30.2-GA.jar` — version-agnostic bytecode manipulation

## Goals / Non-Goals

**Goals:**
- Users can run Play applications on Java 24 and Java 25 JDKs with full dev-mode compilation
- The `java.source` configuration property accepts `"24"` and `"25"` as valid values
- Existing applications on Java 17–23 are completely unaffected

**Non-Goals:**
- Changing the minimum supported Java version (stays at 17)
- Changing the framework's own build target (stays at 21)
- Adding support for Java preview features (e.g. `--enable-preview`)
- Updating Groovy or other template engine dependencies

## Decisions

### 1. Upgrade ECJ to the latest release supporting Java 25

**Decision**: Upgrade both `ecj-*.jar` and `org.eclipse.jdt.core-*.jar` to the latest version that provides `CompilerOptions.VERSION_24` and `CompilerOptions.VERSION_25`.

**Rationale**: ECJ is the only component that has a hard version ceiling. All other libraries (ASM 9.8, Javassist 3.30.2) handle class files generically and already work with Java 25. Upgrading ECJ is the minimum necessary change.

**Alternative considered**: Switching to javac for dev-mode compilation — rejected because ECJ is deeply integrated into `ApplicationCompiler` and provides in-process compilation without forking, which is essential for fast hot-reload cycles.

### 2. Add version map entries for Java 24 and 25

**Decision**: Add `Map.entry("24", CompilerOptions.VERSION_24)` and `Map.entry("25", CompilerOptions.VERSION_25)` to `compatibleJavaVersions` in `ApplicationCompiler.java`.

**Rationale**: The version map is an explicit allowlist. Without these entries, users who set `java.source=24` or `java.source=25` get a `CompilationException` even if the ECJ library supports those versions. Both entries must be added together since Java 24 users exist today.

### 3. Keep ASM and Javassist at current versions unless compilation fails

**Decision**: Do not proactively upgrade ASM or Javassist. Only upgrade if the new ECJ version introduces an incompatibility or if testing reveals class file handling issues.

**Rationale**: ASM 9.8 already supports Java 24 class file format. Javassist uses its own bytecode handling that is not version-gated. Unnecessary upgrades increase risk of regressions in bytecode enhancement (used by JPA entity enhancement, controller method interception, and continuations).

### 4. Keep framework build target at Java 21

**Decision**: The `build-release` property in `build.xml` stays at `21`.

**Rationale**: The framework itself should compile to the lowest reasonable target to maximize compatibility. Java 25 support means users can *run* on Java 25, not that the framework *requires* it.

## Risks / Trade-offs

**[ECJ API changes]** → Newer ECJ versions occasionally change internal APIs. Mitigation: `ApplicationCompiler` only uses stable `CompilerOptions` constants and the `Compiler` class; these are unlikely to break. Verify compilation of existing tests after upgrade.

**[New Java 25 language features may surface bugs]** → If Java 25 introduces new syntax (sealed classes refinements, pattern matching extensions, etc.), ECJ's handling may have edge cases. Mitigation: This is an ECJ concern, not a Play concern. Users hitting ECJ bugs can pin `java.source` to an earlier version as a workaround.

**[JAR size increase]** → Newer ECJ JARs may be slightly larger. Mitigation: Negligible impact; ECJ is already ~3 MB.
