# Performance Test Results -- Round 6 (Kafka Source, Avro Binary, Dynamic Table Routing)

Date: 2026-02-17

## Test Objective

Rounds 1-5 used Pub/Sub as the message source and conclusively proved that BigQuery and Dataflow are not the bottleneck. All five rounds recommended investigating the Kafka source path as the likely cause of the production "noisy neighbor" degradation. Round 6 is the validation test: replace Pub/Sub with Google Managed Kafka as the source while keeping everything else identical to round 5.

Specifically, round 6 tests whether:

1. Replacing Pub/Sub with KafkaIO as the source introduces the per-job throughput degradation observed in production
2. Two Dataflow jobs with **separate consumer groups** (each reading all 400M messages independently) can sustain linear scaling when reading from Kafka
3. The OOTB Kafka configuration (no performance tuning) is sufficient for the production throughput target

## Test Environment

| Parameter | Value |
|:---|:---|
| Project ID | `johanesa-playground-326616` |
| Region | `asia-southeast1` |
| Dataset | `demo_dataset_asia` |
| BQ Tables | `taxi_events_perf_enroute`, `_pickup`, `_dropoff`, `_waiting` |
| BQ DLQ Table | `taxi_events_perf_dlq` |
| **Kafka Cluster** | `perf-test-kafka` (Google Managed Kafka) |
| **Kafka Topic** | `perf-test-taxi-avro` |
| **Kafka Partitions** | 32 |
| **Consumer Groups** | `perf-a2`, `perf-b2` (separate, each reads all messages) |
| Messages per consumer group | 400,000,000 |
| Message format | Avro binary (schema-determined size) |
| Message size | ~88 bytes (9-field taxi ride, no padding) |
| Total data per consumer group | ~35 GB Avro |
| Total data (both consumer groups) | ~70 GB (2 x 35 GB) |

### Kafka Cluster Configuration

| Parameter | Value |
|:---|:---|
| Cluster type | Google Managed Service for Apache Kafka |
| vCPUs | 3 |
| Memory | 3 GiB |
| Region | `asia-southeast1` |
| Network | Default VPC, `default` subnet |
| Bootstrap | `bootstrap.perf-test-kafka.asia-southeast1.managedkafka.johanesa-playground-326616.cloud.goog:9092` |
| Auth | SASL_SSL via `.withGCPApplicationDefaultCredentials()` |
| Partitions | 32 |
| Replication factor | 3 |

### Partition Distribution

Messages were published using index-derived keys (`ByteBuffer.putInt(i)`) to ensure even distribution. Verified via Cloud Monitoring `managedkafka.googleapis.com/last_offset`:

| Metric | Value |
|:---|:---|
| Min per partition | 12,426,300 |
| Max per partition | 12,571,500 |
| Skew ratio (max/min) | 1.01x |

### Sample Row (as stored in BigQuery)

Same schema as round 5 (14 columns), with `subscription_name` mapping to the Kafka consumer group name:

```json
{
  "subscription_name": "perf-a2",
  "message_id": "5:100",
  "publish_time": "2026-02-17 03:33:38",
  "processing_time": "2026-02-17 03:34:01",
  "ride_id": "ride-541660",
  "point_idx": 1509,
  "latitude": 1.228165,
  "longitude": 103.992006,
  "timestamp": "2026-02-08 08:24:35",
  "meter_reading": 75.77,
  "meter_increment": 0.0247,
  "ride_status": "enroute",
  "passenger_count": 2,
  "attributes": "{}"
}
```

**Metadata mapping differences from Pub/Sub (round 5):**

| Field | Round 5 (Pub/Sub) | Round 6 (Kafka) |
|:---|:---|:---|
| `subscription_name` | Pub/Sub subscription name | Kafka consumer group name |
| `message_id` | Pub/Sub message ID | `partition:offset` |
| `publish_time` | Pub/Sub publish timestamp | Kafka record timestamp |
| `attributes` | Pub/Sub message attributes | `{}` (Kafka headers not used) |

