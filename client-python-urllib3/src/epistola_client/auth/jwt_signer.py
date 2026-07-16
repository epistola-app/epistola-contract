"""Creates and signs short-lived JWTs for self-signed JWT authentication with Epistola.

Each token contains:

* ``iss``: the consumer ID
* ``iat``: issued-at timestamp
* ``exp``: expiry (iat + token_lifetime)
* ``jti``: unique nonce (UUID) for replay protection

Example::

    signer = (
        JwtSigner.builder()
        .consumer_id("invoice-service")
        .private_key(JwtSigner.load_private_key("private.pem"))
        .build()
    )
"""

from __future__ import annotations

import datetime
import uuid
from typing import Optional

import jwt
from cryptography.hazmat.primitives.asymmetric import ec, rsa
from cryptography.hazmat.primitives.serialization import load_pem_private_key

_DEFAULT_LIFETIME = datetime.timedelta(seconds=60)


class JwtSigner:
    """Immutable signer that mints freshly signed JWTs for the configured consumer."""

    def __init__(self, consumer_id: str, private_key, algorithm: str, token_lifetime: datetime.timedelta) -> None:
        self._consumer_id = consumer_id
        self._private_key = private_key
        self._algorithm = algorithm
        self._token_lifetime = token_lifetime

    @staticmethod
    def builder() -> "JwtSignerBuilder":
        """Create a new :class:`JwtSignerBuilder`."""
        return JwtSignerBuilder()

    @staticmethod
    def load_private_key(path: str):
        """Load a private key from a PEM file (RSA or EC P-256, PKCS#8 ``BEGIN PRIVATE KEY``)."""
        with open(path, "rb") as handle:
            return JwtSigner.parse_private_key_pem(handle.read())

    @staticmethod
    def parse_private_key_pem(pem):
        """Parse a PEM-encoded private key (RSA or EC P-256, PKCS#8 ``BEGIN PRIVATE KEY``)."""
        if isinstance(pem, str):
            pem = pem.encode("utf-8")
        try:
            return load_pem_private_key(pem, password=None)
        except Exception as exc:  # noqa: BLE001 - normalise to a clear ValueError
            raise ValueError(
                "Failed to parse private key. Supported formats: RSA, EC (P-256) in PKCS#8 PEM format."
            ) from exc

    def create_token(self) -> str:
        """Create a freshly signed JWT with a new ``iat``, ``exp``, and ``jti``."""
        now = datetime.datetime.now(datetime.timezone.utc)
        payload = {
            "iss": self._consumer_id,
            "iat": int(now.timestamp()),
            "exp": int((now + self._token_lifetime).timestamp()),
            "jti": str(uuid.uuid4()),
        }
        return jwt.encode(payload, self._private_key, algorithm=self._algorithm)


class JwtSignerBuilder:
    """Fluent builder for :class:`JwtSigner`."""

    def __init__(self) -> None:
        self._consumer_id: Optional[str] = None
        self._private_key = None
        self._token_lifetime = _DEFAULT_LIFETIME

    def consumer_id(self, consumer_id: str) -> "JwtSignerBuilder":
        """Set the consumer ID used as the JWT ``iss`` claim."""
        if not consumer_id or not consumer_id.strip():
            raise ValueError("consumer_id must not be blank")
        self._consumer_id = consumer_id
        return self

    def private_key(self, private_key) -> "JwtSignerBuilder":
        """Set the private key used to sign tokens (from :meth:`JwtSigner.load_private_key` /
        :meth:`JwtSigner.parse_private_key_pem`).
        """
        self._private_key = private_key
        return self

    def token_lifetime(self, lifetime: datetime.timedelta) -> "JwtSignerBuilder":
        """Set the token lifetime (default: 60 seconds)."""
        if lifetime <= datetime.timedelta(0):
            raise ValueError("token_lifetime must be positive")
        self._token_lifetime = lifetime
        return self

    def build(self) -> JwtSigner:
        """Build the immutable :class:`JwtSigner`."""
        if self._consumer_id is None:
            raise ValueError("consumer_id is required")
        if self._private_key is None:
            raise ValueError("private_key is required")

        if isinstance(self._private_key, rsa.RSAPrivateKey):
            algorithm = "RS256"
        elif isinstance(self._private_key, ec.EllipticCurvePrivateKey):
            algorithm = "ES256"
        else:
            raise ValueError(
                f"Unsupported key type: {type(self._private_key).__name__}. Supported: RSA (2048+), EC (P-256)"
            )

        return JwtSigner(self._consumer_id, self._private_key, algorithm, self._token_lifetime)
