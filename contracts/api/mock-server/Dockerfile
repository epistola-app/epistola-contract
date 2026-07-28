# Epistola API Mock Server
# Multi-architecture build using Node.js + Prism CLI
FROM node:24-alpine

# Install Prism CLI globally (keep the pin in sync with contracts/api/tools/package.json)
RUN npm install -g @stoplight/prism-cli@5.15.11

# Copy the bundled OpenAPI spec
COPY openapi.yaml /app/openapi.yaml

# Expose the mock server port
EXPOSE 4010

WORKDIR /app

# Run Prism mock server with static responses from schema examples
ENTRYPOINT ["prism", "mock", "-h", "0.0.0.0", "-p", "4010", "/app/openapi.yaml"]
