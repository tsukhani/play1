## 1. Upgrade ECJ dependency

- [x] 1.1 Identify the latest ECJ release that provides `CompilerOptions.VERSION_24` and `CompilerOptions.VERSION_25`
- [x] 1.2 Download the new `ecj-<version>.jar` and `org.eclipse.jdt.core-<version>.jar`
- [x] 1.3 Remove `ecj-3.40.0.jar` and `org.eclipse.jdt.core-3.40.0.jar` from `framework/lib/`
- [x] 1.4 Add the new ECJ/JDT JARs to `framework/lib/`

## 2. Update ApplicationCompiler

- [x] 2.1 Add `Map.entry("24", CompilerOptions.VERSION_24)` to `compatibleJavaVersions` in `framework/src/play/classloading/ApplicationCompiler.java`
- [x] 2.2 Add `Map.entry("25", CompilerOptions.VERSION_25)` to `compatibleJavaVersions`
- [x] 2.3 Verify the class compiles cleanly with `ant compile` from `framework/`

## 3. Verify bytecode library compatibility

- [x] 3.1 Confirm ASM 9.8 handles Java 25 class file version (69.0) — check ASM release notes or test with a Java 25-compiled class
- [x] 3.2 Confirm Javassist 3.30.2-GA handles Java 25 class files — verify no version-gated rejection exists
- [x] 3.3 If either library fails, upgrade to the latest compatible version and replace JARs in `framework/lib/`

## 4. Build and test

- [x] 4.1 Run `ant jar` from `framework/` to build the framework with new dependencies
- [x] 4.2 Run `ant unittest` to verify all existing unit tests pass
- [x] 4.3 Run the `ApplicationCompilerTest` (or equivalent) to confirm versions 17–25 are all accepted
- [x] 4.4 Verify that an invalid `java.source` value (e.g. `"16"`) still throws `CompilationException`
