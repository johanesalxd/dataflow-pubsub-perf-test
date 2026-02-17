# Dataflow to BigQuery Performance Test

Test infrastructure for reproducing and diagnosing the "Noisy Neighbor" throughput degradation when multiple Dataflow jobs write to the same BigQuery table via the Storage Write API. Tests Pub/Sub and Kafka as message sources with Python and Java SDKs.

**Conclusion:** Dataflow, BigQuery, and the message source (Pub/Sub and Kafka) are not the bottleneck. Six rounds of testing -- culminating in a Google Managed Kafka source with Avro messages and dynamic routing -- confirmed linear scaling with zero per-job degradation. The most likely production root cause is a **shared Kafka consumer group** causing partition splitting between jobs. See [detailed methodology and analysis](docs/performance_scaling_strategy.md).

## Test Scale

Six rounds of testing pushed Pub/Sub, Kafka, Dataflow, and BigQuery to their limits in `asia-southeast1`:

| Metric | Value |
| :--- | :--- |
| Total rows written to BigQuery | **~2.2 billion** |
| Total data written to BigQuery | **~2.4 TB** |
| Peak row insert rate | **901k rows/sec** |
| Peak sustained write throughput | **425 MB/s** |
| Peak burst write throughput | **579 MB/s** |
| Regional throughput quota (~314 MB/s) | Breached and sustained |
| Zero errors across all rounds | 0 DLQ entries, 0 failed inserts |

The BigQuery Storage Write API regional quota was exceeded in multiple rounds, confirming the system can sustain well beyond documented limits with burst headroom. Row insert rates approached 1 million rows/sec -- all with zero errors, zero data loss, and linear scaling when adding concurrent jobs.

## Quick Start

```bash
# 1. Set PROJECT_ID in run_perf_test.sh and cleanup_perf_test.sh

# 2. Setup GCP resources (topic, subscriptions, BQ table)
./run_perf_test.sh setup

# 3. Publish synthetic messages (batch job, auto-terminates)
./run_perf_test.sh publish
./run_perf_test.sh publish-status    # wait for JOB_STATE_DONE

# 4. Phase 1 -- single job baseline
./run_perf_test.sh job a
# Wait 10 minutes for steady state

# 5. Phase 2 -- noisy neighbor
./run_perf_test.sh job b
# Wait 10+ minutes, observe metrics

# 6. Monitor
./run_perf_test.sh monitor

# 7. Cleanup
./cleanup_perf_test.sh --force
```

## Test Architecture

### Pub/Sub Source (Rounds 1-5)

A Dataflow batch publisher pushes synthetic messages to a Pub/Sub topic. Multiple subscriptions each feed a separate consumer Dataflow job. All consumer jobs write to the same BigQuery table(s).

```
                         +-> Sub A -> Dataflow Job A -+
Publisher (Dataflow)     |                            |
  -> perf_test_topic ----+                            +-> BQ: taxi_events_perf
                         |                            |
                         +-> Sub B -> Dataflow Job B -+
                        (-> Sub N -> Dataflow Job N)
```

### Kafka Source (Round 6)

A Dataflow batch publisher writes Avro messages to a Managed Kafka topic. Each consumer job uses a **separate consumer group** so each independently reads all messages. All jobs write to 4 BQ tables via dynamic routing.

```
                                   +-> ConsumerGroup A -> Dataflow Job A -+
Publisher (Dataflow)               |  (reads all 32 partitions)           |
  -> Kafka topic (32 partitions) --+                                      +-> BQ: taxi_events_perf_{status}
                                   |                                      |    (4 tables)
                                   +-> ConsumerGroup B -> Dataflow Job B -+
```

Consumer pipelines include Python (`pipeline_json.py` for raw JSON) and Java (for Avro + dynamic routing). Both SDKs are tested for comparison.

## Key Results

