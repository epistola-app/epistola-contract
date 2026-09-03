# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""Assembles an :class:`ApiClient` with the Epistola behaviours and hands it to the
generated API classes (each generated ``*Api`` accepts an ``ApiClient``).

Mirrors the Kotlin client's ``RestClient.Builder`` wiring and the .NET
``EpistolaHttpClientBuilder``::

    http = (
        EpistolaClientBuilder()
        .base_url("https://epistola.example.com/api")
        .identity(identity)                  # User-Agent + X-EP-Node-Id
        .jwt_signer(signer)                  # Authorization: Bearer
        .api_key("epk_...")                  # Authorization: ApiKey
        .install_problem_detail_handler()    # typed ProblemDetailException on problem+json
        .build()
    )

    templates = TemplatesApi(http)

Unlike the .NET client, no media-type handler is needed: the Python generator already
emits the versioned ``application/vnd.epistola.v1+json`` content type on request bodies.
"""

from __future__ import annotations

from typing import Optional

from epistola_client_generated import ApiClient, Configuration

from epistola_client.auth.jwt_signer import JwtSigner
from epistola_client.error.problem_detail_handler import PROBLEM_JSON, parse_problem
from epistola_client.identity.client_identity import ClientIdentity


class EpistolaApiClient(ApiClient):
    """An :class:`ApiClient` that adds a fresh self-signed JWT and identity headers to
    every request, and (opt-in) raises a typed ``ProblemDetailException`` for
    ``application/problem+json`` error responses.
    """

    def __init__(
        self,
        configuration: Optional[Configuration] = None,
        identity: Optional[ClientIdentity] = None,
        jwt_signer: Optional[JwtSigner] = None,
        api_key: Optional[str] = None,
        install_problem_detail_handler: bool = False,
    ) -> None:
        super().__init__(configuration=configuration)
        self._jwt_signer = jwt_signer
        self._api_key = _normalize_api_key(api_key)
        self._install_problem_detail_handler = install_problem_detail_handler
        if identity is not None:
            for name, value in identity.headers().items():
                self.set_default_header(name, value)

    def epistola_request_headers(self) -> dict:
        """The per-request Epistola headers (identity defaults + a fresh JWT bearer, if
        configured). Used by :class:`~epistola_client.collect.result_collector.ResultCollector`,
        which drives the raw NDJSON collect endpoint outside the generated API methods.
        """
        headers = dict(self.default_headers)
        if self._jwt_signer is not None:
            headers["Authorization"] = f"Bearer {self._jwt_signer.create_token()}"
        elif self._api_key is not None:
            headers["Authorization"] = f"ApiKey {self._api_key}"
        return headers

    def select_header_accept(self, accepts):
        """Accept every JSON media type the operation declares, not just the first one.

        The stock generated implementation returns the first entry matching ``json``, which drops
        ``application/problem+json`` from every operation that also returns a success body — so the
        client asks for a document it cannot be sent. Against a server doing strict content
        negotiation that turns an error response into a 406, and the typed
        :class:`~epistola_client.error.problem_detail_exception.ProblemDetailException` this client
        exists to raise never gets its body. The other three Epistola clients send both types.
        """
        json_types = [accept for accept in accepts if "json" in accept.lower()]
        if json_types:
            return ", ".join(json_types)
        return accepts[0] if accepts else None

    def param_serialize(self, *args, **kwargs):
        method, url, header_params, body, post_params = super().param_serialize(*args, **kwargs)
        if self._jwt_signer is not None:
            # Mint a fresh short-lived token per request (mirrors the handler chain in
            # the Kotlin/.NET clients), overriding any auth the generated client set.
            header_params["Authorization"] = f"Bearer {self._jwt_signer.create_token()}"
        elif self._api_key is not None:
            header_params["Authorization"] = f"ApiKey {self._api_key}"
        return method, url, header_params, body, post_params

    def response_deserialize(self, response_data, response_types_map=None):
        if self._install_problem_detail_handler and not (200 <= response_data.status <= 299):
            content_type = ""
            if response_data.headers is not None:
                content_type = response_data.headers.get("content-type", "") or ""
            if content_type.split(";")[0].strip().lower() == PROBLEM_JSON:
                body = ""
                if response_data.data:
                    body = response_data.data.decode("utf-8", errors="replace")
                if body.strip():
                    problem = parse_problem(body, response_data.status, response_data.headers)
                    if problem is not None:
                        raise problem
        return super().response_deserialize(response_data, response_types_map)


class EpistolaClientBuilder:
    """Fluent builder producing a configured :class:`EpistolaApiClient`."""

    def __init__(self) -> None:
        self._base_url: Optional[str] = None
        self._identity: Optional[ClientIdentity] = None
        self._jwt_signer: Optional[JwtSigner] = None
        self._api_key: Optional[str] = None
        self._install_problem_detail_handler = False
        self._configuration: Optional[Configuration] = None

    def base_url(self, base_url: str) -> "EpistolaClientBuilder":
        """Set the API base URL (e.g. ``https://epistola.example.com/api``)."""
        self._base_url = base_url
        return self

    def identity(self, identity: ClientIdentity) -> "EpistolaClientBuilder":
        """Add the identity headers (``User-Agent`` + ``X-EP-Node-Id``)."""
        self._identity = identity
        return self

    def jwt_signer(self, signer: JwtSigner) -> "EpistolaClientBuilder":
        """Add the self-signed JWT bearer auth."""
        self._jwt_signer = signer
        return self

    def api_key(self, api_key: str) -> "EpistolaClientBuilder":
        """Add static API-key auth via ``Authorization: ApiKey <key>``."""
        self._api_key = _normalize_api_key(api_key)
        return self

    def install_problem_detail_handler(self) -> "EpistolaClientBuilder":
        """Install the opt-in problem-detail handler (typed ``ProblemDetailException``)."""
        self._install_problem_detail_handler = True
        return self

    def configuration(self, configuration: Configuration) -> "EpistolaClientBuilder":
        """Override the base :class:`Configuration` (advanced: timeouts, TLS, proxies)."""
        self._configuration = configuration
        return self

    def build(self) -> EpistolaApiClient:
        """Build the configured :class:`EpistolaApiClient`."""
        configuration = self._configuration or Configuration()
        if self._base_url:
            configuration.host = self._base_url
        return EpistolaApiClient(
            configuration=configuration,
            identity=self._identity,
            jwt_signer=self._jwt_signer,
            api_key=self._api_key,
            install_problem_detail_handler=self._install_problem_detail_handler,
        )


def _normalize_api_key(api_key: Optional[str]) -> Optional[str]:
    if api_key is None:
        return None
    value = api_key.strip()
    if not value:
        raise ValueError("api_key must not be blank")
    return value
