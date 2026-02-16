package com.johanesalxd.transforms;

import com.google.api.services.bigquery.model.TableRow;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.beam.sdk.io.kafka.KafkaRecord;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.TupleTag;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deserializes Avro binary messages from Kafka and extracts typed fields.
 *
 * <p>Mirrors {@link PubsubAvroToTableRow} but reads from
 * {@code KafkaRecord<byte[], byte[]>} instead of {@code PubsubMessage}.
 * Same Avro deserialization logic, same 14-field TableRow output, same
 * DLQ error handling pattern. Only the metadata extraction differs:
 *
 * <ul>
 *   <li>{@code subscription_name} -> consumer group name
 *   <li>{@code message_id} -> {@code partition:offset}
 *   <li>{@code publish_time} -> Kafka record timestamp
 *   <li>{@code attributes} -> {@code "{}"}  (no Kafka headers used)
 * </ul>
 *
 * <p>Produces {@code KV<String, TableRow>} where the key is the
 * {@code ride_status} value (used for dynamic table routing).
 */
public class KafkaAvroToTableRow
    extends DoFn<KafkaRecord<byte[], byte[]>, KV<String, TableRow>> {

  private static final Logger LOG = LoggerFactory.getLogger(KafkaAvroToTableRow.class);

  public static final TupleTag<KV<String, TableRow>> SUCCESS_TAG =
      new TupleTag<KV<String, TableRow>>() {};
  public static final TupleTag<TableRow> DLQ_TAG = new TupleTag<TableRow>() {};

  /** Classpath resource path for the taxi ride Avro schema. */
  private static final String SCHEMA_RESOURCE = "/taxi_ride_v1.avsc";

  private final String consumerGroupName;

  private transient Schema avroSchema;
  private transient GenericDatumReader<GenericRecord> datumReader;

  /**
   * Creates a new KafkaAvroToTableRow transform.
   *
   * @param consumerGroupName consumer group name for metadata recording.
   *     Stored in the {@code subscription_name} column for consistency
   *     with the Pub/Sub pipeline's TableRow schema.
   */
  public KafkaAvroToTableRow(String consumerGroupName) {
    this.consumerGroupName = consumerGroupName;
  }

  @Setup
  public void setup() {
    try (InputStream is = getClass().getResourceAsStream(SCHEMA_RESOURCE)) {
      if (is == null) {
        throw new IllegalStateException(
            "Avro schema resource not found: " + SCHEMA_RESOURCE);
      }
      avroSchema = new Schema.Parser().parse(is);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load Avro schema", e);
    }
    datumReader = new GenericDatumReader<>(avroSchema);
  }

  /**
   * Deserializes an Avro binary Kafka message into a routable TableRow.
   *
   * @param record the Kafka record containing Avro binary value payload.
   * @param out multi-output receiver for success and DLQ paths.
   */
  @ProcessElement
  public void processElement(
      @Element KafkaRecord<byte[], byte[]> record,
      MultiOutputReceiver out) {

    byte[] payload = record.getKV().getValue();

    try {
      // Deserialize Avro binary (schemaless -- no container header)
      BinaryDecoder decoder =
          DecoderFactory.get().binaryDecoder(new ByteArrayInputStream(payload), null);
      GenericRecord avroRecord = datumReader.read(null, decoder);

      // Extract typed fields from Avro GenericRecord
      String rideId = avroRecord.get("ride_id").toString();
      int pointIdx = (int) avroRecord.get("point_idx");
      double latitude = (double) avroRecord.get("latitude");
      double longitude = (double) avroRecord.get("longitude");
      String rideTimestamp = avroRecord.get("timestamp").toString();
      double meterReading = (double) avroRecord.get("meter_reading");
      double meterIncrement = (double) avroRecord.get("meter_increment");
      String rideStatus = avroRecord.get("ride_status").toString();
      int passengerCount = (int) avroRecord.get("passenger_count");

      // Extract Kafka metadata (maps to Pub/Sub metadata columns)
      String messageId =
          record.getPartition() + ":" + record.getOffset();
      Instant publishTime =
          new Instant(record.getTimestamp());

      // Build typed TableRow (14 fields, matching TaxiRideBigQuerySchema)
      TableRow row = new TableRow();
      // Envelope metadata (same columns as Pub/Sub pipeline)
      row.set("subscription_name", this.consumerGroupName);
      row.set("message_id", messageId);
      row.set("publish_time", publishTime.toString());
      row.set("processing_time", Instant.now().toString());
      // Taxi ride data
      row.set("ride_id", rideId);
      row.set("point_idx", pointIdx);
      row.set("latitude", latitude);
      row.set("longitude", longitude);
      row.set("timestamp", rideTimestamp);
      row.set("meter_reading", meterReading);
      row.set("meter_increment", meterIncrement);
      row.set("ride_status", rideStatus);
      row.set("passenger_count", passengerCount);
      // Kafka has no attributes equivalent; empty JSON for schema consistency
      row.set("attributes", "{}");

      // Output KV: key = ride_status (routing), value = clean TableRow (data)
      out.get(SUCCESS_TAG).output(KV.of(rideStatus, row));

    } catch (Exception e) {
      String errorMessage =
          e.getMessage() != null ? e.getMessage() : e.getClass().getName();
      LOG.error("Error processing Kafka Avro message: {}", errorMessage);

      // Extract stack trace
      StringWriter sw = new StringWriter();
      e.printStackTrace(new PrintWriter(sw));

      TableRow errorRow = new TableRow();
      errorRow.set("processing_time", Instant.now().toString());
      errorRow.set("error_message", errorMessage);
      errorRow.set("stack_trace", sw.toString());
      errorRow.set(
          "original_payload",
          payload != null
              ? new String(payload, StandardCharsets.UTF_8)
              : "");
      errorRow.set("subscription_name", this.consumerGroupName);

      out.get(DLQ_TAG).output(errorRow);
    }
  }
}
