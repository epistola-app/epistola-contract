.PHONY: all lint license license-check conformance conformance-kotlin conformance-jakarta conformance-dotnet conformance-python conformance-node bundle build-protocol build-client build-jakarta build-server build-catalog build-epistola-catalog build-dotnet build-python build-node build clean publish-local sbom-dotnet breaking mock validate-impl release docs help

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
build: build-protocol build-client build-jakarta build-server build-catalog build-dotnet build-python build-node

# Build the shared wire-protocol logic (consumed by both JVM clients and the server stubs)
build-protocol:
	@echo "==> Building shared protocol logic..."
	cd $(API_DIR)/protocol-java && ./gradlew build

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

# Build Node.js client (generates from the bundled spec, then compiles and tests)
build-node: bundle
	@echo "==> Building Node.js client..."
	cd $(API_DIR)/clients/nodejs-fetch && pnpm install --frozen-lockfile && ./generate.sh && pnpm build && pnpm test

# Cross-client conformance: every client against one scripted server, one set of expectations
CONFORMANCE_DIR := $(API_DIR)/conformance

$(CONFORMANCE_DIR)/node_modules: $(CONFORMANCE_DIR)/package.json $(CONFORMANCE_DIR)/pnpm-lock.yaml
	@echo "==> Installing conformance harness..."
	pnpm -C $(CONFORMANCE_DIR) install --frozen-lockfile

conformance: conformance-kotlin conformance-jakarta conformance-dotnet conformance-python conformance-node

conformance-kotlin: bundle $(CONFORMANCE_DIR)/node_modules
	node $(CONFORMANCE_DIR)/src/run.mjs --client kotlin

conformance-jakarta: bundle $(CONFORMANCE_DIR)/node_modules
	node $(CONFORMANCE_DIR)/src/run.mjs --client jakarta

conformance-dotnet: bundle $(CONFORMANCE_DIR)/node_modules
	node $(CONFORMANCE_DIR)/src/run.mjs --client dotnet

conformance-python: bundle $(CONFORMANCE_DIR)/node_modules
	node $(CONFORMANCE_DIR)/src/run.mjs --client python

conformance-node: bundle $(CONFORMANCE_DIR)/node_modules
	node $(CONFORMANCE_DIR)/src/run.mjs --client node

# Generate a CycloneDX SBOM for the .NET client's dependency closure
sbom-dotnet:
	@echo "==> Generating .NET client SBOM (CycloneDX)..."
	@dotnet tool list --global | grep -qi cyclonedx || dotnet tool install --global CycloneDX
	cd $(API_DIR)/clients/dotnet-httpclient && dotnet CycloneDX src/Epistola.Client/Epistola.Client.csproj --output sbom --json --filename epistola-dotnet-client-sbom.json
	@echo "==> Wrote $(API_DIR)/clients/dotnet-httpclient/sbom/epistola-dotnet-client-sbom.json"

# Clean all build artifacts
clean:
	@echo "==> Cleaning..."
	cd $(API_DIR)/protocol-java && ./gradlew clean
	cd $(API_DIR)/clients/kotlin-spring-restclient && ./gradlew clean
	cd $(API_DIR)/clients/jakarta && ./gradlew clean
	cd $(API_DIR)/server-stubs/kotlin-springboot4 && ./gradlew clean
	cd contracts/catalog && ./gradlew clean
	cd $(API_DIR)/clients/dotnet-httpclient && rm -rf Generated src/Epistola.Client/Generated bin obj src/*/bin src/*/obj test/*/bin test/*/obj
	cd $(API_DIR)/clients/python-urllib3 && rm -rf generated src/epistola_client/_generated dist build .venv .pytest_cache && find . -name __pycache__ -type d -prune -exec rm -rf {} +
	cd $(API_DIR)/clients/nodejs-fetch && rm -rf src/generated dist build

# Publish to local Maven repository (for testing)
#
# Every artifact is stamped with one version, as the release workflow does: the spec's
# info.version unless VERSION is given. Left to their defaults the builds disagree — the JVM
# builds would use patch 0, the catalog 0.1.0-SNAPSHOT, .NET 0.0.0-dev, and Node.js whatever
# package.json holds — and the server stubs' POM would name a catalog version nobody published.
# Python reads the full spec version itself, so it follows the spec even when VERSION is set.
publish-local: VERSION ?= $(shell $(API_DIR)/scripts/spec-version.sh)
publish-local: build
	@echo "==> Publishing $(VERSION) to local Maven repository..."
	cd $(API_DIR)/clients/kotlin-spring-restclient && ./gradlew publishToMavenLocal -Pversion=$(VERSION)
	cd $(API_DIR)/clients/jakarta && ./gradlew publishToMavenLocal -Pversion=$(VERSION)
	cd $(API_DIR)/server-stubs/kotlin-springboot4 && ./gradlew publishToMavenLocal -Pversion=$(VERSION)
	cd contracts/catalog && ./gradlew publishToMavenLocal -Pversion=$(VERSION)
	@echo "==> Published to ~/.m2/repository/app/epistola/contract/"
	@echo "==> Packing .NET client..."
	cd $(API_DIR)/clients/dotnet-httpclient && dotnet pack src/Epistola.Client/Epistola.Client.csproj -c Release -p:Version=$(VERSION) -o nupkgs
	@echo "==> Building Python client..."
	cd $(API_DIR)/clients/python-urllib3 && ./generate.sh && uv build
	@echo "==> Packing Node.js client..."
	@# npm version rewrites package.json; restore it so a local pack leaves the tree clean.
	cd $(API_DIR)/clients/nodejs-fetch && pnpm install --frozen-lockfile && ./generate.sh && \
		cp package.json package.json.publish-local && \
		{ npm version $(VERSION) --no-git-tag-version --allow-same-version && pnpm pack; rc=$$?; \
		  mv package.json.publish-local package.json; exit $$rc; }

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