### Dataflow Jobs

| Job | Job ID | Name | State |
|:---|:---|:---|:---|
| Publisher (batch, Java) | `2026-02-16_18_49_11-11142464629999145081` | `dataflow-perf-kafka-publisher-20260217-104433` | Done |
| Consumer A (streaming, Java) | `2026-02-16_19_31_07-5736935440472300995` | `dataflow-perf-kafka-avro-a-20260217-113055` | Cancelled |
| Consumer B (streaming, Java) | `2026-02-16_19_37_18-9095889172267237892` | `dataflow-perf-kafka-avro-b-20260217-113707` | Cancelled |

### Consumer Pipeline Configuration

| Parameter | Value |
|:---|:---|
| SDK | **Java** (Apache Beam 2.70.0, Java 17) |
| Workers per job | 3 |
| Machine type | `n2-highcpu-8` (8 vCPUs, 8 GB RAM) |
| Total vCPUs per job | 24 |
| Pipeline | `KafkaToBigQueryAvro.java` (Kafka Avro binary to typed BQ columns) |
| BQ write method | `STORAGE_API_AT_LEAST_ONCE` (default stream) |
| Connection pooling | `--useStorageApiConnectionPool=true` (multiplexing enabled) |
| Streaming Engine | Enabled |
| Dataflow streaming mode | Exactly-once (default) |
| Runner | Dataflow Runner V2 |
| Dynamic routing | `ride_status` field --> 4 BQ tables |
| KafkaIO connector | `KafkaIO.read()` (not Managed IO) |
| Kafka auth | `.withGCPApplicationDefaultCredentials()` |
| Kafka consumer config | OOTB: `group.id`, `auto.offset.reset=earliest` only |
| Network | `--network=default --subnetwork=regions/asia-southeast1/subnetworks/default` |

**Key differences from round 5:**

| Parameter | Round 5 (Pub/Sub) | Round 6 (Kafka) |
|:---|:---|:---|
| Source | Pub/Sub | Google Managed Kafka |
| Connector | `PubSubIO.read()` | `KafkaIO.read()` |
| Consumer isolation | Separate subscriptions | Separate consumer groups |
| Pipeline class | `PubSubToBigQueryAvro` | `KafkaToBigQueryAvro` |
| DoFn | `PubsubAvroToTableRow` | `KafkaAvroToTableRow` |
| Network config | Not specified | `--network=default --subnetwork=...` |

## Timeline Summary

| Phase | Time (UTC) | Duration | Description |
|:---|:---|:---|:---|
| Job A ramp-up | 03:33 - 03:35 | 3 min | Job A starts writing, ramp from 98k to 414k RPS |
| Job A steady state | 03:36 - 03:39 | 4 min | Job A alone at ~400k RPS, ~46 MB/s |
| Job B ramp-up | 03:39 - 03:41 | 2 min | Job B starts writing, combined ramps to 787k RPS |
| Both jobs steady state | 03:42 - 03:45 | 4 min | Combined ~788-812k RPS, ~90-93 MB/s |
| Job A draining | 03:46 - 03:49 | 4 min | Job A Kafka backlog running low, throughput declining |
| Job B solo | 03:49 - 03:53 | 5 min | Job A near empty, Job B continues at ~400k RPS |

## Raw Data

### BQ Write Throughput

**Query:**

```bash
bq query --use_legacy_sql=false --location=asia-southeast1 --format=pretty '
SELECT
  TIMESTAMP_TRUNC(start_timestamp, MINUTE) AS minute,
  ROUND(SUM(total_input_bytes) / 1000000, 1) AS combined_mbpm,
  ROUND(SUM(total_input_bytes) / 1000000 / 60, 2) AS combined_mbps,
  SUM(total_rows) AS combined_rpm,
  ROUND(SUM(total_rows) / 60, 0) AS combined_rps,
  COUNT(DISTINCT table_id) AS num_tables
FROM
  `region-asia-southeast1`.INFORMATION_SCHEMA.WRITE_API_TIMELINE
WHERE
  table_id LIKE "taxi_events_perf_%"
  AND table_id NOT LIKE "%round%"
  AND table_id NOT LIKE "%dlq%"
  AND start_timestamp > "2026-02-17T03:30:00Z"
  AND start_timestamp < "2026-02-17T04:00:00Z"
GROUP BY minute
ORDER BY minute'
```

