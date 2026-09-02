.PHONY: all lint license license-check bundle build-client build-jakarta build-server build-catalog build-epistola-catalog build-dotnet build-python build clean publish-local sbom-dotnet breaking mock validate-impl release docs help

API_DIR := contracts/api
API_SPEC := $(API_DIR)/openapi.yaml
API_BUNDLE := $(API_DIR)/build/openapi.yaml
API_TOOLS := $(API_DIR)/tools
REDOCLY := $(API_TOOLS)/node_modules/.bin/redocly
PRISM := $(API_TOOLS)/node_modules/.bin/prism

$(REDOCLY): $(API_TOOLS)/package.json $(API_TOOLS)/pnpm-lock.yaml
	@echo "==> Installing pinned tools..."
	pnpm -C $(API_TOOLS) install --frozen-lockfile

# Default target - runs what CI runs
all: lint build

# Add SPDX headers to first-party source files.
license:
	@echo "==> Adding missing SPDX license headers..."
	@python3 scripts/license-headers.py --fix

# Verify source headers and REUSE metadata.
license-check:
	@echo "==> Checking SPDX license metadata..."
	@python3 scripts/license-headers.py --check
	@mise exec -- reuse --no-multiprocessing lint

# Validate OpenAPI spec
lint: $(REDOCLY)
	@echo "==> Validating OpenAPI spec..."
	$(REDOCLY) lint $(API_SPEC) --config $(API_DIR)/redocly.yaml
	@$(API_DIR)/scripts/check-error-registry.sh
	@$(API_DIR)/scripts/check-media-types.sh
	@node contracts/catalog/scripts/check-registry.mjs

# Bundle OpenAPI spec into single file
bundle: $(REDOCLY)
	@echo "==> Bundling OpenAPI spec..."
	@mkdir -p $(dir $(API_BUNDLE))
	$(REDOCLY) bundle $(API_SPEC) -o $(API_BUNDLE)
	@node $(API_DIR)/scripts/normalize-bundle.mjs $(API_BUNDLE)
	@echo "==> Created $(API_BUNDLE)"

# Build all modules
build: build-client build-jakarta build-server build-catalog build-dotnet build-python

# Build Kotlin client
build-client:
	@echo "==> Building Kotlin client..."
	cd $(API_DIR)/clients/kotlin-spring-restclient && ./gradlew build

# Build Jakarta EE client (generates from the bundled spec, then builds and tests)
build-jakarta:
	@echo "==> Building Jakarta EE client..."
	cd $(API_DIR)/clients/jakarta && ./gradlew build

# Build Kotlin server
build-server:
	@echo "==> Building Kotlin server..."
	cd $(API_DIR)/server-stubs/kotlin-springboot4 && ./gradlew build

# Build portable catalog
build-catalog:
	@echo "==> Building portable catalog..."
	cd contracts/catalog && ./gradlew build sourcesJar dokkaJavadocJar
	cd contracts/catalog && pnpm install --frozen-lockfile
	cd contracts/catalog && pnpm generate:types
	cd contracts/catalog && pnpm build
	cd contracts/catalog && npm pack --dry-run

# Backwards-compatible target name.
build-epistola-catalog: build-catalog

# Build .NET client (generates from the bundled spec, then builds and tests)
build-dotnet: bundle
	@echo "==> Building .NET client..."
	cd $(API_DIR)/clients/dotnet-httpclient && ./generate.sh && dotnet test Epistola.Client.sln -c Release

# Build Python client (generates from the bundled spec, then builds and tests)
build-python: bundle
	@echo "==> Building Python client..."
	cd $(API_DIR)/clients/python-urllib3 && ./generate.sh && uv run --group dev pytest

# Generate a CycloneDX SBOM for the .NET client's dependency closure
sbom-dotnet:
	@echo "==> Generating .NET client SBOM (CycloneDX)..."
	@dotnet tool list --global | grep -qi cyclonedx || dotnet tool install --global CycloneDX
	cd $(API_DIR)/clients/dotnet-httpclient && dotnet CycloneDX src/Epistola.Client/Epistola.Client.csproj --output sbom --json --filename epistola-dotnet-client-sbom.json
	@echo "==> Wrote $(API_DIR)/clients/dotnet-httpclient/sbom/epistola-dotnet-client-sbom.json"

# Clean all build artifacts
clean:
	@echo "==> Cleaning..."
	cd $(API_DIR)/clients/kotlin-spring-restclient && ./gradlew clean
	cd $(API_DIR)/clients/jakarta && ./gradlew clean
	cd $(API_DIR)/server-stubs/kotlin-springboot4 && ./gradlew clean
	cd contracts/catalog && ./gradlew clean
	cd $(API_DIR)/clients/dotnet-httpclient && rm -rf Generated src/Epistola.Client/Generated bin obj src/*/bin src/*/obj test/*/bin test/*/obj
	cd $(API_DIR)/clients/python-urllib3 && rm -rf generated src/epistola_client/_generated dist build .venv .pytest_cache && find . -name __pycache__ -type d -prune -exec rm -rf {} +

# Publish to local Maven repository (for testing)
publish-local: build
	@echo "==> Publishing to local Maven repository..."
	cd $(API_DIR)/clients/kotlin-spring-restclient && ./gradlew publishToMavenLocal
	cd $(API_DIR)/clients/jakarta && ./gradlew publishToMavenLocal
	cd $(API_DIR)/server-stubs/kotlin-springboot4 && ./gradlew publishToMavenLocal
	cd contracts/catalog && ./gradlew publishToMavenLocal
	@echo "==> Published to ~/.m2/repository/app/epistola/contract/"
	@echo "==> Packing .NET client..."
	cd $(API_DIR)/clients/dotnet-httpclient && dotnet pack src/Epistola.Client/Epistola.Client.csproj -c Release -o nupkgs
	@echo "==> Building Python client..."
	cd $(API_DIR)/clients/python-urllib3 && ./generate.sh && uv build

