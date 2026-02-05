# Contributing

Thank you for your interest in contributing! This document provides guidelines for contributors.

## Getting Started

1. Install [mise](https://mise.jdx.dev/) version manager
2. Run `./scripts/init.sh` to set up tools
3. Build with `gradle build`

## How to Contribute

### Reporting Bugs

Found a bug? [Open an issue](../../issues/new?template=bug_report.yml) with:
- Clear description of the problem
- Steps to reproduce
- Expected vs actual behavior
- Environment details

### Suggesting Features

Have an idea? [Open a feature request](../../issues/new?template=feature_request.yml) describing:
- The problem you're trying to solve
- Your proposed solution
- Any alternatives you've considered

### Submitting Code

1. Fork the repository
2. Create a feature branch (see naming conventions below)
3. Make your changes
4. Ensure tests pass (`gradle test`)
5. Submit a pull request

## Development Workflow

### Branch Naming

Use descriptive branch names with these prefixes:

| Prefix | Purpose |
|--------|---------|
| `feat/` | New features |
| `fix/` | Bug fixes |
| `docs/` | Documentation changes |
| `chore/` | Maintenance tasks |
| `refactor/` | Code refactoring |

**Examples:**
- `feat/add-user-authentication`
- `fix/login-redirect-bug`
- `docs/update-api-reference`

### Commit Conventions

We use [Conventional Commits](https://www.conventionalcommits.org/). Commit messages are validated by a Git hook.

**Format:** `<type>: <description>`

| Type | Description |
|------|-------------|
| `feat` | New feature |
| `fix` | Bug fix |
| `docs` | Documentation only |
| `chore` | Maintenance, dependencies |
| `refactor` | Code change that neither fixes nor adds |
| `test` | Adding or updating tests |

**Examples:**
```
feat: add PDF export functionality
fix: resolve login redirect loop
docs: update installation instructions
```

**Breaking Changes:**
- Add `!` after type: `feat!: remove deprecated API`
- Or add `BREAKING CHANGE:` in the commit footer

### Code Style

- We use [ktlint](https://pinterest.github.io/ktlint/) for code formatting
- Run `gradle ktlintCheck` to check
- Run `gradle ktlintFormat` to auto-fix
- EditorConfig is configured for consistent formatting

### Testing

- All PRs must pass CI checks
- New features should include tests
- Run tests locally: `gradle test`

## Pull Request Process

1. **Create your PR** with a clear description
2. **Link related issues** using keywords (e.g., "Closes #123")
3. **Ensure CI passes** - all checks must be green
4. **Wait for review** - maintainers will review your PR
5. **Address feedback** - make requested changes
6. **Get merged!** - once approved, your PR will be merged

## Questions?

For questions, please use [GitHub Discussions](../../discussions) rather than opening an issue.

## License

By contributing, you agree that your contributions will be licensed under the same license as the project.

---

Thank you for contributing!