**Results:**

| Minute (UTC) | MB/min | MB/s | Rows | Rows/sec | Phase |
|:---|---:|---:|---:|---:|:---|
| 03:33 | 663.7 | 11.06 | 5,888,033 | 98,134 | Job A ramp-up |
| 03:34 | 2,802.1 | 46.70 | 24,701,558 | 411,693 | Job A ramp-up |
| 03:35 | 2,832.5 | 47.21 | 24,809,627 | 413,494 | Job A ramp-up |
| 03:36 | 2,745.6 | 45.76 | 24,013,558 | 400,226 | Job A only |
| 03:37 | 2,758.8 | 45.98 | 24,127,854 | 402,131 | Job A only |
| 03:38 | 2,786.9 | 46.45 | 24,374,521 | 406,242 | Job A only |
| 03:39 | 2,711.4 | 45.19 | 23,713,592 | 395,227 | Job A only |
| 03:40 | 5,181.7 | 86.36 | 45,537,251 | 758,954 | Job B ramp-up |
| 03:41 | 5,391.5 | 89.86 | 47,247,953 | 787,466 | Job B ramp-up |
| 03:42 | 5,475.2 | 91.25 | 47,886,591 | 798,110 | Both jobs |
| 03:43 | 5,571.8 | 92.86 | 48,725,783 | 812,096 | Both jobs (peak) |
| 03:44 | 5,409.8 | 90.16 | 47,244,637 | 787,411 | Both jobs |
| 03:45 | 5,417.1 | 90.28 | 47,301,857 | 788,364 | Both jobs |
| 03:46 | 4,826.4 | 80.44 | 42,114,607 | 701,910 | Job A draining |
| 03:47 | 4,530.8 | 75.51 | 39,545,497 | 659,092 | Job A draining |
| 03:48 | 4,473.5 | 74.56 | 39,044,431 | 650,741 | Job A draining |
| 03:49 | 3,809.1 | 63.48 | 33,287,035 | 554,784 | Job B solo |
| 03:50 | 3,691.1 | 61.52 | 32,195,833 | 536,597 | Job B solo |
| 03:51 | 3,478.4 | 57.97 | 30,307,202 | 505,120 | Job B solo |
| 03:52 | 3,232.6 | 53.88 | 28,145,839 | 469,097 | Job B draining |
| 03:53 | 2,757.5 | 45.96 | 23,967,989 | 399,466 | Job B draining |

**Per-table throughput at peak (03:43 UTC):**

| Table | MB/s | Rows/sec |
|:---|---:|---:|
| dropoff | 23.11 | 201,687 |
| enroute | 23.00 | 200,658 |
| pickup | 23.31 | 205,150 |
| waiting | 23.45 | 204,601 |
| **Combined** | **92.86** | **812,096** |

### Per-Consumer-Group Throughput

**Query:**

```bash
bq query --use_legacy_sql=false --location=asia-southeast1 --format=pretty '
SELECT
  TIMESTAMP_TRUNC(processing_time, MINUTE) AS minute,
  subscription_name AS consumer_group,
  COUNT(*) AS rows_written,
  ROUND(COUNT(*) / 60, 0) AS rps
FROM (
  SELECT subscription_name, processing_time FROM `demo_dataset_asia.taxi_events_perf_enroute` WHERE subscription_name LIKE "perf-%2"
  UNION ALL
  SELECT subscription_name, processing_time FROM `demo_dataset_asia.taxi_events_perf_pickup` WHERE subscription_name LIKE "perf-%2"
  UNION ALL
  SELECT subscription_name, processing_time FROM `demo_dataset_asia.taxi_events_perf_dropoff` WHERE subscription_name LIKE "perf-%2"
  UNION ALL
  SELECT subscription_name, processing_time FROM `demo_dataset_asia.taxi_events_perf_waiting` WHERE subscription_name LIKE "perf-%2"
)
GROUP BY minute, consumer_group
ORDER BY minute, consumer_group'
```

