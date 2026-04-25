.PHONY: all lint bundle build-client build-server build-epistola-model build clean publish-local breaking mock validate-impl release docs help

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
build: build-client build-server build-epistola-model

# Build Kotlin client
build-client:
	@echo "==> Building Kotlin client..."
	cd client-kotlin-spring-restclient && ./gradlew build

# Build Kotlin server
build-server:
	@echo "==> Building Kotlin server..."
	cd server-kotlin-springboot4 && ./gradlew build

# Build template model
build-epistola-model:
	@echo "==> Building template model..."
	cd epistola-model && ./gradlew build

# Clean all build artifacts
clean:
	@echo "==> Cleaning..."
	cd client-kotlin-spring-restclient && ./gradlew clean
	cd server-kotlin-springboot4 && ./gradlew clean
	cd epistola-model && ./gradlew clean

# Publish to local Maven repository (for testing)
publish-local: build
	@echo "==> Publishing to local Maven repository..."
	cd client-kotlin-spring-restclient && ./gradlew publishToMavenLocal
	cd server-kotlin-springboot4 && ./gradlew publishToMavenLocal
	cd epistola-model && ./gradlew publishToMavenLocal
	@echo "==> Published to ~/.m2/repository/app/epistola/contract/"

# Check for breaking changes against main branch
breaking: bundle
	@echo "==> Checking for breaking changes against main branch..."
	@rm -rf /tmp/epistola-base-spec && mkdir -p /tmp/epistola-base-spec
	@git archive main -- epistola-api.yaml spec/ 2>/dev/null | tar -xC /tmp/epistola-base-spec || cp -r epistola-api.yaml spec/ /tmp/epistola-base-spec/
	@cd /tmp/epistola-base-spec && npx @redocly/cli bundle epistola-api.yaml -o openapi.yaml 2>/dev/null
	oasdiff breaking /tmp/epistola-base-spec/openapi.yaml openapi.yaml

# Generate API docs and open in browser
docs: bundle
	@echo "==> Building API documentation..."
	npx @redocly/cli build-docs openapi.yaml -o /tmp/epistola-api-docs.html
	@echo "==> Opening http://localhost:8888/epistola-api-docs.html"
	@python3 -m http.server 8888 --directory /tmp --bind 0.0.0.0 &>/dev/null &
	@echo "==> Server running on port 8888. Use Ctrl+C to stop."

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
	@SPEC_VERSION=$$(grep -E '^\s*version:' epistola-api.yaml | head -1 | sed -E 's/.*version:\s*["'"'"']?([0-9]+\.[0-9]+\.[0-9]+)["'"'"']?.*/\1/'); \
	API_VERSION=$$(echo "$$SPEC_VERSION" | sed -E 's/([0-9]+\.[0-9]+)\.[0-9]+/\1/'); \
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
	sed -i -E "s/(^\s*version:\s*[\"']?)[0-9]+\.[0-9]+\.[0-9]+([\"']?)/\1$$VERSION\2/" epistola-api.yaml; \
	git add epistola-api.yaml; \
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
	@echo "  bundle         - Bundle OpenAPI spec into single openapi.yaml"
	@echo "  build                - Build all modules (client, server, epistola-model)"
	@echo "  build-client         - Build Kotlin client only"
	@echo "  build-server         - Build Kotlin server only"
	@echo "  build-epistola-model - Build template model only"
	@echo "  clean          - Clean all build artifacts"
	@echo "  publish-local  - Publish to local Maven repository"
	@echo "  breaking       - Check for breaking API changes against main branch"
	@echo "  docs           - Build API docs and serve at http://localhost:8888"
	@echo "  mock           - Start Prism mock server on http://localhost:4010"
	@echo "  validate-impl  - Validate implementation against spec (set TARGET_URL)"
	@echo "  release        - Create a GitHub Release to trigger the release workflow"
	@echo "  help           - Show this help"
