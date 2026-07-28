"""Client identity headers required on every Epistola API request.

The ``User-Agent`` always starts with ``epistola-contract/{contractVersion}``.
Additional product tokens can be appended to describe the full software stack.

Example::

    identity = (
        ClientIdentity.builder()
        .node_id("my-pod-123")
        .product("valtimo-epistola-plugin", "1.2.0")
        .product("gzac", "5.0.0")
        .build()
    )

produces headers::

    User-Agent: epistola-contract/0.11.0 valtimo-epistola-plugin/1.2.0 gzac/5.0.0
    X-EP-Node-Id: my-pod-123
"""

from __future__ import annotations

import socket
from typing import List, Optional, Tuple

from epistola_client._generated.contract_version import CONTRACT_VERSION

#: The ``X-EP-Node-Id`` header name.
HEADER_NODE_ID = "X-EP-Node-Id"

_CONTRACT_PRODUCT = "epistola-contract"


class ClientIdentity:
    """Immutable holder for the assembled ``User-Agent`` and ``X-EP-Node-Id`` header values."""

    #: The contract version this client library was built against.
    CONTRACT_VERSION = CONTRACT_VERSION

    def __init__(self, user_agent: str, node_id: str) -> None:
        #: The assembled ``User-Agent`` header value.
        self.user_agent = user_agent
        #: The ``X-EP-Node-Id`` header value.
        self.node_id = node_id

    @staticmethod
    def builder() -> "ClientIdentityBuilder":
        """Create a new :class:`ClientIdentityBuilder`."""
        return ClientIdentityBuilder()

    def headers(self) -> dict:
        """The identity headers as a plain dict, for merging into request headers."""
        return {"User-Agent": self.user_agent, HEADER_NODE_ID: self.node_id}


class ClientIdentityBuilder:
    """Fluent builder for :class:`ClientIdentity`."""

    def __init__(self) -> None:
        self._node_id: Optional[str] = None
        self._products: List[Tuple[str, str]] = []

    def node_id(self, node_id: str) -> "ClientIdentityBuilder":
        """Set the node identifier (e.g. Kubernetes pod name, hostname). Defaults to the local hostname."""
        self._node_id = node_id
        return self

    def product(self, name: str, version: str) -> "ClientIdentityBuilder":
        """Append a product/version pair to the ``User-Agent``, after the
        ``epistola-contract/{version}`` token.
        """
        if not name or not name.strip():
            raise ValueError("Product name must not be blank")
        if not version or not version.strip():
            raise ValueError("Product version must not be blank")
        if "/" in name or " " in name:
            raise ValueError("Product name must not contain '/' or spaces")
        self._products.append((name, version))
        return self

    def build(self) -> ClientIdentity:
        """Build the immutable :class:`ClientIdentity`."""
        tokens = [f"{_CONTRACT_PRODUCT}/{CONTRACT_VERSION}"]
        tokens.extend(f"{name}/{version}" for name, version in self._products)
        return ClientIdentity(" ".join(tokens), self._node_id or socket.gethostname())