**Results (per-job rows/sec):**

| Minute (UTC) | Job A (perf-a2) | Job B (perf-b2) | Combined | Phase |
|:---|---:|---:|---:|:---|
| 03:33 | 103,363 | -- | 103,363 | Job A ramp-up |
| 03:34 | 409,323 | -- | 409,323 | Job A ramp-up |
| 03:35 | 414,727 | -- | 414,727 | Job A ramp-up |
| 03:36 | 399,774 | -- | 399,774 | Job A only |
| 03:37 | 401,441 | -- | 401,441 | Job A only |
| 03:38 | 405,614 | -- | 405,614 | Job A only |
| 03:39 | 397,917 | 3,421 | 401,338 | Job B starting |
| 03:40 | **402,205** | 357,665 | 759,870 | Job B ramp-up |
| 03:41 | **396,234** | 389,927 | 786,161 | Both jobs |
| 03:42 | **405,006** | 393,600 | 798,606 | Both jobs |
| 03:43 | **404,819** | 406,531 | 811,350 | Both jobs (peak) |
| 03:44 | **396,074** | 390,680 | 786,754 | Both jobs |
| 03:45 | **399,180** | 389,610 | 788,790 | Both jobs |
| 03:46 | 321,931 | 377,533 | 699,464 | Job A draining |
| 03:47 | 261,340 | 398,341 | 659,681 | Job A draining |
| 03:48 | 254,241 | 397,407 | 651,648 | Job A draining |
| 03:49 | 154,569 | 400,201 | 554,770 | Job A near empty |
| 03:50 | 132,890 | 404,192 | 537,082 | Job B solo |
| 03:51 | 125,253 | 378,285 | 503,538 | Job B solo |
| 03:52 | 133,288 | 332,328 | 465,616 | Job B draining |
| 03:53 | 138,422 | 262,116 | 400,538 | Job B draining |

### BQ Concurrent Connections

**Query:**

```bash
curl -s -H "Authorization: Bearer $(gcloud auth print-access-token)" \
  "https://monitoring.googleapis.com/v3/projects/johanesa-playground-326616/timeSeries?\
filter=metric.type%3D%22bigquerystorage.googleapis.com%2Fwrite%2Fconcurrent_connections%22\
&interval.startTime=2026-02-17T03:30:00Z\
&interval.endTime=2026-02-17T04:00:00Z\
&aggregation.alignmentPeriod=120s\
&aggregation.perSeriesAligner=ALIGN_MEAN"
```

**Results (2-minute intervals):**

| Time (UTC) | Connections | Phase |
|:---|---:|:---|
| 03:34 | 3 | Job A starting |
| 03:36 - 03:40 | 6 | Job A only |
| 03:42 - 03:50 | 12 | Both jobs |

### CPU Utilization

**Query:**

```bash
curl -s -H "Authorization: Bearer $(gcloud auth print-access-token)" \
  "https://monitoring.googleapis.com/v3/projects/johanesa-playground-326616/timeSeries?\
filter=metric.type%3D%22compute.googleapis.com%2Finstance%2Fcpu%2Futilization%22\
%20AND%20metadata.user_labels.%22dataflow_job_name%22%3D%22<JOB_NAME>%22\
&interval.startTime=2026-02-17T03:30:00Z\
&interval.endTime=2026-02-17T04:00:00Z\
&aggregation.alignmentPeriod=60s\
&aggregation.perSeriesAligner=ALIGN_MEAN\
&aggregation.crossSeriesReducer=REDUCE_MEAN\
&aggregation.groupByFields=metadata.user_labels.dataflow_job_name"
```

