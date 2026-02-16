package com.johanesalxd.transforms;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;

/**
 * Generates synthetic taxi ride Avro records for throughput testing.
 *
 * <p>Java port of the Python {@code synthetic_messages.py} generation logic.
 * Produces the same 9-field taxi ride records with randomized values,
 * serialized as schemaless Avro binary (no container header).
 *
 * <p>All methods are pure computation with no GCP or broker dependencies.
 */
public class SyntheticAvroGenerator implements Serializable {

  private static final long serialVersionUID = 1L;

  /** Classpath resource path for the taxi ride Avro schema. */
  private static final String SCHEMA_RESOURCE = "/taxi_ride_v1.avsc";

  private static final String[] RIDE_STATUSES =
      {"enroute", "pickup", "dropoff", "waiting"};

  private transient Schema avroSchema;
  private transient GenericDatumWriter<GenericRecord> datumWriter;

  /**
   * Initializes the Avro schema and datum writer.
   *
   * <p>Loads the schema from the classpath resource
   * {@code /taxi_ride_v1.avsc}. Called lazily on first use. Safe for
   * Dataflow serialization because transient fields are re-initialized
   * after deserialization.
   *
   * @throws IllegalStateException if the schema resource is not found.
   */
  private void ensureInitialized() {
    if (avroSchema == null) {
      try (InputStream is = getClass().getResourceAsStream(SCHEMA_RESOURCE)) {
        if (is == null) {
          throw new IllegalStateException(
              "Avro schema resource not found: " + SCHEMA_RESOURCE);
        }
        avroSchema = new Schema.Parser().parse(is);
      } catch (IOException e) {
        throw new IllegalStateException("Failed to load Avro schema", e);
      }
      datumWriter = new GenericDatumWriter<>(avroSchema);
    }
  }

  /**
   * Generates a single synthetic taxi ride as an Avro GenericRecord.
   *
   * <p>Produces the same 9 fields as the Python
   * {@code generate_avro_record()}: ride_id, point_idx, latitude,
   * longitude, timestamp, meter_reading, meter_increment, ride_status,
   * passenger_count.
   *
   * @param random the random number generator to use.
   * @return a GenericRecord matching the taxi_ride_v1 schema.
   */
  public GenericRecord generateRecord(Random random) {
    ensureInitialized();

    GenericRecord record = new GenericData.Record(avroSchema);
    record.put("ride_id", String.format("ride-%d", 100000 + random.nextInt(900000)));
    record.put("point_idx", random.nextInt(2001));
    record.put("latitude", roundTo6(1.2 + random.nextDouble() * 0.3));
    record.put("longitude", roundTo6(103.6 + random.nextDouble() * 0.4));
    record.put("timestamp", generateTimestamp(random));
    record.put("meter_reading", roundTo2(random.nextDouble() * 100.0));
    record.put("meter_increment", roundTo4(0.01 + random.nextDouble() * 0.09));
    record.put("ride_status", RIDE_STATUSES[random.nextInt(RIDE_STATUSES.length)]);
    record.put("passenger_count", 1 + random.nextInt(6));
    return record;
  }

  /**
   * Serializes an Avro GenericRecord to schemaless binary bytes.
   *
   * <p>Uses BinaryEncoder which writes only data bytes (no container
   * header, no embedded schema). The consumer must know the schema
   * a priori to deserialize.
   *
   * @param record the GenericRecord to serialize.
   * @return Avro binary encoded bytes (typically ~85-90 bytes).
   * @throws IOException if serialization fails.
   */
  public byte[] serializeRecord(GenericRecord record) throws IOException {
    ensureInitialized();

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
    datumWriter.write(record, encoder);
    encoder.flush();
    return out.toByteArray();
  }

  /**
   * Generates and serializes a single Avro binary message.
   *
   * <p>Convenience method combining {@link #generateRecord(Random)}
   * and {@link #serializeRecord(GenericRecord)}.
   *
   * @param random the random number generator to use.
   * @return Avro binary encoded bytes.
   * @throws IOException if serialization fails.
   */
  public byte[] generateSerializedRecord(Random random) throws IOException {
    return serializeRecord(generateRecord(random));
  }

  /**
   * Pre-generates a pool of Avro-encoded messages for high-throughput
   * publishing.
   *
   * <p>Same pooling strategy as the Python
   * {@code generate_avro_message_pool()}. Messages are generated at
   * startup so the publish loop cycles through pre-built byte arrays
   * instead of generating on every iteration.
   *
   * @param poolSize number of messages to pre-generate.
   * @param random the random number generator to use.
   * @return list of Avro binary encoded message bytes.
   * @throws IOException if serialization fails.
   */
  public List<byte[]> generateMessagePool(int poolSize, Random random) throws IOException {
    List<byte[]> pool = new ArrayList<>(poolSize);
    for (int i = 0; i < poolSize; i++) {
      pool.add(generateSerializedRecord(random));
    }
    return pool;
  }

  /**
   * Returns the parsed Avro schema.
   *
   * @return the taxi_ride_v1 Avro Schema object.
   */
  public Schema getAvroSchema() {
    ensureInitialized();
    return avroSchema;
  }

  private static String generateTimestamp(Random random) {
    return String.format(
        "2026-02-%02dT%02d:%02d:%02d.%06d+08:00",
        1 + random.nextInt(28),
        random.nextInt(24),
        random.nextInt(60),
        random.nextInt(60),
        random.nextInt(1000000));
  }

  private static double roundTo2(double value) {
    return Math.round(value * 100.0) / 100.0;
  }

  private static double roundTo4(double value) {
    return Math.round(value * 10000.0) / 10000.0;
  }

  private static double roundTo6(double value) {
    return Math.round(value * 1000000.0) / 1000000.0;
  }
}
