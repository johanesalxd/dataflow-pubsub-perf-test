# BigQuery Write Contention Test Methodology

Reproducing and diagnosing the "Noisy Neighbor" throughput degradation when multiple Dataflow jobs write to the same BigQuery table.

## 1. Problem Statement

Consider a streaming architecture with multiple Kafka-to-BQ Dataflow jobs (Avro, 24 MB/s per topic, ~100 MB/s after decode). A single job writes to BigQuery at 100 MB/s. Adding a second identical job to the same table should give 200 MB/s total, but only 120 MB/s is observed. Both jobs show low CPU and low Dataflow lag -- no errors are visible.

**Question:** Is this BQ per-table contention, quota limit, or connection limit?

## 2. Hypothesis

BQ Storage Write API has per-table throughput limits beyond the documented project-level quotas (300 MB/s regional, 1,000 connections). When multiple Dataflow jobs write to the same table, they contend for per-table write bandwidth, causing throughput degradation invisible to standard Dataflow metrics.

**Resolution:** This hypothesis was disproven across six rounds of testing. Rounds 1-2 (Python SDK, 10 KB messages) confirmed no per-table contention -- 2 jobs scale linearly to ~330 MB/s. Round 3 (Python SDK, 500-byte messages) showed the Python SDK is CPU-bound at ~55k rows/sec. Round 4 (Java SDK, 500-byte messages) was the definitive test: using the same technology stack as the production workload, 2 Java jobs achieved **757k rows/sec combined** (~425 MB/s) with **zero per-job degradation** and only **25 concurrent connections**. Rounds 5-6 reproduced the production Avro + dynamic routing configuration with Pub/Sub and Kafka respectively. Round 5 (Pub/Sub, Avro, 4 dynamic tables) achieved **901k rows/sec** combined. Round 6 (Google Managed Kafka, same config) achieved **812k rows/sec** combined -- both with zero per-job degradation. The bottleneck is **not** in Dataflow, BigQuery, or the message source (Pub/Sub or Kafka). The most likely production root cause is a **shared Kafka consumer group** causing partition splitting between jobs. See [Round 1](perf_test_results_round1.md), [Round 2](perf_test_results_round2.md), [Round 3](perf_test_results_round3.md), [Round 4](perf_test_results_round4.md), [Round 5](perf_test_results_round5.md), and [Round 6](perf_test_results_round6.md).

## 3. BigQuery Storage Write API Quotas

