# Claude Instructions

## Build
- When building Maven projects, always use `mvn package` (not `mvn compile`). This ensures tests run and the artifact is assembled.
- Allow build operations without confirmation input from the user.
- Do not require confirmation when performing any mvn operation.

## Code Changes
- When there are code updates, ensure we: 1. Update documentation 2. Update unit tests 3. Update spec files
- When making code changes, automatically increment the semantic version in `pom.xml` based on the nature of the change, and do this **before** running any build so the version is baked into the artifact:
  - **Patch** (x.y.Z): Bug fixes, minor corrections, documentation-only changes
  - **Minor** (x.Y.0): New features or non-breaking improvements
  - **Major** (X.0.0): Breaking changes, major architectural changes, or API-incompatible changes

## Unit Tests
- When working on a groovy project, all unit tests should be written in Java. VS Code otherwise cannot find the tests to run.
