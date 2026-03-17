.PHONY: all lint bundle build-client build-server build-editor-model build clean publish-local breaking mock validate-impl release help

# Default target - runs what CI runs
all: lint build

# Validate OpenAPI spec
lint:
	@echo "==> Validating OpenAPI spec..."
	npx @redocly/cli lint epistola-api.yaml

# Bundle OpenAPI spec into single file
bundle:
	@echo "==> Bundling OpenAPI spec..."
	npx @redocly/cli bundle epistola-api.yaml -o openapi.yaml
	@echo "==> Created openapi.yaml"

# Build all modules
build: build-client build-server build-editor-model

# Build Kotlin client
build-client:
	@echo "==> Building Kotlin client..."
	cd client-kotlin-spring-restclient && ./gradlew build

# Build Kotlin server
build-server:
	@echo "==> Building Kotlin server..."
	cd server-kotlin-springboot4 && ./gradlew build

# Build template model
build-editor-model:
	@echo "==> Building template model..."
	cd editor-model && ./gradlew build

# Clean all build artifacts
clean:
	@echo "==> Cleaning..."
	cd client-kotlin-spring-restclient && ./gradlew clean
	cd server-kotlin-springboot4 && ./gradlew clean
	cd editor-model && ./gradlew clean

# Publish to local Maven repository (for testing)
VERSION_ARG := $(if $(VERSION),-Pversion=$(VERSION),)
publish-local: build
	@echo "==> Publishing to local Maven repository..."
	cd client-kotlin-spring-restclient && ./gradlew publishToMavenLocal -x signMavenPublication $(VERSION_ARG)
	cd server-kotlin-springboot4 && ./gradlew publishToMavenLocal -x signMavenPublication $(VERSION_ARG)
	cd editor-model && ./gradlew publishToMavenLocal -x signMavenPublication $(VERSION_ARG)
	@echo "==> Published to ~/.m2/repository/app/epistola/contract/"

# Check for breaking changes against main branch
breaking: bundle
	@echo "==> Checking for breaking changes against main branch..."
	@rm -rf /tmp/epistola-base-spec && mkdir -p /tmp/epistola-base-spec
	@git archive main -- epistola-api.yaml spec/ 2>/dev/null | tar -xC /tmp/epistola-base-spec || cp -r epistola-api.yaml spec/ /tmp/epistola-base-spec/
	@cd /tmp/epistola-base-spec && npx @redocly/cli bundle epistola-api.yaml -o openapi.yaml 2>/dev/null
	oasdiff breaking /tmp/epistola-base-spec/openapi.yaml openapi.yaml

# Start mock server on port 4010
mock: bundle
	@echo "==> Starting Prism mock server on http://localhost:4010..."
	@echo "==> Use Ctrl+C to stop"
	npx --prefix tools @stoplight/prism-cli mock openapi.yaml -p 4010

# Validate implementation against spec (requires running server)
validate-impl: bundle
	@echo "==> Starting contract validation proxy..."
	@echo "==> Proxying to $${TARGET_URL:-http://localhost:8080}"
	npx --prefix tools @stoplight/prism-cli proxy openapi.yaml $${TARGET_URL:-http://localhost:8080} --errors

# Trigger a release from main
# Creates a [release] marker commit that triggers the release workflow on push
release:
	@# Must be on main
	@BRANCH=$$(git rev-parse --abbrev-ref HEAD); \
	if [ "$$BRANCH" != "main" ]; then \
		echo "Error: must be on 'main' branch (currently on '$$BRANCH')"; \
		exit 1; \
	fi
	@# Working tree must be clean
	@if [ -n "$$(git status --porcelain)" ]; then \
		echo "Error: working tree is not clean. Commit or stash changes first."; \
		exit 1; \
	fi
	@SPEC_VERSION=$$(grep -E '^\s*version:' epistola-api.yaml | head -1 | sed -E 's/.*version:\s*["'"'"']?([0-9]+\.[0-9]+\.[0-9]+)["'"'"']?.*/\1/'); \
	echo "==> Will release version $$SPEC_VERSION (patch auto-incremented by CI)"; \
	echo ""; \
	git commit --allow-empty -m "chore: [release] $$SPEC_VERSION"; \
	echo ""; \
	echo "Release commit created. Push to trigger the release workflow:"; \
	echo "  git push origin main"

# Show help
help:
	@echo "Available targets:"
	@echo "  all            - Run lint + build (default, mirrors CI)"
	@echo "  lint           - Validate OpenAPI spec"
	@echo "  bundle         - Bundle OpenAPI spec into single openapi.yaml"
	@echo "  build                - Build all modules (client, server, editor-model)"
	@echo "  build-client         - Build Kotlin client only"
	@echo "  build-server         - Build Kotlin server only"
	@echo "  build-editor-model - Build template model only"
	@echo "  clean          - Clean all build artifacts"
	@echo "  publish-local  - Publish to local Maven repository"
	@echo "  breaking       - Check for breaking API changes against main branch"
	@echo "  mock           - Start Prism mock server on http://localhost:4010"
	@echo "  validate-impl  - Validate implementation against spec (set TARGET_URL)"
	@echo "  release        - Create a [release] commit to trigger a release from main"
	@echo "  help           - Show this help"
