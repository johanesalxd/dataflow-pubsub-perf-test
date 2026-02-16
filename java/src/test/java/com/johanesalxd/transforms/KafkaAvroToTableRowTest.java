package com.johanesalxd.transforms;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;

import com.google.api.services.bigquery.model.TableRow;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.apache.beam.sdk.coders.ByteArrayCoder;
import org.apache.beam.sdk.io.kafka.KafkaRecord;
import org.apache.beam.sdk.io.kafka.KafkaRecordCoder;
import org.apache.beam.sdk.io.kafka.KafkaTimestampType;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.TupleTagList;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.Rule;
import org.junit.Test;

public class KafkaAvroToTableRowTest {

  @Rule public final transient TestPipeline pipeline = TestPipeline.create();

  private static final String TEST_TOPIC = "perf-test-taxi-avro";
  private static final String TEST_CONSUMER_GROUP = "perf-a";
  private static final long TEST_TIMESTAMP = 1739600000000L; // 2025-02-15T...

  private static final KafkaRecordCoder<byte[], byte[]> KAFKA_CODER =
      KafkaRecordCoder.of(ByteArrayCoder.of(), ByteArrayCoder.of());

  private final SyntheticAvroGenerator generator = new SyntheticAvroGenerator();

  /** Creates a KafkaRecord wrapping the given Avro bytes. */
  private static KafkaRecord<byte[], byte[]> createKafkaRecord(
      byte[] avroBytes, int partition, long offset) {
    return new KafkaRecord<>(
        TEST_TOPIC,
        partition,
        offset,
        TEST_TIMESTAMP,
        KafkaTimestampType.CREATE_TIME,
        new RecordHeaders(),
        new byte[0],
        avroBytes);
  }

  @Test
  public void testValidAvroMessage() throws Exception {
    byte[] avroBytes = generator.generateSerializedRecord(new Random(42));

    KafkaRecord<byte[], byte[]> record = createKafkaRecord(avroBytes, 5, 100);

    PCollectionTuple results =
        pipeline
            .apply(Create.of(record).withCoder(KAFKA_CODER))
            .apply(
                ParDo.of(new KafkaAvroToTableRow(TEST_CONSUMER_GROUP))
                    .withOutputTags(
                        KafkaAvroToTableRow.SUCCESS_TAG,
                        TupleTagList.of(KafkaAvroToTableRow.DLQ_TAG)));

    PCollection<KV<String, TableRow>> success =
        results.get(KafkaAvroToTableRow.SUCCESS_TAG);
    PCollection<TableRow> dlq = results.get(KafkaAvroToTableRow.DLQ_TAG);

    PAssert.that(success)
        .satisfies(
            rows -> {
              KV<String, TableRow> kv = rows.iterator().next();
              TableRow row = kv.getValue();

              // Verify routing key matches ride_status
              String rideStatus = (String) row.get("ride_status");
              if (!kv.getKey().equals(rideStatus)) {
                throw new AssertionError(
                    "Routing key '" + kv.getKey()
                        + "' does not match ride_status '" + rideStatus + "'");
              }

              // Verify Kafka metadata mapping
              if (!"perf-a".equals(row.get("subscription_name"))) {
                throw new AssertionError(
                    "subscription_name should be consumer group name, got: "
                        + row.get("subscription_name"));
              }
              if (!"5:100".equals(row.get("message_id"))) {
                throw new AssertionError(
                    "message_id should be partition:offset '5:100', got: "
                        + row.get("message_id"));
              }
              if (row.get("publish_time") == null) {
                throw new AssertionError("publish_time should not be null");
              }
              if (row.get("processing_time") == null) {
                throw new AssertionError("processing_time should not be null");
              }
              if (!"{}".equals(row.get("attributes"))) {
                throw new AssertionError(
                    "attributes should be '{}', got: " + row.get("attributes"));
              }

              // Verify taxi ride fields are present and typed correctly
              if (row.get("ride_id") == null) {
                throw new AssertionError("ride_id should not be null");
              }
              if (!(row.get("point_idx") instanceof Integer)) {
                throw new AssertionError("point_idx should be Integer");
              }
              if (!(row.get("latitude") instanceof Double)) {
                throw new AssertionError("latitude should be Double");
              }
              if (!(row.get("longitude") instanceof Double)) {
                throw new AssertionError("longitude should be Double");
              }
              if (!(row.get("passenger_count") instanceof Integer)) {
                throw new AssertionError("passenger_count should be Integer");
              }

              return null;
            });

    PAssert.that(dlq).empty();

    pipeline.run();
  }

