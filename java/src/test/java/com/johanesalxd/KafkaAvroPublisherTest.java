package com.johanesalxd;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DecoderFactory;
import org.apache.beam.sdk.coders.ByteArrayCoder;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.junit.Rule;
import org.junit.Test;

/**
 * Tests for {@link KafkaAvroPublisher.GenerateAvroBatchFn}.
 *
 * <p>Validates that the DoFn produces the correct number of elements
 * with non-null keys and values, and that values are valid Avro binary.
 */
public class KafkaAvroPublisherTest {

  @Rule public final transient TestPipeline pipeline = TestPipeline.create();

  /** Loads the taxi_ride_v1 Avro schema from classpath. */
  private static Schema loadSchema() throws Exception {
    try (var stream =
        KafkaAvroPublisherTest.class
            .getClassLoader()
            .getResourceAsStream("taxi_ride_v1.avsc")) {
      return new Schema.Parser().parse(stream);
    }
  }

  @Test
  public void testGenerateAvroBatchFnOutputCount() {
    int numMessages = 10;

    PCollection<KV<byte[], byte[]>> output =
        pipeline
            .apply(Create.of(KV.of(0, numMessages)))
            .apply(ParDo.of(new KafkaAvroPublisher.GenerateAvroBatchFn()));

    PAssert.that(output)
        .satisfies(
            records -> {
              List<KV<byte[], byte[]>> list = new ArrayList<>();
              records.forEach(list::add);
              if (list.size() != numMessages) {
                throw new AssertionError(
                    "Expected " + numMessages + " messages, got " + list.size());
              }
              return null;
            });

    pipeline.run();
  }

  @Test
  public void testGenerateAvroBatchFnNonNullKeysAndValues() {
    PCollection<KV<byte[], byte[]>> output =
        pipeline
            .apply(Create.of(KV.of(42, 5)))
            .apply(ParDo.of(new KafkaAvroPublisher.GenerateAvroBatchFn()));

    PAssert.that(output)
        .satisfies(
            records -> {
              for (KV<byte[], byte[]> kv : records) {
                if (kv.getKey() == null) {
                  throw new AssertionError("Key must not be null");
                }
                if (kv.getValue() == null) {
                  throw new AssertionError("Value must not be null");
                }
                if (kv.getValue().length == 0) {
                  throw new AssertionError("Value must not be empty");
                }
              }
              return null;
            });

    pipeline.run();
  }

  @Test
  public void testGenerateAvroBatchFnValuesAreValidAvro() throws Exception {
    Schema schema = loadSchema();

    PCollection<KV<byte[], byte[]>> output =
        pipeline
            .apply(Create.of(KV.of(7, 3)))
            .apply(ParDo.of(new KafkaAvroPublisher.GenerateAvroBatchFn()));

    PAssert.that(output)
        .satisfies(
            records -> {
              GenericDatumReader<GenericRecord> reader =
                  new GenericDatumReader<>(schema);
              for (KV<byte[], byte[]> kv : records) {
                try {
                  var decoder =
                      DecoderFactory.get()
                          .binaryDecoder(
                              new ByteArrayInputStream(kv.getValue()), null);
                  GenericRecord record = reader.read(null, decoder);
                  if (record.get("ride_id") == null) {
                    throw new AssertionError("Deserialized record missing ride_id");
                  }
                } catch (Exception e) {
                  throw new AssertionError(
                      "Value is not valid Avro: " + e.getMessage(), e);
                }
              }
              return null;
            });

    pipeline.run();
  }

  @Test
  public void testGenerateAvroBatchFnCoderRoundTrip() {
    // Verify that the output can be encoded/decoded by ByteArrayCoder
    // (this is what failed in production with null keys).
    PCollection<KV<byte[], byte[]>> output =
        pipeline
            .apply(Create.of(KV.of(1, 5)))
            .apply(ParDo.of(new KafkaAvroPublisher.GenerateAvroBatchFn()))
            .setCoder(KvCoder.of(ByteArrayCoder.of(), ByteArrayCoder.of()));

    PAssert.that(output)
        .satisfies(
            records -> {
              int count = 0;
              for (KV<byte[], byte[]> kv : records) {
                count++;
              }
              if (count != 5) {
                throw new AssertionError("Expected 5 messages, got " + count);
              }
              return null;
            });

    pipeline.run();
  }
}
