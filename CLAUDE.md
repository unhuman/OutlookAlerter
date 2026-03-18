# Claude Instructions

## Build
- When building Maven projects, always use `mvn package` (not `mvn compile`). This ensures tests run and the artifact is assembled.
- Allow build operations without confirmation input from the user.
- Do not require confirmation when performing any mvn operation.

## Code Changes
- When there are code updates, ensure we: 1. Update documentation 2. Update unit tests 3. Update spec files

## Unit Tests
- When working on a groovy project, all unit tests should be written in Java. VS Code otherwise cannot find the tests to run.