# Start a release: publish a GitHub Release, which the release workflow builds and publishes.
# Nothing releases on a push; a release is only ever started here (or by hand, the same way).
#   make release                     the next patch of the spec's major.minor, from main or release/X.Y;
#                                    commits the spec version and pushes it first
#   make release PRERELEASE=alpha.1  v<spec version>-alpha.1, from main, release/* or feature/*;
#                                    marked pre-release, so no registry or the docs treat it as the latest
release:
	@BRANCH=$$(git rev-parse --abbrev-ref HEAD); \
	API_VERSION=$$($(API_DIR)/scripts/spec-version.sh --api); \
	SPEC_VERSION=$$($(API_DIR)/scripts/spec-version.sh --full); \
	if [ -n "$(PRERELEASE)" ]; then \
		case "$$BRANCH" in \
			main|release/*|feature/*) ;; \
			*) echo "Error: a pre-release comes from main, release/* or feature/* (currently on '$$BRANCH')"; exit 1 ;; \
		esac; \
	elif [ "$$BRANCH" != "main" ] && [ "$$BRANCH" != "release/$$API_VERSION" ]; then \
		echo "Error: a release comes from main or release/$$API_VERSION (currently on '$$BRANCH')"; \
		exit 1; \
	fi; \
	if [ -n "$$(git status --porcelain)" ]; then \
		echo "Error: working tree is not clean. Commit or stash changes first."; \
		exit 1; \
	fi; \
	git fetch origin "$$BRANCH" --tags --quiet; \
	if [ "$$(git rev-parse HEAD)" != "$$(git rev-parse "origin/$$BRANCH")" ]; then \
		echo "Error: local $$BRANCH is not the same as origin/$$BRANCH. Pull or push first."; \
		exit 1; \
	fi; \
	if [ -n "$(PRERELEASE)" ]; then \
		if git rev-parse -q --verify "refs/tags/v$$SPEC_VERSION" >/dev/null; then \
			echo "Error: v$$SPEC_VERSION is already released; raise info.version before a pre-release of the next one."; \
			exit 1; \
		fi; \
		VERSION="$$SPEC_VERSION-$(PRERELEASE)"; \
		if git rev-parse -q --verify "refs/tags/v$$VERSION" >/dev/null; then \
			echo "Error: v$$VERSION already exists."; \
			exit 1; \
		fi; \
		echo "==> Creating pre-release v$$VERSION from $$BRANCH ($$(git rev-parse --short HEAD))"; \
		gh release create "v$$VERSION" --target "$$(git rev-parse HEAD)" --title "v$$VERSION" --generate-notes --prerelease || exit 1; \
	else \
		LATEST_PATCH=-1; \
		for tag in $$(git tag -l "v$${API_VERSION}.*" "*-v$${API_VERSION}.*"); do \
			PATCH=$$(echo "$$tag" | sed -E 's/.*v[0-9]+\.[0-9]+\.([0-9]+)$$/\1/'); \
			if [ "$$PATCH" -gt "$$LATEST_PATCH" ] 2>/dev/null; then \
				LATEST_PATCH=$$PATCH; \
			fi; \
		done; \
		VERSION="$${API_VERSION}.$$((LATEST_PATCH + 1))"; \
		if [ "$$VERSION" != "$$SPEC_VERSION" ]; then \
			echo "==> Updating spec version to $$VERSION"; \
			perl -pi -e 's/^(\s*version:\s*["\x27]?)\d+\.\d+\.\d+(["\x27]?)/$${1}'"$$VERSION"'$$2/ if !$$done && /^\s*version:/ && ($$done = 1)' $(API_SPEC); \
			git add $(API_SPEC); \
			git commit -m "release: bump spec version to $$VERSION" || exit 1; \
			git push origin "$$BRANCH" || exit 1; \
		fi; \
		echo "==> Creating release v$$VERSION from $$BRANCH"; \
		gh release create "v$$VERSION" --target "$$(git rev-parse HEAD)" --title "v$$VERSION" --generate-notes || exit 1; \
	fi; \
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
	@echo "  build-protocol       - Build the shared protocol logic only"
	@echo "  build-client         - Build Kotlin client only"
	@echo "  build-jakarta        - Build Jakarta EE client only"
	@echo "  build-server         - Build Kotlin server only"
	@echo "  build-catalog        - Build portable catalog only"
	@echo "  build-dotnet         - Build .NET client only"
	@echo "  build-python         - Build Python client only"
	@echo "  build-node           - Build Node.js client only"
	@echo "  conformance          - Run the cross-client conformance suite (all five clients)"
	@echo "  conformance-<client> - Run it for one client (kotlin|jakarta|dotnet|python|node)"
	@echo "  sbom-dotnet          - Generate a CycloneDX SBOM for the .NET client"
	@echo "  clean          - Clean all build artifacts"
	@echo "  publish-local  - Publish every artifact locally at the spec version (override: VERSION=x.y.z)"
	@echo "  breaking       - Check for breaking API changes against main branch"
	@echo "  docs           - Build API docs and serve at http://localhost:8888"
	@echo "  mock           - Start Prism mock server on http://localhost:4010"
	@echo "  validate-impl  - Validate implementation against spec (set TARGET_URL)"
	@echo "  release        - Start a release from main or release/X.Y (PRERELEASE=alpha.1: a pre-release, also from feature/*)"
	@echo "  help           - Show this help"
