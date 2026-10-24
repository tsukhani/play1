// A build of its own, run by `ant dependency-check` (PF-195). Not a project of the root build:
// a distribution runs the root build for `play new`, and every one of those would then resolve
// dependency-check and its libraries.
rootProject.name = "play1-dependency-check"
