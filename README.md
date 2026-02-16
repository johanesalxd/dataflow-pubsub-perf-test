# BigQuery Write Contention Test

Test infrastructure for reproducing and diagnosing the "Noisy Neighbor" throughput degradation when multiple Dataflow jobs write to the same BigQuery table via the Storage Write API.

**Conclusion:** Dataflow and BigQuery are not the bottleneck. Two jobs writing to the same table scale linearly with zero per-job degradation. The production bottleneck is upstream (Kafka source path). See [detailed methodology and analysis](docs/performance_scaling_strategy.md).

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

A Dataflow batch publisher pushes synthetic messages to a Pub/Sub topic. Multiple subscriptions each feed a separate consumer Dataflow job. All consumer jobs write to the same BigQuery table.

```
                         +-> Sub A -> Dataflow Job A -+
Publisher (Dataflow)     |                            |
  -> perf_test_topic ----+                            +-> BQ: taxi_events_perf
                         |                            |
                         +-> Sub B -> Dataflow Job B -+
                        (-> Sub N -> Dataflow Job N)
```

The consumer pipeline (`pipeline_json.py`) stores raw JSON payloads in a BQ `JSON` column with minimal CPU overhead, isolating the BQ write path. A Java consumer pipeline is also included for SDK comparison testing.

## Key Results

| Round | SDK | Single Job | 2 Jobs Combined | Per-Job Degradation | Connections |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 1 | Python | 160 MB/s, 16k RPS | 330 MB/s, 33k RPS | None | 120 |
| 2 | Python | 161 MB/s, 16k RPS | 328 MB/s (2-job tail) | None | 120 |
| 3 | Python | 31 MB/s, 55k RPS | N/A (CPU-bound) | N/A | N/A |
| **4** | **Java** | **190 MB/s, 340k RPS** | **393 MB/s, 700k RPS** | **None** | **25** |

Round 4 used the same Java SDK, write method (`STORAGE_API_AT_LEAST_ONCE`), connection pooling, region (`asia-southeast1`), and message size (~500 bytes) as the production workload. Two jobs achieved 757k rows/sec combined -- 3.8x the production target -- with zero per-job degradation.

| Hypothesis | Result |
| :--- | :--- |
| Per-table contention | Disproven. Linear scaling with no degradation. |
| Connection limit | Peak 25 connections (2.5% of 1,000 limit). |
| Throughput quota | Only triggered above ~314 MB/s. Production is well below. |
| Write method | No effect. Exactly-once and at-least-once produce identical throughput. |

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

Per test run (400M messages, 2 consumers, ~500 bytes/msg):

| Component | Cost |
| :--- | :--- |
| Pub/Sub publish + subscribe | ~$15 |
| Dataflow publisher (5 workers, ~10 min) | ~$0.33 |
| Dataflow consumers (3 workers each, ~35 min) | ~$2.40 |
| **Total** | **~$18** |

## Project Structure

```
dataflow-pubsub-perf-test/
+-- README.md
+-- pyproject.toml
+-- run_perf_test.sh                       # Test orchestrator
+-- cleanup_perf_test.sh                   # Tear down GCP resources
+-- dataflow_pubsub_to_bq/
|   +-- pipeline_json.py                   # Consumer pipeline (benchmarked)
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
+-- java/                                  # Java SDK comparison pipeline
|   +-- pom.xml
|   +-- src/
+-- docs/
|   +-- performance_scaling_strategy.md    # Detailed methodology and analysis
|   +-- perf_test_results_round1.md
|   +-- perf_test_results_round2.md
|   +-- perf_test_results_round3.md
|   +-- perf_test_results_round4.md
|   +-- perf_test_results_round5.md
+-- images/                                # Metrics screenshots
```

## Prerequisites

- Google Cloud Project with billing enabled
- APIs enabled: Dataflow, Pub/Sub, BigQuery, Cloud Storage
- gcloud CLI configured
- Python 3.12+
- [uv](https://docs.astral.sh/uv/) package manager
- Maven (for Java pipeline only)

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
- [Round 1 results](docs/perf_test_results_round1.md) -- Python SDK, 10 KB messages, 2 consumers
- [Round 2 results](docs/perf_test_results_round2.md) -- Python SDK, 10 KB messages, 3 consumers
- [Round 3 results](docs/perf_test_results_round3.md) -- Python SDK, 500 B messages, write method comparison
- [Round 4 results](docs/perf_test_results_round4.md) -- Java SDK, 500 B messages, production-scale
- [Round 5 results](docs/perf_test_results_round5.md) -- Java SDK, Avro binary, dynamic table routing

## References

- [BigQuery Storage Write API best practices](https://cloud.google.com/bigquery/docs/write-api-best-practices)
- [Pub/Sub to BigQuery best practices](https://cloud.google.com/dataflow/docs/guides/pubsub-bigquery-best-practices)
- [Pub/Sub to BigQuery performance benchmarks](https://cloud.google.com/dataflow/docs/guides/pubsub-bigquery-performance)
- [Write from Dataflow to BigQuery](https://cloud.google.com/dataflow/docs/guides/write-to-bigquery)
- [BigQuery Storage Write API quotas](https://cloud.google.com/bigquery/quotas#write-api-limits)

## License

This project is provided as-is for educational and testing purposes.
