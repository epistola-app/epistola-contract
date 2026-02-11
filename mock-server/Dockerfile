# Epistola API Mock Server
# Multi-architecture build using Node.js + Prism CLI
FROM node:22-alpine

# Install Prism CLI globally
RUN npm install -g @stoplight/prism-cli@5

# Copy the bundled OpenAPI spec
COPY openapi.yaml /app/openapi.yaml

# Expose the mock server port
EXPOSE 4010

WORKDIR /app

# Run Prism mock server with static responses from schema examples
ENTRYPOINT ["prism", "mock", "-h", "0.0.0.0", "-p", "4010", "/app/openapi.yaml"]
