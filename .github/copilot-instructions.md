# Copilot Instructions

## Build
- When building Maven projects, always use `mvn package` (not `mvn compile`). This ensures tests run and the artifact is assembled.
- Do not require confirmation when performing any mvn operation.

## Code Changes
- When there are code updates, ensure we: 1. Update documentation 2. update unit tests

## Unit Tests
- When working on a groovy project, all unit tests should be written in Java.  VS Code otherwise cannot find the tests to run.