| Round | SDK | Source | Single Job | 2 Jobs Combined | Per-Job Degradation | Connections |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| 1 | Python | Pub/Sub | 160 MB/s, 16k RPS | 330 MB/s, 33k RPS | None | 120 |
| 2 | Python | Pub/Sub | 161 MB/s, 16k RPS | 328 MB/s (2-job tail) | None | 120 |
| 3 | Python | Pub/Sub | 31 MB/s, 55k RPS | N/A (CPU-bound) | N/A | N/A |
| 4 | Java | Pub/Sub | 190 MB/s, 340k RPS | 393 MB/s, 700k RPS | None | 25 |
| 5 | Java | Pub/Sub | 59 MB/s, 457k RPS | 114 MB/s, 901k RPS | None | 42 |
| **6** | **Java** | **Kafka** | **46 MB/s, 401k RPS** | **91 MB/s, 812k RPS** | **None** | **12** |

Round 6 used Google Managed Kafka as the source with the same Avro binary format, dynamic table routing, write method (`STORAGE_API_AT_LEAST_ONCE`), connection pooling, and region (`asia-southeast1`) as the production workload. Two jobs with **separate consumer groups** achieved 812k rows/sec combined -- 4.1x the production target -- with zero per-job degradation. The production noisy neighbor scenario (200k total dropping to 100k per job) cannot be reproduced with separate consumer groups.

| Hypothesis | Result |
| :--- | :--- |
| Per-table contention | Disproven. Linear scaling with no degradation (Rounds 1, 4, 5, 6). |
| Connection limit | Peak 42 connections (4.2% of 1,000 limit). |
| Throughput quota | Only triggered above ~314 MB/s. Production is well below. |
| Write method | No effect. Exactly-once and at-least-once produce identical throughput. |
| Pub/Sub vs Kafka source | No effect. Both show zero degradation with separate consumer groups. |
| KafkaIO connector | Not a bottleneck. ~401k RPS per job with OOTB config. |

## Configuration

Edit the configuration section at the top of `run_perf_test.sh`:

| Parameter | Default | Description |
| :--- | :--- | :--- |
| `NUM_CONSUMERS` | `2` | Number of consumer subscriptions (a, b, c, ...) |
| `CONSUMER_NUM_WORKERS` | `3` | Workers per consumer job |
| `CONSUMER_MACHINE_TYPE` | `n2-standard-4` | Consumer worker machine type |
| `PUBLISHER_NUM_WORKERS` | `5` | Workers for the publisher job |
| `PUBLISHER_MACHINE_TYPE` | `n2-standard-4` | Publisher worker machine type |
| `NUM_MESSAGES` | `400000000` | Total messages per subscription |
| `MESSAGE_SIZE_BYTES` | `500` | Target size per message in bytes |

## Cost Estimate

Per Pub/Sub test run (400M messages, 2 consumers, ~500 bytes/msg, ~200 GB):

| Component | Cost |
| :--- | :--- |
| Pub/Sub publish (200 GB) | ~$8 |
| Pub/Sub subscribe (200 GB x 2 subs) | ~$16 |
| Dataflow publisher (5 workers, ~10 min) | ~$0.33 |
| Dataflow consumers (3 workers each, ~35 min) | ~$2.40 |
| **Total** | **~$27** |

Per Kafka test run (400M messages, 2 consumer groups, ~88 bytes/msg, ~35 GB):

| Component | Cost |
| :--- | :--- |
| Managed Kafka cluster (3 vCPU, ~2 hours) | ~$1.50 |
| Dataflow publisher (3 workers, ~15 min) | ~$0.50 |
| Dataflow consumers (3 workers each, ~20 min) | ~$1.40 |
| **Total** | **~$3.40** |

## Project Structure