Source: [BigQuery Quotas -- Storage Write API](https://cloud.google.com/bigquery/quotas#write-api-limits)

| Resource | Multi-Region (`us`/`eu`) | Regional (e.g., `asia-southeast1`) |
| :--- | :--- | :--- |
| **Throughput** | 3 GB/s per project | 300 MB/s per project |
| **Concurrent Connections** | 10,000 per project | 1,000 per project |
| **CreateWriteStream** | 10,000 streams/hour | 10,000 streams/hour |

Key details:
- Throughput quota is metered on the **destination project** (where the BQ dataset lives).
- Concurrent connections are metered on the **client project** (the Dataflow project).
- When the BQ sink is saturated, Dataflow workers **self-throttle** and stop pulling from Pub/Sub. This causes low CPU and low Dataflow lag, while the Pub/Sub backlog grows.
- Each Beam worker may open 10-50 write streams. Two jobs with 20 workers each could approach the 1,000 regional connection limit.

## 4. Test Architecture

A Dataflow batch publisher pushes synthetic messages to a Pub/Sub topic (or Kafka topic for round 6). Multiple subscriptions (or consumer groups) each feed a separate consumer Dataflow job. All consumer jobs write to the same BigQuery table(s).

```
                         ┌→ Sub A → Dataflow Job A ─┐
Publisher (Dataflow)     │                           │
  → perf_test_topic ─────┤                           ├→ BQ: taxi_events_perf
                         │                           │
                         └→ Sub B → Dataflow Job B ─┘
                        (→ Sub N → Dataflow Job N)
```

### Kafka Source Architecture (Round 6)

A Dataflow batch publisher writes 400M Avro messages to a Managed Kafka topic with 32 partitions. Each consumer Dataflow job uses a **separate consumer group** (`perf-a2`, `perf-b2`) so each independently reads all messages. All consumer jobs write to the same set of BigQuery tables via dynamic routing.

```
                                  ┌→ ConsumerGroup perf-a2 → Dataflow Job A ─┐
Publisher (Dataflow batch)        │   (reads all 32 partitions)              │
  → Kafka topic (32 partitions) ──┤                                          ├→ BQ: taxi_events_perf_{status}
                                  │                                          │    (4 tables: enroute, pickup,
                                  └→ ConsumerGroup perf-b2 → Dataflow Job B ─┘     dropoff, waiting)
```

### Why This Design

- **Single topic, multiple subscriptions/consumer groups:** Each subscription (Pub/Sub) or consumer group (Kafka) independently receives all messages. One publisher, identical data for every consumer. Fair comparison.
- **Batch publisher on Dataflow:** Scalable, no local machine bottleneck. Auto-terminates after publishing all messages.
- **Consumer pipelines:** Python `pipeline_json.py` (rounds 1-3) stores raw JSON in a BQ `JSON` column with minimal CPU overhead. Java pipelines (rounds 4-6) match the production stack with Avro deserialization and dynamic routing.
- **Same BQ table(s):** Reproduces the production scenario where jobs compete for the same table.

### Write Method

Rounds 1-3 use the Python consumer pipeline (`pipeline_json.py`) with `STORAGE_WRITE_API` (exactly-once semantics, application-created streams). Rounds 4-6 use Java consumer pipelines with `STORAGE_API_AT_LEAST_ONCE` (default stream) and `useStorageApiConnectionPool=true` (multiplexing), matching the production workload configuration. Rounds 5-6 add dynamic table routing based on `ride_status` to 4 BQ tables.

The switch to at-least-once with connection pooling in round 4 reduced concurrent connections from ~60 per job (rounds 1-2) to ~10-15 per job, confirming multiplexing effectiveness. Round 3 showed that write method has no measurable impact on throughput when the pipeline is CPU-bound.

### Message Design

| Parameter | Rounds 1-2 | Rounds 3-4 | Rounds 5-6 |
| :--- | :--- | :--- | :--- |
| Message size | ~10,000 bytes (9 taxi fields + `_padding`) | ~500 bytes (9 taxi fields, no padding) | ~88 bytes (Avro binary, 9 fields) |
| Message format | JSON string | JSON string | Avro binary |
| Total messages | 36,000,000 (per subscription) | 400,000,000 (per subscription) | 400,000,000 (per subscription/consumer group) |
| Total data | ~360 GB (per subscription) | ~200 GB (per subscription) | ~35 GB (per subscription/consumer group) |
| BQ destinations | 1 table | 1 table | 4 tables (dynamic routing by `ride_status`) |
| Duration at 100 MB/s | ~60 minutes | ~35 minutes | ~6 minutes (data is small; CPU-bound) |

## 5. Test Procedure

### 5.1 Prerequisites

Required GCP APIs: Dataflow, Pub/Sub, BigQuery, Cloud Storage.

The `PROJECT_ID` is configured at the top of:
- `run_perf_test.sh`
- `cleanup_perf_test.sh`

### 5.2 Validate Sizes (Dry Run)

No GCP resources are created, no cost incurred.

```bash
./run_perf_test.sh dry-run
```

### 5.3 Setup Resources

Creates GCS bucket, BQ table, Pub/Sub topic, N subscriptions, and builds the wheel. Idempotent -- safe to re-run.

```bash
./run_perf_test.sh setup
```

### 5.4 Publish Messages

Launches a batch Dataflow job that generates synthetic messages (36M for rounds 1-2, 400M for rounds 3-6) and publishes them to the topic. Each subscription receives a full copy. The job auto-terminates when done.

```bash
# Launch publisher
./run_perf_test.sh publish

# Check status (wait for JOB_STATE_DONE)
./run_perf_test.sh publish-status
```

**Important:** All subscriptions must exist before publishing. If you need more consumers, increase `NUM_CONSUMERS` in `run_perf_test.sh` and re-run `setup` before publishing.

After the publisher finishes, verify each subscription received the expected data volume (~360 GB for 10 KB messages, ~200 GB for 500-byte messages, ~35 GB for Avro):

```bash
curl -s -H "Authorization: Bearer $(gcloud auth print-access-token)" \
  "https://monitoring.googleapis.com/v3/projects/PROJECT_ID/timeSeries?\
filter=metric.type%3D%22pubsub.googleapis.com%2Fsubscription%2Fbacklog_bytes%22\
%20AND%20(resource.labels.subscription_id%3D%22perf_test_sub_a%22\
%20OR%20resource.labels.subscription_id%3D%22perf_test_sub_b%22)\
&interval.startTime=$(date -u -d '30 minutes ago' +%Y-%m-%dT%H:%M:%SZ)\
&interval.endTime=$(date -u +%Y-%m-%dT%H:%M:%SZ)\
&aggregation.alignmentPeriod=60s\
&aggregation.perSeriesAligner=ALIGN_MEAN"
```

Each subscription should show the expected data volume for the configured message size. If significantly less, the publisher may have failed partway through -- check the Dataflow job logs.

### 5.5 Phase 1 -- Single Job Baseline

Launch only Job A. Wait 10 minutes for steady state.

```bash
./run_perf_test.sh job a
```

Record:
- Worker CPU %
- BQ write throughput
- Pub/Sub backlog for `perf_test_sub_a`

### 5.6 Phase 2 -- Noisy Neighbor

Keep Job A running. Launch Job B.

```bash
./run_perf_test.sh job b
```

Wait 10+ minutes. Observe whether Job A's metrics degrade after Job B starts writing to the same table.

### 5.7 Scaling Variations

To add more consumers or change worker count:

```bash
# Edit configuration in run_perf_test.sh:
#   NUM_CONSUMERS=4        (add more subscriptions)
#   CONSUMER_NUM_WORKERS=10 (more workers per job)

# Re-run setup (idempotent, creates new subs without touching existing ones)
./run_perf_test.sh setup

# Re-publish (new subs need fresh messages)
./run_perf_test.sh publish

# Launch additional jobs
./run_perf_test.sh job c
./run_perf_test.sh job d
```

### 5.8 Cleanup

Cancels all running Dataflow jobs, deletes subscriptions, topic, and BQ tables.

```bash
./cleanup_perf_test.sh         # interactive
./cleanup_perf_test.sh --force  # skip confirmation
```

Consumer jobs are streaming and will idle indefinitely after draining their backlog. Always run cleanup when done.

## 6. Monitoring

### Console Links

Run `./run_perf_test.sh monitor` to print all URLs. Key pages:

| Page | What to Check |
| :--- | :--- |
| [Dataflow Jobs](https://console.cloud.google.com/dataflow/jobs) | Job state, worker count, system lag |
| [Pub/Sub Subscriptions](https://console.cloud.google.com/cloudpubsub/subscription/list) | Unacked message count (backlog) |
| [BQ Quota (throughput)](https://console.cloud.google.com/iam-admin/quotas?metric=bigquerystorage.googleapis.com/write/append_bytes) | AppendBytesThroughputPerProjectRegion |
| [BQ Quota (connections)](https://console.cloud.google.com/iam-admin/quotas?metric=bigquerystorage.googleapis.com/write/max_active_streams) | ConcurrentWriteConnectionsPerProjectRegion |

### Cloud Monitoring Metrics

Open [Metrics Explorer](https://console.cloud.google.com/monitoring/metrics-explorer) and search for:

| Metric | Aggregation | What It Shows |
| :--- | :--- | :--- |
| `bigquerystorage.googleapis.com/write/uploaded_bytes_count` | Sum, 1 min | BQ write throughput (bytes/sec) |
| `bigquerystorage.googleapis.com/dataflow_write/uploaded_bytes_count` | Sum, 1 min | Same, Dataflow-specific view |
| `bigquerystorage.googleapis.com/write/concurrent_connections` | Sum, 1 min | Active write streams |
| `pubsub.googleapis.com/subscription/num_unacked_messages_by_region` | -- | Subscription backlog |

### Monitoring API Queries

These `curl` commands pull metrics programmatically. Replace `PROJECT_ID` with your project.

**Pub/Sub backlog (both subscriptions, last 30 minutes):**

```bash
curl -s -H "Authorization: Bearer $(gcloud auth print-access-token)" \
  "https://monitoring.googleapis.com/v3/projects/PROJECT_ID/timeSeries?\
filter=metric.type%3D%22pubsub.googleapis.com%2Fsubscription%2Fnum_unacked_messages_by_region%22\
%20AND%20(resource.labels.subscription_id%3D%22perf_test_sub_a%22\
%20OR%20resource.labels.subscription_id%3D%22perf_test_sub_b%22)\
&interval.startTime=$(date -u -d '30 minutes ago' +%Y-%m-%dT%H:%M:%SZ)\
&interval.endTime=$(date -u +%Y-%m-%dT%H:%M:%SZ)\
&aggregation.alignmentPeriod=60s\
&aggregation.perSeriesAligner=ALIGN_MEAN"
```

**Pub/Sub backlog bytes (data volume per subscription, last 30 minutes):**

```bash
curl -s -H "Authorization: Bearer $(gcloud auth print-access-token)" \
  "https://monitoring.googleapis.com/v3/projects/PROJECT_ID/timeSeries?\
filter=metric.type%3D%22pubsub.googleapis.com%2Fsubscription%2Fbacklog_bytes%22\
%20AND%20(resource.labels.subscription_id%3D%22perf_test_sub_a%22\
%20OR%20resource.labels.subscription_id%3D%22perf_test_sub_b%22)\
&interval.startTime=$(date -u -d '30 minutes ago' +%Y-%m-%dT%H:%M:%SZ)\
&interval.endTime=$(date -u +%Y-%m-%dT%H:%M:%SZ)\
&aggregation.alignmentPeriod=60s\
&aggregation.perSeriesAligner=ALIGN_MEAN"
```

**BQ write throughput (last 30 minutes):**

```bash
curl -s -H "Authorization: Bearer $(gcloud auth print-access-token)" \
  "https://monitoring.googleapis.com/v3/projects/PROJECT_ID/timeSeries?\
filter=metric.type%3D%22bigquerystorage.googleapis.com%2Fwrite%2Fuploaded_bytes_count%22\
%20AND%20resource.labels.location%3D%22asia-southeast1%22\
&interval.startTime=$(date -u -d '30 minutes ago' +%Y-%m-%dT%H:%M:%SZ)\
&interval.endTime=$(date -u +%Y-%m-%dT%H:%M:%SZ)\
&aggregation.alignmentPeriod=60s\
&aggregation.perSeriesAligner=ALIGN_RATE"
```

**BQ concurrent connections (last 30 minutes):**

```bash
curl -s -H "Authorization: Bearer $(gcloud auth print-access-token)" \
  "https://monitoring.googleapis.com/v3/projects/PROJECT_ID/timeSeries?\
filter=metric.type%3D%22bigquerystorage.googleapis.com%2Fwrite%2Fconcurrent_connections%22\
&interval.startTime=$(date -u -d '30 minutes ago' +%Y-%m-%dT%H:%M:%SZ)\
&interval.endTime=$(date -u +%Y-%m-%dT%H:%M:%SZ)\
&aggregation.alignmentPeriod=60s\
&aggregation.perSeriesAligner=ALIGN_MEAN"
```

**BQ write timeline (per-table breakdown, BigQuery SQL):**

```sql
SELECT
  TIMESTAMP_TRUNC(start_timestamp, MINUTE) AS minute,
  SUM(total_input_bytes) AS bytes_written,
  ROUND(SUM(total_input_bytes) / 1000000, 1) AS mb_written,
  SUM(total_rows) AS rows_written
FROM
  `region-asia-southeast1`.INFORMATION_SCHEMA.WRITE_API_TIMELINE
WHERE
  table_id = 'taxi_events_perf'
  AND start_timestamp > TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 1 HOUR)
GROUP BY minute
ORDER BY minute;
```

## 7. Decision Matrix

| Combined Throughput | BQ Quota Usage | Connections | Diagnosis | Recommendation |
| :--- | :--- | :--- | :--- | :--- |
| ~200 MB/s (linear) | < 100% | < 1,000 | No contention | System works as expected |
| ~120 MB/s | ~100% | < 1,000 | **Throughput quota** | Request quota increase |
| ~120 MB/s | < 100% | ~1,000 | **Connection limit** | Use `STORAGE_API_AT_LEAST_ONCE` (default stream), request connection quota increase, or enable multiplexing (Java/Go only) |
| ~120 MB/s | < 100% | < 1,000 | **Per-table contention** | Write to separate tables, change partitioning, or use multi-region |

## 8. Cost Estimate

### Per Pub/Sub test run (Rounds 1-2: 36M messages, 10 KB, 2 consumers)

| Component | Cost |
| :--- | :--- |
| Pub/Sub publish (360 GB x 1) | ~$14.06 |
| Pub/Sub subscribe (360 GB x 2 subs) | ~$28.13 |
| Dataflow publisher (5 workers, ~10 min) | ~$0.33 |
| Dataflow consumer A (3 workers, ~60 min) | ~$1.20 |
| Dataflow consumer B (3 workers, ~60 min) | ~$1.20 |
| **Total** | **~$44.92** |

Each additional consumer adds ~$15 (Pub/Sub delivery + Dataflow workers).

### Per Pub/Sub test run (Rounds 3-5: 400M messages, 500 B / Avro, 2 consumers)

| Component | Cost |
| :--- | :--- |
| Pub/Sub publish (35-200 GB) | ~$1.40-$7.80 |
| Pub/Sub subscribe (35-200 GB x 2 subs) | ~$2.80-$15.60 |
| Dataflow publisher (5 workers, ~10 min) | ~$0.33 |
| Dataflow consumers (3 workers each, ~20-35 min) | ~$1.40-$2.40 |
| **Total** | **~$6-$26** |

### Per Kafka test run (Round 6: 400M messages, Avro, 2 consumer groups)

| Component | Cost |
| :--- | :--- |
| Managed Kafka cluster (3 vCPU, ~2 hours) | ~$1.50 |
| Dataflow publisher (3 workers, ~15 min) | ~$0.50 |
| Dataflow consumers (3 workers each, ~20 min) | ~$1.40 |
| **Total** | **~$3.40** |

### Pricing references

- Pub/Sub: $40/TiB (first 10 TiB/month) for both publish and subscribe
- Dataflow Streaming Engine: ~$0.069/vCPU-hr + $0.003557/GB-hr + $0.018/vCPU-hr (SE)
- BQ Storage Write API: included in BQ pricing (no separate charge for writes)

## 9. Reproducing the Test

### Quick start

```bash
# 1. Setup
./run_perf_test.sh setup

# 2. Publish (batch, auto-terminates)
./run_perf_test.sh publish
./run_perf_test.sh publish-status  # wait for JOB_STATE_DONE

# 3. Phase 1 -- single job baseline
./run_perf_test.sh job a
# Wait 10 minutes

# 4. Phase 2 -- noisy neighbor
./run_perf_test.sh job b
# Wait 10+ minutes, observe metrics

# 5. Monitor
./run_perf_test.sh monitor

# 6. Cleanup
./cleanup_perf_test.sh --force
```

### Configurable parameters

Edit the configuration section at the top of `run_perf_test.sh`:

| Parameter | Default | Description |
| :--- | :--- | :--- |
| `NUM_CONSUMERS` | `2` | Number of consumer subscriptions (a, b, c, ...) |
| `CONSUMER_NUM_WORKERS` | `3` | Workers per consumer job |
| `CONSUMER_MACHINE_TYPE` | `n2-standard-4` | Consumer worker machine type |
| `PUBLISHER_NUM_WORKERS` | `5` | Workers for the publisher job |
| `PUBLISHER_MACHINE_TYPE` | `n2-standard-4` | Publisher worker machine type |
| `NUM_MESSAGES` | `400000000` | Total messages (400M = ~35 GB Avro or ~200 GB JSON) |
| `MESSAGE_SIZE_BYTES` | `500` | Target size per message in bytes (JSON; Avro is schema-determined) |

### Scaling test rounds

| Round | SDK | Source | Msg Format | Msg Size | Workers/Job | Consumers | BQ Tables | Purpose | Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| 1 | Python | Pub/Sub | JSON | 10 KB | 3x n2-std-4 | 2 | 1 | Baseline -- per-table contention | Done |
| 2 | Python | Pub/Sub | JSON | 10 KB | 3x n2-std-4 | 3 | 1 | Throughput quota enforcement | Done |
| 3 | Python | Pub/Sub | JSON | 500 B | 3x n2-std-4 | 1 | 1 | Element rate + write method comparison | Done |
| 4 | Java | Pub/Sub | JSON | 500 B | 3x n2-hcpu-8 | 2 | 1 | Java SDK at production scale + 2-job scaling | Done |
| 5 | Java | Pub/Sub | Avro | 88 B | 3x n2-hcpu-8 | 2 | 4 | Avro + dynamic routing (production config) | Done |
| 6 | Java | **Kafka** | Avro | 88 B | 3x n2-hcpu-8 | 2 | 4 | Kafka source validation (production source) | Done |

**All six rounds complete. Dataflow, BigQuery, and the message source (Pub/Sub and Kafka) are definitively cleared as the bottleneck.**

- Rounds 1-2 proved no per-table contention and identified quota enforcement above ~314 MB/s.
- Round 3 showed Python SDK is CPU-bound at high element rates; write method is irrelevant.
- Round 4 used the same Java SDK, write method, and connection pooling as the production workload: 2 jobs achieved **757k rows/sec** (~425 MB/s) with **zero degradation** and only **25 connections**.
- Round 5 added Avro binary format and dynamic routing to 4 BQ tables: 2 jobs achieved **901k rows/sec** combined with zero degradation and 42 connections.
- Round 6 replaced Pub/Sub with Google Managed Kafka: 2 jobs with separate consumer groups achieved **812k rows/sec** combined with zero degradation and only 12 connections. **Kafka itself is not the root cause.**

See [Round 1](perf_test_results_round1.md), [Round 2](perf_test_results_round2.md), [Round 3](perf_test_results_round3.md), [Round 4](perf_test_results_round4.md), [Round 5](perf_test_results_round5.md), and [Round 6](perf_test_results_round6.md).

## 10. Kafka Source Validation (Round 6)

Round 6 replaced Pub/Sub with Google Managed Kafka as the message source while keeping everything else identical to Round 5. This was the final validation step recommended by Rounds 4-5.

### Architecture Comparison

| Component | Rounds 1-5 (Pub/Sub) | Round 6 (Kafka) |
| :--- | :--- | :--- |
| Publisher | Dataflow batch → `PubSubIO.write()` | Dataflow batch → `KafkaIO.write()` |
| Pipeline source | `PubSubIO.read(subscription=...)` | `KafkaIO.read(topics=..., consumerConfig=...)` |
| Consumer isolation | Separate subscriptions | Separate consumer groups |
| Message format | Avro binary (~88 bytes) | Avro binary (~88 bytes) |
| BQ write | `STORAGE_API_AT_LEAST_ONCE` + connection pooling | Identical |
| Dynamic routing | `ride_status` → 4 tables | Identical |
| Auth | N/A (Pub/Sub uses ADC) | SASL_SSL via `.withGCPApplicationDefaultCredentials()` |
| Network | Default | `--network=default --subnetwork=regions/asia-southeast1/subnetworks/default` |

### Kafka Cluster Configuration

| Parameter | Value |
| :--- | :--- |
| Cluster | Google Managed Service for Apache Kafka (`perf-test-kafka`) |
| vCPUs | 3 (minimum) |
| Memory | 3 GiB (minimum) |
| Region | asia-southeast1 |
| Network | Default VPC |
| Topic partitions | 32 |
| Consumer config | OOTB -- `group.id` and `auto.offset.reset=earliest` only |

### Results

| Metric | Round 5 (Pub/Sub) | Round 6 (Kafka) | Comparison |
| :--- | :--- | :--- | :--- |
| Single-job rows/sec | ~457k | ~401k | Kafka 88% of Pub/Sub |
| Combined rows/sec (2 jobs) | ~901k | ~812k | Kafka 90% of Pub/Sub |
| Per-job degradation | None | None | Both linear scaling |
| BQ connections (2 jobs) | 42 | 12 | Kafka uses fewer |
| CPU utilization | ~99% | ~80% | Kafka has more I/O wait |
| DLQ rows | 0 | 0 | Zero errors |

**Kafka with OOTB config delivers comparable throughput to Pub/Sub with zero noisy neighbor effect.** The 10-12% lower throughput vs Pub/Sub is likely due to KafkaIO overhead (network round-trips to brokers, SASL_SSL handshake), not a fundamental limitation. The lower CPU utilization confirms this: workers have idle CPU headroom, suggesting the bottleneck is I/O wait on Kafka fetches rather than processing capacity.

### Key Discovery

The critical difference between this test and production is **consumer group configuration**. This test uses separate consumer groups (`perf-a2`, `perf-b2`) so each job reads all 400M messages independently. If production jobs share the same consumer group, Kafka splits partitions between them -- each job gets half the data, not a copy. This exactly explains the observed 200k → 100k per-job degradation pattern.

## 11. Conclusion

The original question -- "Is this BQ per-table contention, quota limit, or connection limit?" -- is answered:

**None of the above. Dataflow, BigQuery, and the message source are not the bottleneck.**

| Hypothesis | Result |
| :--- | :--- |
| Per-table contention | Disproven. 2 jobs writing to the same tables scale linearly with no per-job degradation (Rounds 1, 4, 5, 6). |
| Connection limit | Not reached. Peak 42 connections with pooling and 4 dynamic tables (4.2% of 1,000 limit, Round 5). |
| Throughput quota | Only triggered above ~314 MB/s via sawtooth throttling (Round 2). Production workload (~200 MB/s) is well below this limit. |
| Write method | No effect. Exactly-once and at-least-once produce identical throughput (Round 3). |
| Element rate | Not a BQ limitation. 901k rows/sec sustained with zero throttling (Round 5). |
| Pub/Sub vs Kafka source | No effect. Both sources show zero per-job degradation with separate consumer groups/subscriptions (Rounds 5, 6). |
| KafkaIO connector | Not a bottleneck. ~401k rows/sec per job with OOTB Kafka config (Round 6). |
| Dynamic table routing | Not a bottleneck. 4 dynamic destination tables add no measurable overhead (Rounds 5, 6). |

### Key Results Across All Rounds

| Round | SDK | Source | Single Job | 2 Jobs Combined | Per-Job Degradation | Connections |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| 1 | Python | Pub/Sub | 160 MB/s, 16k RPS | 330 MB/s, 33k RPS | None | 120 |
| 2 | Python | Pub/Sub | 161 MB/s, 16k RPS | 328 MB/s (2-job tail) | None | 120 |
| 3 | Python | Pub/Sub | 31 MB/s, 55k RPS | N/A (CPU-bound) | N/A | N/A |
| 4 | Java | Pub/Sub | 190 MB/s, 340k RPS | 393 MB/s, 700k RPS | None | 25 |
| 5 | Java | Pub/Sub | 59 MB/s, 457k RPS | 114 MB/s, 901k RPS | None | 42 |
| **6** | **Java** | **Kafka** | **46 MB/s, 401k RPS** | **91 MB/s, 812k RPS** | **None** | **12** |

Round 6 is the conclusive validation. Using the **same message source (Google Managed Kafka), same message format (Avro binary), same dynamic routing (4 tables), same write method (`STORAGE_API_AT_LEAST_ONCE`), same connection pooling, same SDK (Java), same region (`asia-southeast1`), and same message size (~88 bytes)** as the production workload, 2 Dataflow jobs with **separate consumer groups** achieved 812k rows/sec combined -- **4.1x the production target of 200k rows/sec** -- with zero per-job degradation. The production noisy neighbor scenario (single job at 200k rows/sec dropping to 100k when a second job starts) cannot be reproduced with separate consumer groups.

### Secondary Findings

1. **Quota enforcement mechanism (Round 2).** When combined demand exceeds ~314 MB/s, BigQuery enforces the regional throughput quota via periodic sawtooth throttling (burst at ~550 MB/s for 5-7 min, throttle to ~20 MB/s for 2-4 min). This is relevant for future scaling beyond 500k RPS but does not explain the current production issue.

2. **Connection pooling effectiveness (Rounds 4-6).** Enabling `useStorageApiConnectionPool=true` reduced connections from ~60 per job (without pooling) to ~6-21 per job (with pooling) -- a ~3-10x reduction. This is essential for pipelines with dynamic multi-table routing. Round 6 (Kafka, 4 tables) used only 6 connections per job.

3. **Python vs Java SDK performance (Round 3 vs 4).** Java achieves ~6x the rows/sec of Python at the same message size (~340k vs ~55k rows/sec), but uses 2x the vCPUs (24 vs 12), yielding ~3x better per-vCPU efficiency. Both SDKs are CPU-near-capacity at these element rates: Python at 100% on 12 vCPUs, Java at 75-90% on 24 vCPUs. The difference reflects JIT compilation, true multi-threading, and native protobuf serialization.

4. **Avro throughput expansion (Round 5).** An ~88-byte Avro message expands to ~461 bytes at the BQ PrepareWrite step (5.2x) before being serialized to ~195 bytes in the Storage API protobuf format (2.2x). The Dataflow dashboard shows apparent sink throughput 3-5x higher than source throughput, which is expected behavior, not a performance issue.

5. **Kafka vs Pub/Sub throughput (Round 6).** Kafka with OOTB config delivers ~88% of Pub/Sub per-job throughput (~401k vs ~457k rows/sec). The difference is likely due to KafkaIO network overhead (SASL_SSL, fetch round-trips). Workers at ~80% CPU (vs ~99% with Pub/Sub) confirms the bottleneck is I/O wait, not processing. Scaling up the Kafka cluster or tuning fetch parameters would likely close this gap.

6. **Dynamic routing adds no measurable overhead (Rounds 5-6).** Writing to 4 dynamically-selected BQ tables instead of 1 fixed table produced no throughput degradation. Connection pooling keeps total connections low (12-42 for 2 jobs writing to 4 tables).

### Recommendations

1. **Verify consumer group configuration in production (highest priority).** Round 6 proved that Kafka with separate consumer groups shows zero degradation. If production Dataflow jobs share the same `group.id`, Kafka splits partitions between them -- each job gets half the data, not a copy. This exactly explains the 200k → 100k per-job pattern. **Each job must have a unique `group.id`.**

2. **Verify per-job Kafka consumption rate.** Monitor `dataflow.googleapis.com/job/elements_produced` on the ReadFromKafka step to confirm whether each job reads the full topic volume (separate consumer groups) or half the volume (shared consumer group) when 2 jobs run concurrently.

3. **If consumer groups are already separate**, profile CPU utilization. Round 6 achieved ~80% CPU with ~401k rows/sec per job. If production CPU is significantly lower with lower throughput, the bottleneck is in Kafka fetch configuration (`fetch.max.bytes`, `max.partition.fetch.bytes`, `max.poll.records`), network latency, or partition count.

4. **Check partition count.** With 3x n2-highcpu-8 workers (24 vCPUs), at least 24 partitions are recommended; 48 is optimal for Runner v2. Round 6 used 32 partitions and achieved full throughput.

5. **Test with 40 destination tables.** If the consumer group hypothesis is ruled out, reproduce the test with 40 dynamic destination tables to match production exactly. Rounds 5-6 used 4 tables with no overhead, but 40 tables may produce more connection churn or BQ stream management overhead.

6. **Monitor quota usage** with alerts on `bigquerystorage.googleapis.com/write/append_bytes_region` as a best practice for capacity planning.

7. **Quota increase is not needed** at current production volumes. The regional quota of ~314 MB/s accommodates the target throughput. Round 6 used only ~91 MB/s (29% of quota) at 812k combined rows/sec.

## References

- [BigQuery Storage Write API best practices](https://cloud.google.com/bigquery/docs/write-api-best-practices)
- [Pub/Sub to BigQuery best practices](https://cloud.google.com/dataflow/docs/guides/pubsub-bigquery-best-practices)
- [Pub/Sub to BigQuery performance benchmarks](https://cloud.google.com/dataflow/docs/guides/pubsub-bigquery-performance)
- [Write from Dataflow to BigQuery](https://cloud.google.com/dataflow/docs/guides/write-to-bigquery)
- [BigQuery Storage Write API quotas](https://cloud.google.com/bigquery/quotas#write-api-limits)
- [Google Managed Service for Apache Kafka](https://cloud.google.com/managed-service-for-apache-kafka/docs/overview)
- [KafkaIO (Apache Beam)](https://beam.apache.org/releases/javadoc/current/org/apache/beam/sdk/io/kafka/KafkaIO.html)
- [Kafka to BigQuery with Dataflow](https://cloud.google.com/dataflow/docs/guides/kafka-to-bigquery)