  @Test
  public void testDifferentRideStatuses() throws Exception {
    // Generate multiple messages; use different seeds to get different ride_statuses
    List<KafkaRecord<byte[], byte[]>> records = new ArrayList<>();
    String[] statuses = {"enroute", "pickup", "dropoff", "waiting"};
    for (int i = 0; i < statuses.length; i++) {
      // Create a record with a specific ride_status by generating until we get it
      byte[] avroBytes = createAvroWithStatus(statuses[i]);
      records.add(createKafkaRecord(avroBytes, i, i * 100L));
    }

    PCollectionTuple results =
        pipeline
            .apply(Create.of(records).withCoder(KAFKA_CODER))
            .apply(
                ParDo.of(new KafkaAvroToTableRow(TEST_CONSUMER_GROUP))
                    .withOutputTags(
                        KafkaAvroToTableRow.SUCCESS_TAG,
                        TupleTagList.of(KafkaAvroToTableRow.DLQ_TAG)));

    PCollection<KV<String, TableRow>> success =
        results.get(KafkaAvroToTableRow.SUCCESS_TAG);

    PAssert.that(success)
        .satisfies(
            rows -> {
              List<String> keys = new ArrayList<>();
              for (KV<String, TableRow> kv : rows) {
                keys.add(kv.getKey());
                String rowStatus = (String) kv.getValue().get("ride_status");
                if (!kv.getKey().equals(rowStatus)) {
                  throw new AssertionError(
                      "Routing key '" + kv.getKey()
                          + "' does not match ride_status '" + rowStatus + "'");
                }
              }
              assertThat("Expected 4 outputs", keys, hasSize(4));
              for (String status : statuses) {
                if (!keys.contains(status)) {
                  throw new AssertionError("Missing routing key: " + status);
                }
              }
              return null;
            });

    pipeline.run();
  }

  @Test
  public void testMalformedAvroToDLQ() {
    byte[] garbage = "not valid avro data".getBytes(StandardCharsets.UTF_8);
    KafkaRecord<byte[], byte[]> record = createKafkaRecord(garbage, 0, 0);

    PCollectionTuple results =
        pipeline
            .apply(Create.of(record).withCoder(KAFKA_CODER))
            .apply(
                ParDo.of(new KafkaAvroToTableRow(TEST_CONSUMER_GROUP))
                    .withOutputTags(
                        KafkaAvroToTableRow.SUCCESS_TAG,
                        TupleTagList.of(KafkaAvroToTableRow.DLQ_TAG)));

    PCollection<KV<String, TableRow>> success =
        results.get(KafkaAvroToTableRow.SUCCESS_TAG);
    PCollection<TableRow> dlq = results.get(KafkaAvroToTableRow.DLQ_TAG);

    PAssert.that(success).empty();

    PAssert.that(dlq)
        .satisfies(
            rows -> {
              TableRow row = rows.iterator().next();
              if (row.get("error_message") == null) {
                throw new AssertionError("error_message should not be null");
              }
              if (row.get("stack_trace") == null) {
                throw new AssertionError("stack_trace should not be null");
              }
              if (!"not valid avro data".equals(row.get("original_payload"))) {
                throw new AssertionError("original_payload mismatch");
              }
              if (!"perf-a".equals(row.get("subscription_name"))) {
                throw new AssertionError(
                    "subscription_name should be consumer group name");
              }
              return null;
            });

    pipeline.run();
  }

  /**
   * Creates an Avro binary message with a specific ride_status.
   *
   * <p>Generates a record using SyntheticAvroGenerator, then overwrites
   * the ride_status field before serializing.
   */
  private byte[] createAvroWithStatus(String rideStatus) throws Exception {
    org.apache.avro.generic.GenericRecord record = generator.generateRecord(new Random());
    record.put("ride_status", rideStatus);
    return generator.serializeRecord(record);
  }
}
