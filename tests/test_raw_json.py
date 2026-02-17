import json
import apache_beam as beam
from apache_beam.io.gcp.pubsub import PubsubMessage
from apache_beam.testing.test_pipeline import TestPipeline
from apache_beam.testing.util import assert_that, is_empty
from dataflow_pubsub_to_bq.transforms.raw_json import (
    ParsePubSubMessageToRawJson,
    get_dead_letter_bigquery_schema,
    get_raw_json_bigquery_schema,
)


# Define a custom matcher for DLQ content since timestamps/stacktraces vary
def matches_dlq_error(expected_payload):
    def _matcher(elements):
        if len(elements) != 1:
            raise ValueError(f"Expected 1 DLQ element, got {len(elements)}")
        row = elements[0]
        if row["original_payload"] != expected_payload:
            raise ValueError(
                f"Expected payload '{expected_payload}', got '{row['original_payload']}'"
            )
        if not row.get("error_message"):
            raise ValueError("DLQ row missing 'error_message'")
        if not row.get("stack_trace"):
            raise ValueError("DLQ row missing 'stack_trace'")
        if not row.get("processing_time"):
            raise ValueError("DLQ row missing 'processing_time'")

    return _matcher


def test_process_valid_message():
    """Test that valid JSON messages go to the main output."""
    subscription = "projects/test/subscriptions/sub"
    payload = {"ride_id": "123", "passenger_count": 1}
    message = PubsubMessage(
        data=json.dumps(payload).encode("utf-8"), attributes={"source": "test"}
    )

    with TestPipeline() as p:
        results = (
            p
            | beam.Create([message])
            | beam.ParDo(ParsePubSubMessageToRawJson(subscription)).with_outputs(
                "dlq", main="success"
            )
        )

        # Check success output
        assert_that(
            results.success,
            lambda elements: (
                len(elements) == 1
                and json.loads(elements[0]["payload"]) == payload
                and elements[0]["subscription_name"] == subscription
            ),
            label="CheckSuccess",
        )

        # Check DLQ is empty
        assert_that(results.dlq, is_empty(), label="CheckDLQEmpty")


def test_process_malformed_message():
    """Test that malformed JSON messages go to the DLQ output."""
    subscription = "projects/test/subscriptions/sub"
    invalid_json = '{"ride_id": "123", "passeng'  # truncated
    message = PubsubMessage(data=invalid_json.encode("utf-8"), attributes={})

    with TestPipeline() as p:
        results = (
            p
            | beam.Create([message])
            | beam.ParDo(ParsePubSubMessageToRawJson(subscription)).with_outputs(
                "dlq", main="success"
            )
        )

        # Check success output is empty
        assert_that(results.success, is_empty(), label="CheckSuccessEmpty")

        # Check DLQ output
        assert_that(results.dlq, matches_dlq_error(invalid_json), label="CheckDLQ")


def test_process_preserves_raw_formatting():
    """Test that valid JSON messages preserve original formatting (whitespace)."""
    subscription = "projects/test/subscriptions/sub"
    # JSON with irregular whitespace (valid)
    raw_payload = '{"ride_id":   "123", "passenger_count":\n 1}'
    message = PubsubMessage(
        data=raw_payload.encode("utf-8"), attributes={"source": "test"}
    )

    with TestPipeline() as p:
        results = (
            p
            | beam.Create([message])
            | beam.ParDo(ParsePubSubMessageToRawJson(subscription)).with_outputs(
                "dlq", main="success"
            )
        )

        # Check success output matches RAW string exactly (no normalization)
        assert_that(
            results.success,
            lambda elements: (
                len(elements) == 1 and elements[0]["payload"] == raw_payload
            ),
            label="CheckRawPreservation",
        )


def test_get_raw_json_bigquery_schema():
    """Tests that get_raw_json_bigquery_schema returns expected 6-field schema."""
    schema = get_raw_json_bigquery_schema()
    field_names = [f["name"] for f in schema]

    assert len(schema) == 6
    assert field_names == [
        "subscription_name",
        "message_id",
        "publish_time",
        "processing_time",
        "attributes",
        "payload",
    ]

    # Verify key type mappings
    field_map = {f["name"]: f for f in schema}
    assert field_map["publish_time"]["type"] == "TIMESTAMP"
    assert field_map["processing_time"]["type"] == "TIMESTAMP"
    assert field_map["payload"]["type"] == "STRING"
    assert field_map["subscription_name"]["type"] == "STRING"


def test_get_dead_letter_bigquery_schema():
    """Tests that get_dead_letter_bigquery_schema returns expected 5-field schema."""
    schema = get_dead_letter_bigquery_schema()
    field_names = [f["name"] for f in schema]

    assert len(schema) == 5
    assert field_names == [
        "processing_time",
        "error_message",
        "stack_trace",
        "original_payload",
        "subscription_name",
    ]

    # Verify key type mappings
    field_map = {f["name"]: f for f in schema}
    assert field_map["processing_time"]["type"] == "TIMESTAMP"
    assert field_map["error_message"]["type"] == "STRING"
    assert field_map["stack_trace"]["type"] == "STRING"
    assert field_map["original_payload"]["type"] == "STRING"