**Job A (average across 3 workers):**

| Time (UTC) | CPU % | Phase |
|:---|---:|:---|
| 03:32 | 0.7% | Startup |
| 03:33 | 6.4% | Ramp-up |
| 03:34 | 33.5% | Ramp-up |
| 03:35 | 79.9% | Ramp-up |
| 03:36 | 82.1% | Steady (A only) |
| 03:37 | 80.4% | Steady (A only) |
| 03:38 | 81.2% | Steady (A only) |
| 03:39 | 80.7% | Both starting |
| 03:40 | 79.7% | Both steady |
| 03:41 | 80.3% | Both steady |
| 03:42 | 79.9% | Both steady |
| 03:43 | 80.5% | Both steady |
| 03:44 | 79.0% | Both steady |
| 03:45 | 78.3% | Both steady |
| 03:46 | 78.3% | Draining |
| 03:47 | 66.7% | Draining |
| 03:48 | 54.0% | Near empty |
| 03:49 | 49.3% | Near empty |
| 03:50 | 35.8% | Near empty |

**Job B (average across 3 workers):**

| Time (UTC) | CPU % | Phase |
|:---|---:|:---|
| 03:39 | 5.6% | Startup |
| 03:40 | 20.2% | Ramp-up |
| 03:41 | 70.3% | Ramp-up |
| 03:42 | 79.4% | Steady |
| 03:43 | 79.6% | Steady |
| 03:44 | 81.9% | Steady |
| 03:45 | 81.0% | Steady |
| 03:46 | 80.5% | Steady |
| 03:47 | 79.5% | Steady |
| 03:48 | 80.6% | Steady |
| 03:49 | 81.6% | Steady (solo) |
| 03:50 | 81.6% | Steady (solo) |
| 03:51 | 79.9% | Steady (solo) |
| 03:52 | 78.5% | Draining |
| 03:53 | 69.7% | Draining |

### Total Rows Verification

**Query:**

```bash
bq query --use_legacy_sql=false --location=asia-southeast1 '
SELECT
  subscription_name AS consumer_group,
  COUNT(*) AS total_rows,
  MIN(processing_time) AS first_processed,
  MAX(processing_time) AS last_processed,
  TIMESTAMP_DIFF(MAX(processing_time), MIN(processing_time), SECOND) AS duration_sec
FROM (
  SELECT subscription_name, processing_time FROM `demo_dataset_asia.taxi_events_perf_enroute` WHERE subscription_name LIKE "perf-%2"
  UNION ALL
  SELECT subscription_name, processing_time FROM `demo_dataset_asia.taxi_events_perf_pickup` WHERE subscription_name LIKE "perf-%2"
  UNION ALL
  SELECT subscription_name, processing_time FROM `demo_dataset_asia.taxi_events_perf_dropoff` WHERE subscription_name LIKE "perf-%2"
  UNION ALL
  SELECT subscription_name, processing_time FROM `demo_dataset_asia.taxi_events_perf_waiting` WHERE subscription_name LIKE "perf-%2"
)
GROUP BY subscription_name
ORDER BY consumer_group'
```

**Results:**

| Consumer Group | Total Rows | First Processed | Last Processed | Duration |
|:---|---:|:---|:---|:---|
| perf-a2 | 388,987,890 | 2026-02-17 03:33:38 | 2026-02-17 03:54:10 | 20.5 min |
| perf-b2 | 325,519,826 | 2026-02-17 03:39:57 | 2026-02-17 03:54:33 | 14.6 min |

Jobs were cancelled before fully draining all 400M messages. Job A consumed 97.2% (389M of 400M), Job B consumed 81.4% (326M of 400M). Sufficient data for analysis -- both jobs ran at steady state concurrently for 6+ minutes.

**Per-table row counts (including data from rounds 5 and 6):**

