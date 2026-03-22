# AI Policy

## Tools Used
- Junie by JetBrains / Claude (architecture, implementation, code review, CI/CD setup)
- JetBrains AI Assistant (code completion)

## How AI Is Used
AI tools are used as a significant development partner in this project,
including but not limited to:
- Architecture and design discussions
- Feature implementation and refactoring
- Test development
- CI/CD pipeline and compliance setup
- Code review and optimization

## Rules
1. All AI-generated code is reviewed and understood before integration.
2. The developer maintains full understanding of the codebase and all shipped code.
3. AI suggestions are tested through the project's test suite before merging.
4. All dependencies are checked for license compliance (ScanCode, Gradle License Report).
5. The developer takes full responsibility for all shipped code.

## Scope
This policy covers the development process only.
The app itself does not use AI services, does not connect to AI APIs,
and does not send any user data to AI providers.

## Compliance
- License scanning: ScanCode Toolkit, Gradle License Report
- CI checks: GitHub Actions (ort-compliance.yml)
- ORT Starter-Kit for deep compliance audits