# Check for breaking changes against main branch
breaking: bundle
	@echo "==> Checking for breaking changes against main branch..."
	@rm -rf /tmp/epistola-base-spec && mkdir -p /tmp/epistola-base-spec
	@if git cat-file -e main:contracts/api/openapi.yaml 2>/dev/null; then \
		git archive main -- contracts/api/openapi.yaml contracts/api/paths contracts/api/components contracts/catalog/schemas | tar -xC /tmp/epistola-base-spec; \
		BASE_SPEC=contracts/api/openapi.yaml; \
	else \
		git archive main -- epistola-api.yaml spec | tar -xC /tmp/epistola-base-spec; \
		BASE_SPEC=epistola-api.yaml; \
	fi; \
	cd /tmp/epistola-base-spec && $(CURDIR)/$(REDOCLY) bundle $$BASE_SPEC -o openapi.yaml
	oasdiff breaking --flatten-allof --flatten-params /tmp/epistola-base-spec/openapi.yaml $(API_BUNDLE)

# Generate API docs and open in browser
docs: bundle
	@echo "==> Building API documentation..."
	$(REDOCLY) build-docs $(API_BUNDLE) -o /tmp/epistola-api-docs.html
	@echo "==> Opening http://localhost:8888/epistola-api-docs.html"
	@python3 -m http.server 8888 --directory /tmp --bind 0.0.0.0 &>/dev/null &
	@echo "==> Server running on port 8888. Use Ctrl+C to stop."

# Start mock server on port 4010
mock: bundle
	@echo "==> Starting Prism mock server on http://localhost:4010..."
	@echo "==> Use Ctrl+C to stop"
	$(PRISM) mock $(API_BUNDLE) -p 4010

# Validate implementation against spec (requires running server)
validate-impl: bundle
	@echo "==> Starting contract validation proxy..."
	@echo "==> Proxying to $${TARGET_URL:-http://localhost:8080}"
	$(PRISM) proxy $(API_BUNDLE) $${TARGET_URL:-http://localhost:8080} --errors

# Create a GitHub Release to trigger the release workflow
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
	@# Must be up to date with remote
	@git fetch origin main --quiet; \
	if [ "$$(git rev-parse HEAD)" != "$$(git rev-parse origin/main)" ]; then \
		echo "Error: local main is not up to date with origin/main. Pull first."; \
		exit 1; \
	fi
	@API_VERSION=$$($(API_DIR)/scripts/spec-version.sh --api); \
	LATEST_PATCH=-1; \
	for tag in $$(git tag -l "v$${API_VERSION}.*" 2>/dev/null) $$(git tag -l "*-v$${API_VERSION}.*" 2>/dev/null); do \
		PATCH=$$(echo "$$tag" | sed -E 's/.*v[0-9]+\.[0-9]+\.([0-9]+)/\1/'); \
		if [ "$$PATCH" -gt "$$LATEST_PATCH" ] 2>/dev/null; then \
			LATEST_PATCH=$$PATCH; \
		fi; \
	done; \
	NEXT_PATCH=$$((LATEST_PATCH + 1)); \
	VERSION="$${API_VERSION}.$${NEXT_PATCH}"; \
	echo "==> Updating spec version to $$VERSION"; \
	sed -i -E "s/(^\s*version:\s*[\"']?)[0-9]+\.[0-9]+\.[0-9]+([\"']?)/\1$$VERSION\2/" $(API_SPEC); \
	git add $(API_SPEC); \
	git commit -m "release: bump spec version to $$VERSION"; \
	git push origin main; \
	echo "==> Creating release v$$VERSION"; \
	gh release create "v$$VERSION" --title "v$$VERSION" --generate-notes; \
	echo ""; \
	echo "Release v$$VERSION created. The release workflow will now build and publish all artifacts."

# Show help
help:
	@echo "Available targets:"
	@echo "  all            - Run lint + build (default, mirrors CI)"
	@echo "  lint           - Validate OpenAPI spec"
	@echo "  license        - Add missing SPDX license headers"
	@echo "  license-check  - Verify SPDX headers and REUSE metadata"
	@echo "  bundle         - Bundle OpenAPI spec into single openapi.yaml"
	@echo "  build                - Build all modules (clients, server, catalog)"
	@echo "  build-client         - Build Kotlin client only"
	@echo "  build-jakarta        - Build Jakarta EE client only"
	@echo "  build-server         - Build Kotlin server only"
	@echo "  build-catalog        - Build portable catalog only"
	@echo "  build-dotnet         - Build .NET client only"
	@echo "  build-python         - Build Python client only"
	@echo "  sbom-dotnet          - Generate a CycloneDX SBOM for the .NET client"
	@echo "  clean          - Clean all build artifacts"
	@echo "  publish-local  - Publish to local Maven repository"
	@echo "  breaking       - Check for breaking API changes against main branch"
	@echo "  docs           - Build API docs and serve at http://localhost:8888"
	@echo "  mock           - Start Prism mock server on http://localhost:4010"
	@echo "  validate-impl  - Validate implementation against spec (set TARGET_URL)"
	@echo "  release        - Create a GitHub Release to trigger the release workflow"
	@echo "  help           - Show this help"