| Table | Row Count | Size (MB) | Bytes/Row |
|:---|---:|---:|---:|
| taxi_events_perf_dlq | 0 | 0.0 | -- |
| taxi_events_perf_dropoff | 374,446,191 | 45,299.5 | 126.9 |
| taxi_events_perf_enroute | 373,602,956 | 45,193.6 | 126.8 |
| taxi_events_perf_pickup | 379,142,879 | 45,476.6 | 125.8 |
| taxi_events_perf_waiting | 378,457,938 | 45,788.6 | 126.9 |

- **DLQ is empty** -- zero Avro parse failures from Kafka messages.
- **Even distribution** -- each `ride_status` value receives ~25% of messages, confirming dynamic routing works correctly with Kafka source.

### Dataflow Metrics Summary

**Job A Results (cumulative totals):**

| Metric | Value |
|:---|---:|
| ElementCount | 388,987,890 |
| MeanByteCount (Kafka source) | 130 bytes |
| MeanByteCount (DecodeAvroAndRoute) | 389 bytes |
| MeanByteCount (PrepareWrite) | 461 bytes |
| MeanByteCount (Convert to message) | 195 bytes |

**Throughput expansion chain (Job A):**

```
Kafka record:        130 bytes/element  (Avro payload + Kafka metadata)
   | Avro decode + TableRow construction
KV<String,TableRow>: 389 bytes/element  (3.0x expansion)
   | PrepareWrite (adds BQ metadata)
BQ write element:    461 bytes/element  (3.5x vs source)
   | Protobuf serialization
Storage API proto:   195 bytes/element  (1.5x vs source)
   | Written to BQ storage
BQ stored row:       127 bytes/row      (1.0x vs source)
```

## Analysis

### Phase 1: Job A Alone (03:36 - 03:39)

- Steady-state throughput: **395-406k rows/sec** (~401k average)
- Steady-state BQ write rate: **45.2-46.5 MB/s** (~46 MB/s average)
- Per-table rows/sec: **~100k** per table (4 tables)
- BQ connections: **6** (connection pooling via multiplexing)
- CPU utilization: **80-82%** average across workers
- Row size: ~127 bytes/row in BQ storage

### Phase 2: Both Jobs Running (03:42 - 03:45)

- Combined throughput: **787-812k rows/sec** (~797k average)
- Combined BQ write rate: **90.2-92.9 MB/s** (~91 MB/s average)
- Peak throughput: **812k rows/sec** at 03:43 (**93 MB/s**)
- Per-table rows/sec: **~201-205k** per table (4 tables)
- BQ connections: **12** (6 for Job A + 6 for Job B, with multiplexing)
- **Job A per-job throughput: 396-405k rows/sec** -- no degradation vs solo phase
- **Job B per-job throughput: 390-407k rows/sec** -- symmetric to Job A
- CPU utilization: **79-81%** Job A, **79-82%** Job B (both identical to solo phase)
- Both jobs draining Kafka concurrently at comparable rates

### Phase 3: Job A Draining (03:46 - 03:48)

