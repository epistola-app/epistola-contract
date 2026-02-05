# Kotlin Gradle Template

A modern Kotlin + Spring Boot project template with best practices for tooling, CI/CD, and development workflow.

## Features

- **Kotlin 2.3** with Spring Boot 4.0
- **JDK 25** via mise version management
- **Gradle 9.3** with Kotlin DSL and version catalogs
- **ktlint** for code formatting
- **Kover** for test coverage
- **Conventional Commits** enforced via commitlint
- **SSH commit signing** auto-configured
- **GitHub Actions** CI/CD with:
  - Build and test
  - Coverage badges
  - SBOM generation
  - Vulnerability scanning (Trivy)
  - Automatic PR labeling
  - Label sync from config
- **Renovate** for automated dependency updates
- **Issue templates** for bugs and features
- **PR template** with checklist

## Quick Start

### Prerequisites

Install [mise](https://mise.jdx.dev/) version manager:

```bash
# macOS
brew install mise

# Other platforms: https://mise.jdx.dev/getting-started.html
```

### Setup

1. **Use this template** - Click "Use this template" on GitHub or clone manually

2. **Initialize the project:**
   ```bash
   ./scripts/init.sh
   ```
   This will:
   - Install Java, Gradle, Node.js via mise
   - Set up Git hooks for commit validation
   - Configure SSH commit signing

3. **Restart your shell** to activate mise

4. **Build and test:**
   ```bash
   gradle build
   ```

5. **Run the application:**
   ```bash
   gradle :app:bootRun
   ```

## Project Structure

```
├── app/                    # Main application module (Spring Boot)
│   ├── src/main/kotlin/    # Application code
│   └── src/test/kotlin/    # Tests
├── lib/                    # Library module (shared code)
│   ├── src/main/kotlin/    # Library code
│   └── src/test/kotlin/    # Tests
├── gradle/
│   └── libs.versions.toml  # Dependency version catalog
├── .github/
│   ├── workflows/          # CI/CD pipelines
│   ├── ISSUE_TEMPLATE/     # Issue templates
│   └── labels.yml          # Label definitions
├── .husky/                 # Git hooks (commitlint)
├── scripts/
│   └── init.sh             # Developer setup script
├── .mise.toml              # Tool versions
├── build.gradle.kts        # Root build config
└── settings.gradle.kts     # Module includes
```

## Development

### Build Commands

```bash
# Build everything
gradle build

# Run tests
gradle test

# Check code style
gradle ktlintCheck

# Fix code style
gradle ktlintFormat

# Generate coverage report
gradle koverHtmlReport
# View at: build/reports/kover/html/index.html

# Generate SBOM
gradle :app:cyclonedxBom
```

### Commit Conventions

This project uses [Conventional Commits](https://www.conventionalcommits.org/). Commits are validated by a Git hook.

```bash
feat: add new feature
fix: resolve bug
docs: update documentation
chore: maintenance task
refactor: code restructuring
test: add tests
```

**Breaking changes:** Use `feat!:` or `fix!:` or add `BREAKING CHANGE:` in footer.

### Adding Dependencies

Edit `gradle/libs.versions.toml`:

```toml
[versions]
my-lib = "1.0.0"

[libraries]
my-lib = { module = "com.example:my-lib", version.ref = "my-lib" }
```

Then use in `build.gradle.kts`:

```kotlin
dependencies {
    implementation(libs.my.lib)
}
```

## Customization

### Rename the Project

1. Update `settings.gradle.kts`:
   ```kotlin
   rootProject.name = "your-project-name"
   ```

2. Update `build.gradle.kts`:
   ```kotlin
   group = "com.yourcompany"
   description = "Your Project Description"
   ```

3. Update `package.json`:
   ```json
   { "name": "your-project-name" }
   ```

4. Rename packages in `app/` and `lib/` modules

### Add More Modules

1. Create module directory with `build.gradle.kts`
2. Add to `settings.gradle.kts`:
   ```kotlin
   include(":your-module")
   ```

### Enable Docker Builds

Uncomment the `docker` job in `.github/workflows/build.yml` and configure as needed.

## Configuration Files

| File | Purpose |
|------|---------|
| `.mise.toml` | Tool versions (Java, Gradle, Node) |
| `.editorconfig` | Editor formatting rules |
| `gradle/libs.versions.toml` | Dependency versions |
| `.husky/commitlint.config.js` | Commit message rules |
| `renovate.json` | Dependency update settings |
| `.github/labels.yml` | Issue/PR label definitions |
| `.github/labeler.yml` | Auto-labeling rules |

## License

This project is licensed under the [European Union Public License 1.2](LICENSE) (EUPL-1.2).

---

Created from [kotlin-gradle-template](https://github.com/sdegroot/kotlin-gradle-template)
