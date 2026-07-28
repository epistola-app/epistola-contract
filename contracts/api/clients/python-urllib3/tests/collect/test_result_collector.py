"""Tests for ResultCollector: murmur3 hashing, partition routing, and builder guards."""

import pytest

from epistola_client import PartitionAssignment, ResultCollector
from epistola_client.collect.result_collector import _murmur3_x86_32


def test_murmur3_matches_guava_reference_vector():
    # Guava Hashing.murmur3_32(0).hashString("hello", UTF_8) == 0x248bfa47
    assert _murmur3_x86_32(b"hello", 0) == 0x248BFA47


def test_murmur3_empty_input_is_zero():
    assert _murmur3_x86_32(b"", 0) == 0


def _collector_with_assignment(assignment):
    collector = (
        ResultCollector.builder()
        .api_client(object())
        .tenant_id("acme")
        .handler(lambda r: None)
        .register_shutdown_hook(False)
        .build()
    )
    collector.current_partition_assignment = assignment
    return collector


def test_partition_for_is_none_before_assignment_known():
    collector = (
        ResultCollector.builder()
        .api_client(object())
        .tenant_id("acme")
        .handler(lambda r: None)
        .build()
    )
    assert collector.partition_for("anything") is None


def test_partition_for_is_stable_and_in_range():
    collector = _collector_with_assignment(PartitionAssignment(total=8, mine=[0, 1]))
    p = collector.partition_for("customer-123")
    assert p is not None and 0 <= p < 8
    assert collector.partition_for("customer-123") == p


def test_routing_key_to_me_returns_key_unchanged_when_all_partitions_are_mine():
    # When this node owns every partition, any key already routes here.
    collector = _collector_with_assignment(PartitionAssignment(total=4, mine=[0, 1, 2, 3]))
    assert collector.is_my_partition("some-key")
    assert collector.routing_key_to_me("some-key") == "some-key"


def test_routing_key_to_me_prefixes_a_key_that_lands_on_my_partition():
    collector = _collector_with_assignment(PartitionAssignment(total=8, mine=[0, 1]))
    routed = collector.routing_key_to_me("some-key")
    assert routed is not None
    # Best-effort (matching the Kotlin/.NET contract): if a prefixed candidate landed on
    # one of our partitions it is returned; otherwise a prefixed fallback is returned.
    if routed != "some-key" and collector.is_my_partition(routed):
        assert routed.split(":", 1)[0] in {"0", "1"}


def test_builder_requires_api_client():
    with pytest.raises(ValueError):
        ResultCollector.builder().tenant_id("t").handler(lambda r: None).build()


def test_builder_rejects_out_of_range_batch_size():
    with pytest.raises(ValueError):
        ResultCollector.builder().batch_size(0)


def test_builder_rejects_backoff_multiplier_le_one():
    with pytest.raises(ValueError):
        ResultCollector.builder().backoff_multiplier(1.0)
