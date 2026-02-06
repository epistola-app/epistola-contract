.PHONY: all lint bundle build-client build-server build clean publish-local help

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
	cd epistola-server-kotlin && ./gradlew build

# Clean all build artifacts
clean:
	@echo "==> Cleaning..."
	cd client-kotlin-spring-restclient && ./gradlew clean
	cd epistola-server-kotlin && ./gradlew clean

# Publish to local Maven repository (for testing)
publish-local: build
	@echo "==> Publishing to local Maven repository..."
	cd client-kotlin-spring-restclient && ./gradlew publishToMavenLocal
	cd epistola-server-kotlin && ./gradlew publishToMavenLocal
	@echo "==> Published to ~/.m2/repository/app/epistola/contract/"

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
	@echo "  help           - Show this help"
