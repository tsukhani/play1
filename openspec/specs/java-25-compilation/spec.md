## ADDED Requirements

### Requirement: Java 25 is the only accepted source level
The dev-mode compiler SHALL accept `"25"` as the sole valid value for the `java.source` configuration property. The default `java.source` value SHALL be `"25"`. Pre-25 source levels SHALL be rejected at startup with a `CompilationException` listing the supported set.

#### Scenario: Compile with java.source=25
- **WHEN** `java.source` is set to `"25"` in `application.conf` and the application is running on a Java 25+ JDK
- **THEN** the dev-mode compiler SHALL compile application classes at Java 25 language level
- **AND** the compilation SHALL succeed for code using Java 25 language features

#### Scenario: Default java.source applies Java 25
- **WHEN** no `java.source` property is set in `application.conf`
- **THEN** the dev-mode compiler SHALL compile at Java 25 language level

#### Scenario: Pre-25 java.source rejected
- **WHEN** `java.source` is set to any value other than `"25"` (including `"17"`, `"21"`, `"24"`)
- **THEN** the compiler SHALL throw a `CompilationException` listing the valid versions

### Requirement: ECJ library supports Java 25
The framework SHALL bundle an Eclipse JDT/ECJ version that provides `CompilerOptions.VERSION_25`. The bundled ECJ version SHALL support parsing and compiling all non-preview language features defined in Java 25.

#### Scenario: ECJ version provides Java 25 compiler options
- **WHEN** the framework loads the ECJ library
- **THEN** `CompilerOptions.VERSION_25` SHALL be resolvable at compile time without errors

#### Scenario: ECJ compiles Java 25 source
- **WHEN** application source uses Java 25 syntax and `java.source=25`
- **THEN** the ECJ compiler SHALL produce valid bytecode with the correct class file version

### Requirement: Runtime requires Java 25
The framework SHALL start and run only on Java 25 or later JDKs. The runtime version gate SHALL reject any JDK older than 25 with a clear `CompilationException` referring to the unsupported version.

#### Scenario: Framework starts on Java 25
- **WHEN** the framework is launched on a Java 25 JDK
- **THEN** the framework SHALL start without version-related errors
- **AND** dev-mode hot-reload compilation SHALL function correctly

#### Scenario: Framework rejects pre-25 JDK
- **WHEN** the framework is launched on any JDK older than 25 (including 17–24)
- **THEN** the framework SHALL throw a `CompilationException` stating that the JDK is unsupported
- **AND** the framework SHALL NOT proceed with classloading or request handling

### Requirement: Bytecode processing compatibility
All bytecode processing pipelines (JPA entity enhancement, controller method interception, classloading) SHALL handle class files produced at Java 25 target levels without errors.

#### Scenario: Enhanced entity on Java 25 bytecode
- **WHEN** a JPA entity class is compiled at Java 25 level and bytecode enhancement runs
- **THEN** the enhancement SHALL complete without errors
- **AND** the enhanced class SHALL load and function correctly

#### Scenario: Controller enhancement on Java 25 bytecode
- **WHEN** a controller class is compiled at Java 25 level and the framework applies bytecode interception
- **THEN** the enhancement SHALL complete without errors
- **AND** controller actions SHALL be invocable
