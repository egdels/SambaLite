# Contributing to SambaLite

Thank you for your interest in contributing to SambaLite! This document provides guidelines and instructions for contributing to the project.

## Code of Conduct

By participating in this project, you agree to maintain a respectful and inclusive environment for everyone.

## How to Contribute

### Reporting Bugs

If you find a bug, please create an issue in the GitHub issue tracker with the following information:

- A clear, descriptive title
- Steps to reproduce the issue
- Expected behavior
- Actual behavior
- Screenshots (if applicable)
- Device information (Android version, device model)
- Any additional context

### Suggesting Features

Feature suggestions are welcome! Please create an issue with:

- A clear, descriptive title
- A detailed description of the proposed feature
- Any relevant mockups or examples
- Why this feature would be beneficial to the project

### Pull Requests

1. Fork the repository
2. Create a new branch (`git checkout -b feature/amazing-feature`)
3. Make your changes
4. Commit your changes (`git commit -m 'Add some amazing feature'`)
5. Push to the branch (`git push origin feature/amazing-feature`)
6. Open a Pull Request

## Development Guidelines

### Code Quality Checks

The project uses automated code quality tools. Before submitting a PR, ensure all checks pass:

```bash
./gradlew spotlessCheck          # Code formatting (Google Java Format)
./gradlew :app:compileDebugJavaWithJavac  # Error Prone static analysis
./gradlew :app:testDebugUnitTest # Unit tests
./gradlew :app:lintDebug         # Android Lint checks
./gradlew :app:jacocoTestReport  # Test coverage report
```

Or run all checks at once:

```bash
./gradlew spotlessCheck :app:compileDebugJavaWithJavac :app:testDebugUnitTest :app:lintDebug :app:jacocoTestReport
```

#### Spotless (Code Formatting)

All Java code must be formatted with [Google Java Format](https://github.com/google/google-java-format). To auto-fix formatting:

```bash
./gradlew spotlessApply
```

#### Error Prone (Static Analysis)

[Error Prone](https://errorprone.info/) runs during compilation and catches common Java bugs. All warnings are treated as errors; generated code is excluded. Fix any issues reported during compilation.

#### Android Lint

Lint checks cover accessibility, performance, security, unused resources, and more. A `lint-baseline.xml` file tracks accepted issues (e.g., third-party library warnings, architecture-specific patterns). New lint warnings must be resolved — do not add them to the baseline.

#### JaCoCo (Test Coverage)

Coverage reports are generated under `app/build/reports/jacoco/`. Aim to maintain or improve test coverage with your changes.

### Coding Style

- Code is auto-formatted by Spotless (Google Java Format) — no manual formatting needed
- Use meaningful variable and method names
- Write clear comments for complex logic
- Keep methods focused and concise
- Annotate all public/protected API parameters and return types with `@NonNull` or `@Nullable`

### Architecture

- Follow the MVVM architecture pattern
- Use the repository pattern for data access
- Use Dagger for dependency injection
- Keep UI logic in ViewModels, not in Activities/Fragments

### Testing

- Write unit tests for repositories and ViewModels
- Write UI tests for critical user flows
- Use the SMB Testing Framework for SMB-related functionality
- Ensure all tests pass before submitting a PR

#### SMB Testing Framework

The project includes a comprehensive SMB testing framework:

**Mock-based tests (fast):**
```java
SmbTestHelper helper = new SmbTestHelper.Builder()
    .withMockOnly()
    .build();
```

**Container-based tests (realistic):**
```java
SmbTestHelper helper = new SmbTestHelper.Builder()
    .withContainerOnly()
    .build();
```

**Error testing:**
```java
helper.setConnectionFailure(true);
helper.setNetworkDelay(500);
```

See [SAMBA_TESTING_FRAMEWORK.md](docs/SAMBA_TESTING_FRAMEWORK.md) for details.

## Git Workflow

- Keep commits focused and atomic
- Write clear, descriptive commit messages
- Reference issue numbers in commit messages when applicable
- Rebase your branch before submitting a PR

## Review Process

All submissions require review. We strive to review PRs promptly and provide constructive feedback.

## License

By contributing to SambaLite, you agree that your contributions will be licensed under the project's [Apache License 2.0](LICENSE).

Thank you for contributing to SambaLite!