# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""Tests for JwtSigner (self-signed JWT minting)."""

import datetime

import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec, rsa

from epistola_client import JwtSigner


def _ec_key_pem():
    key = ec.generate_private_key(ec.SECP256R1())
    return key, key.public_key()


def _rsa_key():
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


def test_signs_es256_token_with_expected_claims():
    private_key, public_key = _ec_key_pem()
    signer = JwtSigner.builder().consumer_id("invoice-service").private_key(private_key).build()

    token = signer.create_token()
    decoded = jwt.decode(token, public_key, algorithms=["ES256"])

    assert decoded["iss"] == "invoice-service"
    assert "jti" in decoded
    assert decoded["exp"] > decoded["iat"]


def test_two_tokens_have_distinct_jti():
    private_key, _ = _ec_key_pem()
    signer = JwtSigner.builder().consumer_id("svc").private_key(private_key).build()
    t1 = jwt.decode(signer.create_token(), options={"verify_signature": False})
    t2 = jwt.decode(signer.create_token(), options={"verify_signature": False})
    assert t1["jti"] != t2["jti"]


def test_rsa_key_uses_rs256():
    private_key = _rsa_key()
    signer = JwtSigner.builder().consumer_id("svc").private_key(private_key).build()
    header = jwt.get_unverified_header(signer.create_token())
    assert header["alg"] == "RS256"


def test_token_lifetime_is_respected():
    private_key, _ = _ec_key_pem()
    signer = (
        JwtSigner.builder()
        .consumer_id("svc")
        .private_key(private_key)
        .token_lifetime(datetime.timedelta(seconds=120))
        .build()
    )
    claims = jwt.decode(signer.create_token(), options={"verify_signature": False})
    assert 115 <= claims["exp"] - claims["iat"] <= 121


def test_parse_private_key_pem_roundtrip():
    private_key = _rsa_key()
    pem = private_key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    parsed = JwtSigner.parse_private_key_pem(pem)
    assert isinstance(parsed, rsa.RSAPrivateKey)


def test_missing_consumer_id_is_rejected():
    private_key = _rsa_key()
    with pytest.raises(ValueError):
        JwtSigner.builder().private_key(private_key).build()


def test_invalid_pem_raises_value_error():
    with pytest.raises(ValueError):
        JwtSigner.parse_private_key_pem("not a pem")
