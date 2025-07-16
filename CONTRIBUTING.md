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

### Coding Style

- Follow standard Java coding conventions
- Use meaningful variable and method names
- Write clear comments for complex logic
- Keep methods focused and concise

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

Das Projekt verfügt über ein umfassendes SMB-Testing-Framework:

**Mock-basierte Tests (schnell):**
```java
SmbTestHelper helper = new SmbTestHelper.Builder()
    .withMockOnly()
    .build();
```

**Container-basierte Tests (realistisch):**
```java
SmbTestHelper helper = new SmbTestHelper.Builder()
    .withContainerOnly()
    .build();
```

**Fehler-Testing:**
```java
helper.setConnectionFailure(true);
helper.setNetworkDelay(500);
```

Siehe [SAMBA_TESTING_FRAMEWORK.md](docs/SAMBA_TESTING_FRAMEWORK.md) für Details.

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