```
dataflow-pubsub-perf-test/
+-- README.md
+-- pyproject.toml
+-- run_perf_test.sh                       # Test orchestrator (Pub/Sub + Kafka)
+-- cleanup_perf_test.sh                   # Tear down GCP resources
+-- dataflow_pubsub_to_bq/                 # Python package
|   +-- pipeline_json.py                   # Consumer pipeline (Pub/Sub JSON)
|   +-- pipeline_options.py                # Consumer pipeline options
|   +-- pipeline_publisher.py              # Synthetic message publisher
|   +-- pipeline_publisher_options.py      # Publisher options
|   +-- transforms/
|       +-- raw_json.py                    # Raw JSON BQ transform
|       +-- synthetic_messages.py          # Message generation DoFns
+-- tests/
|   +-- test_raw_json.py
|   +-- test_pipeline_publisher.py
|   +-- test_avro_publisher.py
+-- java/                                  # Java SDK pipelines
|   +-- pom.xml
|   +-- src/main/resources/
|   |   +-- taxi_ride_v1.avsc              # Avro schema (single source of truth)
|   +-- src/main/java/.../
|   |   +-- PubSubToBigQueryJson.java      # Pub/Sub JSON consumer (Round 4)
|   |   +-- PubSubToBigQueryAvro.java      # Pub/Sub Avro consumer (Round 5)
|   |   +-- KafkaToBigQueryAvro.java       # Kafka Avro consumer (Round 6)
|   |   +-- KafkaAvroPublisher.java        # Kafka Avro publisher (Round 6)
|   |   +-- transforms/
|   |       +-- PubsubMessageToRawJson.java
|   |       +-- PubsubAvroToTableRow.java
|   |       +-- KafkaAvroToTableRow.java
|   |       +-- SyntheticAvroGenerator.java
|   +-- src/test/java/.../                 # JUnit 4 tests
+-- docs/
|   +-- performance_scaling_strategy.md    # Detailed methodology and analysis
|   +-- perf_test_results_round1.md        # Python, Pub/Sub, JSON, 10 KB
|   +-- perf_test_results_round2.md        # Python, Pub/Sub, JSON, 3 jobs
|   +-- perf_test_results_round3.md        # Python, Pub/Sub, JSON, 500 B
|   +-- perf_test_results_round4.md        # Java, Pub/Sub, JSON, 500 B
|   +-- perf_test_results_round5.md        # Java, Pub/Sub, Avro, dynamic routing
|   +-- perf_test_results_round6.md        # Java, Kafka, Avro, dynamic routing
+-- images/                                # Metrics screenshots (Rounds 1-2)
```

## Prerequisites

- Google Cloud Project with billing enabled
- APIs enabled: Dataflow, Pub/Sub, BigQuery, Cloud Storage, Managed Service for Apache Kafka
- gcloud CLI configured
- Python 3.12+
- [uv](https://docs.astral.sh/uv/) package manager
- Maven (for Java pipelines)

## Setup and Testing

```bash
# Install dependencies
uv sync

# Run tests
uv run pytest

# Lint
uv run ruff check .

# Build wheel (for Dataflow workers)
uv build --wheel
```

## Documentation

- [Detailed methodology, monitoring, and analysis](docs/performance_scaling_strategy.md) -- test procedure, monitoring queries, decision matrix, cost estimates, and recommendations
- [Round 1 results](docs/perf_test_results_round1.md) -- Python SDK, Pub/Sub, 10 KB JSON, 2 consumers
- [Round 2 results](docs/perf_test_results_round2.md) -- Python SDK, Pub/Sub, 10 KB JSON, 3 consumers
- [Round 3 results](docs/perf_test_results_round3.md) -- Python SDK, Pub/Sub, 500 B JSON, write method comparison
- [Round 4 results](docs/perf_test_results_round4.md) -- Java SDK, Pub/Sub, 500 B JSON, production-scale
- [Round 5 results](docs/perf_test_results_round5.md) -- Java SDK, Pub/Sub, Avro binary, dynamic table routing
- [Round 6 results](docs/perf_test_results_round6.md) -- Java SDK, Kafka, Avro binary, dynamic table routing

## References

- [BigQuery Storage Write API best practices](https://cloud.google.com/bigquery/docs/write-api-best-practices)
- [Pub/Sub to BigQuery best practices](https://cloud.google.com/dataflow/docs/guides/pubsub-bigquery-best-practices)
- [Pub/Sub to BigQuery performance benchmarks](https://cloud.google.com/dataflow/docs/guides/pubsub-bigquery-performance)
- [Write from Dataflow to BigQuery](https://cloud.google.com/dataflow/docs/guides/write-to-bigquery)
- [BigQuery Storage Write API quotas](https://cloud.google.com/bigquery/quotas#write-api-limits)

## License

This project is provided as-is for educational and testing purposes.
