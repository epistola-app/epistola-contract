# Epistola API Mock Server
# Based on Stoplight Prism for OpenAPI mocking
FROM stoplight/prism:5

# Copy the bundled OpenAPI spec
COPY openapi.yaml /app/openapi.yaml

# Expose the mock server port
EXPOSE 4010

# Run Prism mock server with dynamic response generation
ENTRYPOINT ["prism", "mock", "-h", "0.0.0.0", "-p", "4010", "-d", "/app/openapi.yaml"]