- Job A throughput declining as Kafka backlog approaches zero (started 6 min earlier)
- Job B continues at full speed: **~398k rows/sec** (unaffected by Job A's decline)
- Job B CPU remains at 80% while Job A CPU drops from 78% to 54%

### Key Findings

1. **No noisy neighbor effect with Kafka source.** Adding Job B did not degrade Job A's throughput. Job A maintained ~400k rows/sec both before and after Job B started. Combined throughput approximately doubled from ~401k to ~812k rows/sec, confirming linear scaling. **The production noisy neighbor degradation is not caused by the Kafka source itself.**

2. **812k rows/sec with zero degradation.** Peak combined rate of 812,096 rows/sec from 2 jobs reading from Kafka and writing to the same 4 BQ tables. This is **4.1x the production target** of 200k rows/sec.

3. **Kafka with OOTB config delivers equivalent throughput to Pub/Sub.** Single-job throughput of ~401k rows/sec with Kafka is comparable to round 5's ~457k rows/sec with Pub/Sub (~88% of Pub/Sub rate). The slight difference is likely due to KafkaIO overhead vs PubSubIO, not a fundamental limitation.

4. **Lower CPU utilization than Pub/Sub.** Jobs ran at ~80% CPU vs round 5's ~99% CPU. This suggests KafkaIO's read path has more I/O wait time (network round-trips to Kafka brokers), leaving CPU headroom. In production, this would allow higher throughput with the same workers if the Kafka cluster is scaled up.

5. **Minimal connection usage.** 2 jobs with 4 dynamic destination tables used only **12 connections** (1.2% of the 1,000 regional limit). This is even lower than round 5's 42 connections, likely because the lower throughput results in fewer active write streams.

6. **32 partitions provide sufficient parallelism.** With 3 workers x 8 vCPUs = 24 vCPUs per job, 32 partitions (above the recommended 4x max_workers = 12) provided sufficient parallelism for ~400k rows/sec per job. The Dataflow docs recommend 2x vCPUs = 48 partitions for Runner v2, but 32 was adequate for this workload.

### Comparison Across All Rounds

| Metric | Round 1 | Round 2 | Round 3 | Round 4 | Round 5 | **Round 6** |
|:---|:---|:---|:---|:---|:---|:---|
| SDK | Python | Python | Python | Java | Java | **Java** |
| Source | Pub/Sub | Pub/Sub | Pub/Sub | Pub/Sub | Pub/Sub | **Kafka** |
| Message size | 10 KB | 10 KB | 500 bytes | 500 bytes | 88 bytes | **88 bytes** |
| Message format | JSON | JSON | JSON | JSON | Avro | **Avro** |
| BQ destinations | 1 table | 1 table | 1 table | 1 table | 4 tables | **4 tables** |
| BQ write method | Exactly-once | Exactly-once | Both | At-least-once | At-least-once | **At-least-once** |
| Connection pooling | No | No | No | Yes | Yes | **Yes** |
| Workers/job | 3x n2-std-4 | 3x n2-std-4 | 3x n2-std-4 | 3x n2-hcpu-8 | 3x n2-hcpu-8 | **3x n2-hcpu-8** |
| Single-job MB/s (BQ) | ~160 | ~161 | ~31 | ~190 | ~59 | **~46** |
| Single-job rows/sec | ~16k | ~16k | ~55k | ~340k | ~457k | **~401k** |
| Combined MB/s (2 jobs) | ~330 | ~328 | N/A | ~393 | ~114 | **~91** |
| Combined rows/sec | ~33k | ~33k | N/A | ~700k | ~901k | **~812k** |
| Peak rows/sec | ~39k | ~58k | ~59k | 757k | 901k | **812k** |
| Per-job degradation | None | None | N/A | None | None | **None** |
| Connections (2 jobs) | 120 | 120 | N/A | 25 | 42 | **12** |
| CPU at peak | ~25% | ~25% | 100% | 75-90% | ~99% | **~80%** |
| DLQ rows | 0 | 0 | 0 | 0 | 0 | **0** |

### Comparison with Production

| Metric | Round 6 (This Test) | Production Workload | Gap |
|:---|:---|:---|:---|
| SDK | Java 17, Beam 2.70.0 | Java, Beam (version varies) | Same stack |
| Region | asia-southeast1 | asia-southeast1 | Same |
| Source | **Google Managed Kafka** | Google Managed Kafka | **Same** |
| Connector | KafkaIO | KafkaIO | Same |
| Write method | `STORAGE_API_AT_LEAST_ONCE` | `STORAGE_API_AT_LEAST_ONCE` | Same |
| Connection pooling | Enabled | Enabled | Same |
| Message format | Avro binary (~88 bytes) | Avro binary (similar) | Same |
| BQ destinations | 4 tables (dynamic) | 40 tables (dynamic) | 10x more tables in prod |
| Consumer groups | Separate (`perf-a2`, `perf-b2`) | Unknown | **Verify in production** |
| Kafka partitions | 32 | Unknown | Verify in production |
| Kafka config | OOTB (no tuning) | Unknown | Verify in production |
| Single-job rows/sec | **~401,000** | ~200,000 | **2.0x higher in test** |
| 2-job per-job rows/sec | **~400,000** (no degradation) | ~100,000 (50% degradation) | **4.0x higher in test** |
| 2-job combined rows/sec | **~812,000** | ~200,000 | **4.1x higher in test** |
| Connections (2 jobs) | 12 | ~100 | Both well under limit |
| CPU utilization | ~80% | Low (per production team) | Test has higher CPU |

## Diagnosis

**The noisy neighbor degradation cannot be reproduced with Kafka as the source, using separate consumer groups.**

Round 6 is the conclusive validation of the recommendation from rounds 4-5. Using the **same source technology (Google Managed Kafka)**, same message format (Avro binary), same dynamic routing (4 tables), same write method, same connection pooling, same SDK, and same region as the production workload, two Dataflow jobs achieved **812k rows/sec combined** with **zero per-job degradation**.

This eliminates Kafka itself as the root cause. The production noisy neighbor effect is not caused by:

| Hypothesis | Result |
|:---|:---|
| BQ per-table contention | Disproven (rounds 1, 4, 5, 6) |
| BQ connection limit | Not reached (12 connections, 1.2% of limit) |
| BQ throughput quota | Not triggered (~91 MB/s, 29% of 314 MB/s limit) |
| Pub/Sub vs Kafka as source | Disproven -- both show zero degradation |
| KafkaIO connector overhead | Disproven -- ~400k RPS per job, no degradation |
| Kafka partition assignment | Disproven -- separate consumer groups work correctly |

### Remaining Hypotheses

The production degradation must be caused by a **configuration or architectural difference** not present in this test:

1. **Shared consumer group in production.** If both production Dataflow jobs use the **same consumer group**, Kafka splits partitions between them. Each job gets half the data, not a copy. This exactly explains the 200k -> 100k per-job pattern. **This is the most likely root cause** and is the first thing to verify.

2. **40 dynamic destination tables.** This test uses 4 tables; production uses 40. More tables means more write contexts, more BQ stream management, and potentially more connection overhead. However, with connection pooling enabled, this is unlikely to cause 50% degradation.

3. **Production-specific Kafka configuration.** `fetch.max.bytes`, `max.partition.fetch.bytes`, `max.poll.records`, or other consumer settings may be limiting throughput in production.

4. **Schema complexity.** The test uses a 9-field flat Avro schema. If the production schema has nested records, unions, or many fields, Avro deserialization CPU cost could be higher.

5. **Network topology.** The test uses the same VPC (`default`) for both Kafka and Dataflow. Production may have cross-VPC or cross-region network hops.

## Recommendations

1. **Verify consumer group configuration in production.** This is the single highest-priority action. If both Dataflow jobs share the same Kafka consumer group, switching to separate consumer groups should immediately resolve the 50% degradation. Each job must have a unique `group.id`.

2. **If consumer groups are already separate**, profile the production pipeline to identify CPU hotspots. Compare production CPU utilization (reported as "low") with this test's ~80% utilization. If production CPU is genuinely low despite low throughput, the bottleneck is in I/O wait -- likely Kafka fetch configuration or network latency.

3. **Check partition count and assignment.** Verify the production topic has enough partitions for the number of workers. With 3x n2-highcpu-8 workers (24 vCPUs), at least 24 partitions are recommended; 48 is optimal for Runner v2.

4. **Test with 40 destination tables.** If the consumer group hypothesis is ruled out, reproduce the test with 40 dynamic destination tables to match production exactly.

5. **Monitor per-job Kafka consumption rate.** Use `dataflow.googleapis.com/job/elements_produced` on the KafkaIO read step in production to confirm whether each job reads the full topic volume or half.
