package com.johanesalxd.transforms;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DecoderFactory;
import org.junit.Test;

public class SyntheticAvroGeneratorTest {

  private final SyntheticAvroGenerator generator = new SyntheticAvroGenerator();

  @Test
  public void testGenerateRecordHasAllFields() {
    GenericRecord record = generator.generateRecord(new Random(42));

    Set<String> expectedFields =
        new HashSet<>(
            Arrays.asList(
                "ride_id",
                "point_idx",
                "latitude",
                "longitude",
                "timestamp",
                "meter_reading",
                "meter_increment",
                "ride_status",
                "passenger_count"));

    for (String field : expectedFields) {
      assertThat("Field " + field + " should not be null", record.get(field), notNullValue());
    }
  }

  @Test
  public void testGenerateRecordFieldTypes() {
    GenericRecord record = generator.generateRecord(new Random(42));

    assertThat(record.get("ride_id").toString().startsWith("ride-"), equalTo(true));
    assertThat((int) record.get("point_idx"), greaterThanOrEqualTo(0));
    assertThat((int) record.get("point_idx"), lessThanOrEqualTo(2000));
    assertThat((double) record.get("latitude"), greaterThanOrEqualTo(1.2));
    assertThat((double) record.get("latitude"), lessThanOrEqualTo(1.5));
    assertThat((double) record.get("longitude"), greaterThanOrEqualTo(103.6));
    assertThat((double) record.get("longitude"), lessThanOrEqualTo(104.0));
    assertThat((double) record.get("meter_reading"), greaterThanOrEqualTo(0.0));
    assertThat((double) record.get("meter_reading"), lessThanOrEqualTo(100.0));
    assertThat((double) record.get("meter_increment"), greaterThanOrEqualTo(0.01));
    assertThat((double) record.get("meter_increment"), lessThanOrEqualTo(0.10));
    assertThat((int) record.get("passenger_count"), greaterThanOrEqualTo(1));
    assertThat((int) record.get("passenger_count"), lessThanOrEqualTo(6));

    String rideStatus = record.get("ride_status").toString();
    Set<String> validStatuses =
        new HashSet<>(Arrays.asList("enroute", "pickup", "dropoff", "waiting"));
    assertThat("ride_status should be valid", validStatuses.contains(rideStatus), equalTo(true));
  }

  @Test
  public void testSerializeAndDeserializeRoundTrip() throws Exception {
    GenericRecord original = generator.generateRecord(new Random(42));
    byte[] bytes = generator.serializeRecord(original);

    assertThat("Serialized bytes should not be empty", bytes.length, greaterThan(0));

    // Deserialize using the same schema
    Schema schema = generator.getAvroSchema();
    GenericDatumReader<GenericRecord> reader = new GenericDatumReader<>(schema);
    BinaryDecoder decoder =
        DecoderFactory.get().binaryDecoder(new ByteArrayInputStream(bytes), null);
    GenericRecord deserialized = reader.read(null, decoder);

    assertThat(deserialized.get("ride_id").toString(), equalTo(original.get("ride_id").toString()));
    assertThat((int) deserialized.get("point_idx"), equalTo((int) original.get("point_idx")));
    assertThat((double) deserialized.get("latitude"), equalTo((double) original.get("latitude")));
    assertThat(
        deserialized.get("ride_status").toString(),
        equalTo(original.get("ride_status").toString()));
    assertThat(
        (int) deserialized.get("passenger_count"),
        equalTo((int) original.get("passenger_count")));
  }

  @Test
  public void testGenerateMessagePool() throws Exception {
    int poolSize = 50;
    List<byte[]> pool = generator.generateMessagePool(poolSize, new Random(42));

    assertThat(pool, hasSize(poolSize));

    // Verify all messages are valid Avro binary
    Schema schema = generator.getAvroSchema();
    GenericDatumReader<GenericRecord> reader = new GenericDatumReader<>(schema);
    for (byte[] message : pool) {
      assertThat("Message should not be empty", message.length, greaterThan(0));
      BinaryDecoder decoder =
          DecoderFactory.get().binaryDecoder(new ByteArrayInputStream(message), null);
      GenericRecord record = reader.read(null, decoder);
      assertThat(record.get("ride_id"), notNullValue());
    }
  }

  @Test
  public void testGetAvroSchema() {
    Schema schema = generator.getAvroSchema();

    assertThat(schema.getName(), equalTo("TaxiRide"));
    assertThat(schema.getNamespace(), equalTo("com.example.taxi"));
    assertThat(schema.getFields(), hasSize(9));
  }
}
