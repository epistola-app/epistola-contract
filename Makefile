.PHONY: all lint bundle build-client build-server build clean publish-local breaking mock validate-impl cut-release help

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

# Build both modules
build: build-client build-server

# Build Kotlin client
build-client:
	@echo "==> Building Kotlin client..."
	cd client-kotlin-spring-restclient && ./gradlew build

# Build Kotlin server
build-server:
	@echo "==> Building Kotlin server..."
	cd server-kotlin-springboot4 && ./gradlew build

# Clean all build artifacts
clean:
	@echo "==> Cleaning..."
	cd client-kotlin-spring-restclient && ./gradlew clean
	cd server-kotlin-springboot4 && ./gradlew clean

# Publish to local Maven repository (for testing)
publish-local: build
	@echo "==> Publishing to local Maven repository..."
	cd client-kotlin-spring-restclient && ./gradlew publishToMavenLocal
	cd server-kotlin-springboot4 && ./gradlew publishToMavenLocal
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

# Cut a release branch from main
# Usage: make cut-release VERSION=0.2
cut-release:
ifndef VERSION
	$(error VERSION is required. Usage: make cut-release VERSION=0.2)
endif
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
	@# Spec version must match requested release
	@SPEC_VERSION=$$(grep -E '^\s*version:' epistola-api.yaml | head -1 | sed -E 's/.*version:\s*"?([0-9]+\.[0-9]+)\.[0-9]+"?.*/\1/'); \
	if [ "$$SPEC_VERSION" != "$(VERSION)" ]; then \
		echo "Error: epistola-api.yaml version '$$SPEC_VERSION' does not match requested VERSION=$(VERSION)"; \
		echo "Update the spec version to $(VERSION).0 before cutting a release."; \
		exit 1; \
	fi
	@git branch release/$(VERSION)
	@echo "==> Created branch release/$(VERSION)"
	@echo ""
	@echo "Next steps:"
	@echo "  1. Push the branch:  git push origin release/$(VERSION)"
	@echo "  2. A version-bump PR will be created automatically on main"
	@echo "  3. Review and merge the version-bump PR"
	@echo "  4. Pushes to release/$(VERSION) trigger releases to Maven Central"

# Show help
help:
	@echo "Available targets:"
	@echo "  all            - Run lint + build (default, mirrors CI)"
	@echo "  lint           - Validate OpenAPI spec"
	@echo "  bundle         - Bundle OpenAPI spec into single openapi.yaml"
	@echo "  build          - Build client and server"
	@echo "  build-client   - Build Kotlin client only"
	@echo "  build-server   - Build Kotlin server only"
	@echo "  clean          - Clean all build artifacts"
	@echo "  publish-local  - Publish to local Maven repository"
	@echo "  breaking       - Check for breaking API changes against main branch"
	@echo "  mock           - Start Prism mock server on http://localhost:4010"
	@echo "  validate-impl  - Validate implementation against spec (set TARGET_URL)"
	@echo "  cut-release    - Cut a release branch (VERSION=X.Y required)"
	@echo "  help           - Show this help"
