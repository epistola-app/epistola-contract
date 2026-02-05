# Epistola API Clients

Official API client libraries for [Epistola](https://github.com/sdegroot/epistola) in multiple programming languages.

## Available Clients

| Client | Language | Status | Directory |
|--------|----------|--------|-----------|
| Kotlin | Kotlin/JVM | In Development | [`epistola-client-kotlin/`](./epistola-client-kotlin/) |

More clients (TypeScript, Python, Go, etc.) may be added in the future.

## Repository Structure

```
├── epistola-client-kotlin/     # Kotlin/JVM client
│   ├── lib/                    # Client library
│   ├── app/                    # Example application
│   └── gradle/                 # Gradle build configuration
├── .github/
│   └── workflows/              # CI/CD pipelines (parallel builds)
├── scripts/
│   └── init.sh                 # Developer setup script
└── .mise.toml                  # Tool versions
```

Each client is a self-contained project with its own build system:
- **Kotlin**: Gradle with Kotlin DSL
- Future clients will use their language's standard build tools

## Quick Start

### Prerequisites

Install [mise](https://mise.jdx.dev/) version manager:

```bash
# macOS
brew install mise

# Other platforms: https://mise.jdx.dev/getting-started.html
```

### Setup

1. **Clone the repository**

2. **Initialize the development environment:**
   ```bash
   ./scripts/init.sh
   ```

3. **Restart your shell** to activate mise

### Building a Client

Each client has its own build commands. Navigate to the client directory and use its build system.

#### Kotlin Client

```bash
cd epistola-client-kotlin

# Build and test
./gradlew build

# Run tests only
./gradlew test

# Check code style
./gradlew ktlintCheck

# Fix code style
./gradlew ktlintFormat
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

### Commit Conventions

This project uses [Conventional Commits](https://www.conventionalcommits.org/). Commits are validated by a Git hook.

```bash
feat(kotlin): add new feature
fix(kotlin): resolve bug
docs: update documentation
chore: maintenance task
```

Use scope to indicate which client is affected (e.g., `feat(kotlin):`, `fix(typescript):`).

### Adding a New Client

1. Create a new directory: `epistola-client-<language>/`
2. Set up the language's standard build system
3. Add a job to `.github/workflows/build.yml`
4. Update this README

## CI/CD

All clients are built and tested in parallel via GitHub Actions. Each client:
- Runs its own test suite
- Generates coverage reports
- Produces SBOM (Software Bill of Materials)
- Undergoes vulnerability scanning

## License

This project is licensed under the [European Union Public License 1.2](LICENSE) (EUPL-1.2).
