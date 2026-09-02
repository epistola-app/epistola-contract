# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

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


def test_routing_key_to_me_always_produces_a_key_that_lands_here():
    # Trying only the partition numbers this node owns is not enough: "3:key" hashes to wherever
    # it hashes, not to partition 3. With 2 of 8 partitions the old fallback returned a foreign
    # key more often than not, which sends the result to another node.
    collector = _collector_with_assignment(PartitionAssignment(total=8, mine=[0, 1]))
    rewritten = 0
    for i in range(100):
        key = f"order-{i}"
        routed = collector.routing_key_to_me(key)
        assert routed is not None, f"routing_key_to_me returned None for {key}"
        assert collector.is_my_partition(routed), f"produced a foreign key: {routed}"
        if routed != key:
            rewritten += 1
    assert rewritten > 0, "with 2 of 8 partitions, most keys should need rewriting"


def test_routing_key_to_me_is_deterministic():
    collector = _collector_with_assignment(PartitionAssignment(total=8, mine=[0, 1]))
    assert collector.routing_key_to_me("order-7") == collector.routing_key_to_me("order-7")


def test_partition_helpers_are_safe_when_the_partition_count_is_missing():
    collector = _collector_with_assignment(PartitionAssignment(total=0, mine=[]))
    assert collector.partition_for("anything") is None
    assert collector.is_my_partition("anything") is False
    assert collector.routing_key_to_me("anything") is None


def test_backoff_recovers_from_a_has_more_burst_instead_of_returning_zero():
    # has_more sets the interval to 0 so the next poll is immediate, and 0 * multiplier is still
    # 0 — without a floor, a burst that drained left the loop polling /generation/collect flat
    # out forever, with no path back to a sane interval.
    collector = (
        ResultCollector.builder()
        .api_client(object())
        .tenant_id("acme")
        .handler(lambda r: None)
        .min_interval(1.0)
        .max_interval(30.0)
        .register_shutdown_hook(False)
        .build()
    )
    assert collector._back_off(0.0) == 1.0
    assert collector._back_off(1.0) == 3.0
    assert collector._back_off(20.0) == 30.0


def test_builder_requires_api_client():
    with pytest.raises(ValueError):
        ResultCollector.builder().tenant_id("t").handler(lambda r: None).build()


def test_builder_rejects_out_of_range_batch_size():
    with pytest.raises(ValueError):
        ResultCollector.builder().batch_size(0)


def test_builder_rejects_backoff_multiplier_le_one():
    with pytest.raises(ValueError):
        ResultCollector.builder().backoff_multiplier(1.0)
