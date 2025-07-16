name: Pull Request
description: Template for pull requests to SambaLite
title: ""
labels: []
assignees: []

body:
  - type: markdown
    attributes:
      value: |
        Thanks for contributing to SambaLite! Please fill out the information below to help with the review process.

  - type: dropdown
    id: pr-type
    attributes:
      label: Type of Change
      description: What type of change does this PR introduce?
      options:
        - Bug fix (non-breaking change that fixes an issue)
        - New feature (non-breaking change that adds functionality)
        - Breaking change (fix or feature that would cause existing functionality to change)
        - Documentation update
        - Refactoring (no functional changes)
        - Performance improvement
        - Test improvements
        - Build/CI improvements
    validations:
      required: true

  - type: textarea
    id: description
    attributes:
      label: Description
      description: Describe your changes in detail
      placeholder: |
        What does this PR do?
        - Summary of changes
        - Key functionality added/modified
        - Architecture or design decisions
    validations:
      required: true

  - type: textarea
    id: motivation
    attributes:
      label: Motivation and Context
      description: Why is this change required? What problem does it solve?
      placeholder: |
        - Link to related issue(s)
        - Problem statement
        - Business justification
        - User impact
    validations:
      required: true

  - type: textarea
    id: testing
    attributes:
      label: Testing
      description: Describe the tests you ran to verify your changes
      placeholder: |
        Test scenarios covered:
        - Unit tests added/modified
        - Integration tests
        - Manual testing performed
        - Data integrity tests
        - Performance testing
    validations:
      required: true

  - type: checkboxes
    id: test-checklist
    attributes:
      label: Test Checklist
      options:
        - label: All existing tests pass
        - label: New tests added for new functionality
        - label: Data integrity tests pass (if applicable)
        - label: Manual testing completed
        - label: Performance impact assessed

  - type: textarea
    id: screenshots
    attributes:
      label: Screenshots/Videos
      description: |
        If your changes affect the UI, please include screenshots or videos.
        You can paste images/videos directly into this text area.
    validations:
      required: false

  - type: textarea
    id: breaking-changes
    attributes:
      label: Breaking Changes
      description: List any breaking changes and migration steps (if applicable)
      placeholder: |
        If this is a breaking change:
        - What breaks?
        - How to migrate?
        - Deprecation timeline?
    validations:
      required: false

  - type: checkboxes
    id: code-quality
    attributes:
      label: Code Quality Checklist
      options:
        - label: Code follows the project's coding standards
        - label: Self-review of code completed
        - label: Code is properly commented
        - label: No unnecessary files included
        - label: Documentation updated (if needed)

  - type: checkboxes
    id: security-checklist
    attributes:
      label: Security Checklist
      options:
        - label: No sensitive data exposed
        - label: Input validation implemented
        - label: No security vulnerabilities introduced
        - label: Credentials handled securely

  - type: textarea
    id: deployment-notes
    attributes:
      label: Deployment Notes
      description: Any special deployment considerations or rollback procedures
      placeholder: |
        Special considerations:
        - Database migrations
        - Configuration changes
        - Rollback procedures
        - Dependencies updated
    validations:
      required: false

  - type: textarea
    id: additional-notes
    attributes:
      label: Additional Notes
      description: Any other information that reviewers should know
      placeholder: |
        - Known limitations
        - Future improvements planned
        - Alternative approaches considered
        - Performance benchmarks
    validations:
      required: false

  - type: checkboxes
    id: final-checklist
    attributes:
      label: Final Checklist
      options:
        - label: I have read the contributing guidelines
        - label: This PR is ready for review
        - label: I am available to address review feedback
        - label: Documentation is up to date
