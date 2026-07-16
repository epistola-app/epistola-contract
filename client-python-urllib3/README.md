# Epistola Python Client

Python client library for the [Epistola](https://github.com/epistola-app/epistola-contract)
document generation API, generated from the Epistola OpenAPI contract with
[OpenAPI Generator](https://openapi-generator.tech) (`python` / urllib3, pydantic v2 models).

It adds, on top of the stock generated client:

- **Client identity** headers (`User-Agent` + `X-EP-Node-Id`) required on every request.
- **RFC 9457 problem-detail** error handling — typed `ProblemDetailException` with a
  `type_slug` discriminator and generated `KnownProblemSlugs` constants.
- **Self-signed JWT** authentication (`JwtSigner`), minting a fresh short-lived token per request.
- **NDJSON result collection** (`ResultCollector`) with adaptive polling, compression, and
  partition-aware routing helpers.
- **Client-side JSON Schema validation** of template data (`TemplateSchemaValidator`).

The package version tracks the Epistola contract version (`info.version`) and releases in
lockstep with the Kotlin and .NET clients.

## Install

```bash
pip install epistola-client
```

Prerelease snapshots (published on every push to `main`) are available from TestPyPI:

```bash
pip install -i https://test.pypi.org/simple/ \
  --extra-index-url https://pypi.org/simple/ epistola-client
```

## Quick start

```python
from epistola_client import (
    EpistolaClientBuilder, ClientIdentity, JwtSigner, TemplatesApi,
)

identity = ClientIdentity.builder().node_id("my-pod-123").build()
signer = (
    JwtSigner.builder()
    .consumer_id("invoice-service")
    .private_key(JwtSigner.load_private_key("private.pem"))
    .build()
)

http = (
    EpistolaClientBuilder()
    .base_url("https://epistola.example.com/api")
    .identity(identity)
    .jwt_signer(signer)
    .install_problem_detail_handler()
    .build()
)

templates = TemplatesApi(http)
template = templates.get_template("acme", "invoices", "invoice")
```

## Error handling

Install the opt-in problem-detail handler on the client
(`.install_problem_detail_handler()`), then switch on `type_slug` against
`KnownProblemSlugs`. The slug list is **open** — the API can introduce new problem types
without a client release — so always keep a fallback branch and fall back to the HTTP
status for unrecognized types.

```python
from epistola_client import (
    TenantsApi, ProblemDetailException, KnownProblemSlugs,
)

try:
    tenants_api.get_tenant("acme")
except ProblemDetailException as e:
    match e.type_slug:
        case KnownProblemSlugs.NOT_FOUND:
            log.warning("tenant not found: %s", e.detail)
        case KnownProblemSlugs.FORBIDDEN:
            raise PermissionError(e.detail)
        case KnownProblemSlugs.VALIDATION_ERROR:
            for err in e.errors:               # field-level ValidationProblemDetail
                log.warning("%s: %s", err.var_field, err.message)
        case KnownProblemSlugs.DATA_MODEL_VALIDATION_ERROR:
            for example, failures in e.validation_errors.items():  # 422 data-model failures
                for f in failures:
                    log.warning("%s %s: %s", example, f.path, f.message)
        case _:
            raise                              # unknown / framework error — fall back to status
```

`ProblemDetailException` extends the generated `ApiException`, so existing
`except ApiException` sites keep working. It exposes `type`, `type_slug`, `title`,
`problem_status`, `detail`, `errors`, `validation_errors`, `is_validation_problem`, and
`is_data_model_validation_problem`.

## Result collection

```python
from epistola_client import ResultCollector

collector = (
    ResultCollector.builder()
    .api_client(http)
    .tenant_id("acme")
    .handler(lambda result: print(result.request_id, result.status))
    .build()
)
collector.start()   # blocks; adaptive polling until collector.stop()
```

## Development

The client is generated from the bundled spec. From the repository root:

```bash
make bundle                       # produce openapi.yaml
cd client-python-urllib3
./generate.sh                     # stock client + derived sources
uv run pytest                     # run the tests
```

`generated/` (stock client) and `src/epistola_client/_generated/` (derived sources) are
gitignored and rebuilt from the spec each time.
