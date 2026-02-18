## ADDED Requirements

### Requirement: Java 24 and 25 compilation targets
The dev-mode compiler SHALL accept `"24"` and `"25"` as valid values for the `java.source` configuration property. When set, the Eclipse JDT compiler SHALL compile application source code at the corresponding Java language level. The default `java.source` value SHALL remain `"17"`.

#### Scenario: Compile with java.source=24
- **WHEN** `java.source` is set to `"24"` in `application.conf` and the application is running on a Java 24+ JDK
- **THEN** the dev-mode compiler SHALL compile application classes at Java 24 language level
- **AND** the compilation SHALL succeed for code using Java 24 language features

#### Scenario: Compile with java.source=25
- **WHEN** `java.source` is set to `"25"` in `application.conf` and the application is running on a Java 25+ JDK
- **THEN** the dev-mode compiler SHALL compile application classes at Java 25 language level
- **AND** the compilation SHALL succeed for code using Java 25 language features

#### Scenario: Default java.source unchanged
- **WHEN** no `java.source` property is set in `application.conf`
- **THEN** the dev-mode compiler SHALL compile at Java 17 language level, identical to current behavior

#### Scenario: Invalid java.source rejected
- **WHEN** `java.source` is set to a value not in the supported set (17–25)
- **THEN** the compiler SHALL throw a `CompilationException` listing the valid versions

### Requirement: ECJ library supports Java 25
The framework SHALL bundle an Eclipse JDT/ECJ version that provides `CompilerOptions.VERSION_24` and `CompilerOptions.VERSION_25` constants. The bundled ECJ version SHALL support parsing and compiling all non-preview language features defined in Java 24 and Java 25.

#### Scenario: ECJ version provides Java 25 compiler options
- **WHEN** the framework loads the ECJ library
- **THEN** `CompilerOptions.VERSION_24` and `CompilerOptions.VERSION_25` SHALL be resolvable at compile time without errors

#### Scenario: ECJ compiles Java 25 source
- **WHEN** application source uses Java 25 syntax and `java.source=25`
- **THEN** the ECJ compiler SHALL produce valid bytecode with the correct class file version

### Requirement: Runtime on Java 24 and 25 JDKs
The framework SHALL start and run correctly on Java 24 and Java 25 JDKs. The existing runtime version gate (which rejects Java < 17) SHALL continue to permit Java 24 and 25 without modification.

#### Scenario: Framework starts on Java 25
- **WHEN** the framework is launched on a Java 25 JDK
- **THEN** the framework SHALL start without version-related errors
- **AND** dev-mode hot-reload compilation SHALL function correctly

#### Scenario: Framework starts on Java 24
- **WHEN** the framework is launched on a Java 24 JDK
- **THEN** the framework SHALL start without version-related errors

#### Scenario: Existing Java 17–23 unaffected
- **WHEN** the framework is launched on any JDK from 17 through 23
- **THEN** behavior SHALL be identical to before this change

### Requirement: Bytecode processing compatibility
All bytecode processing pipelines (JPA entity enhancement, controller method interception, classloading) SHALL handle class files produced at Java 24 and Java 25 target levels without errors.

#### Scenario: Enhanced entity on Java 25 bytecode
- **WHEN** a JPA entity class is compiled at Java 25 level and bytecode enhancement runs
- **THEN** the enhancement SHALL complete without errors
- **AND** the enhanced class SHALL load and function correctly

#### Scenario: Controller enhancement on Java 25 bytecode
- **WHEN** a controller class is compiled at Java 25 level and the framework applies bytecode interception
- **THEN** the enhancement SHALL complete without errors
- **AND** controller actions SHALL be invocable

### Requirement: Backward compatibility of supported versions
The `compatibleJavaVersions` map SHALL continue to include all previously supported versions (17, 18, 19, 20, 21, 22, 23) in addition to the new entries for 24 and 25. No existing version entries SHALL be removed.

#### Scenario: All prior versions remain valid
- **WHEN** `java.source` is set to any value from `"17"` through `"23"`
- **THEN** compilation SHALL succeed identically to before this change
