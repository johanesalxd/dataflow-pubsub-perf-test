package com.johanesalxd;

import com.johanesalxd.transforms.SyntheticAvroGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.kafka.KafkaIO;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.KV;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Batch pipeline for publishing synthetic Avro messages to Kafka.
 *
 * <p>Java equivalent of the Python publisher pipeline, targeting Kafka
 * instead of Pub/Sub. Generates synthetic taxi ride Avro binary messages
 * and writes them to a Kafka topic using Dataflow workers.
 *
 * <p>Architecture mirrors the Python publisher:
 * <pre>
 *   Create seed elements (100 seeds)
 *     -> FlatMap: generate Avro message batches (pool-based)
 *     -> KafkaIO.write() to Kafka topic
 * </pre>
 *
 * <p>This is a batch pipeline: it generates a fixed number of messages,
 * publishes them as fast as the workers allow, and auto-terminates.
 * Messages remain in the Kafka topic for consumer Dataflow jobs to
 * process.
 */
public class KafkaAvroPublisher {

  private static final Logger LOG = LoggerFactory.getLogger(KafkaAvroPublisher.class);

  /**
   * Number of seed elements for parallelism. Each seed generates a
   * batch of messages. Matches the Python publisher's _NUM_SEEDS.
   */
  private static final int NUM_SEEDS = 100;

  /**
   * Pool size for pre-generating messages within each bundle.
   * Matches the Python publisher's _POOL_SIZE.
   */
  private static final int POOL_SIZE = 1000;

  /**
   * DoFn that generates a batch of Avro-encoded messages for one seed.
   *
   * <p>Pre-generates a pool of messages and cycles through it to
   * produce the required count. This avoids per-message random
   * generation overhead while still providing message diversity.
   *
   * <p>Mirrors the Python {@code generate_avro_batch()} function.
   */
  static class GenerateAvroBatchFn extends DoFn<KV<Integer, Integer>, KV<byte[], byte[]>> {

    private transient SyntheticAvroGenerator generator;

    @Setup
    public void setup() {
      generator = new SyntheticAvroGenerator();
    }

    /**
     * Generates a batch of Avro messages for a single seed element.
     *
     * @param context the process context containing the seed and count.
     */
    @ProcessElement
    public void processElement(ProcessContext context) throws Exception {
      KV<Integer, Integer> seedCount = context.element();
      int seed = seedCount.getKey();
      int numMessages = seedCount.getValue();

      int poolSize = Math.min(POOL_SIZE, numMessages);
      Random random = new Random(seed);
      List<byte[]> pool = generator.generateMessagePool(poolSize, random);

      for (int i = 0; i < numMessages; i++) {
        byte[] message = pool.get(i % poolSize);
        context.output(KV.of(new byte[0], message));
      }
    }
  }

  public static void main(String[] args) {
    KafkaAvroPublisherOptions options =
        PipelineOptionsFactory.fromArgs(args)
            .withValidation()
            .as(KafkaAvroPublisherOptions.class);

    String bootstrapServer = options.getBootstrapServer();
    String kafkaTopic = options.getKafkaTopic();
    int numMessages = options.getNumMessages();

    // Calculate messages per seed for even distribution
    int msgsPerSeed = numMessages / NUM_SEEDS;
    int remainder = numMessages % NUM_SEEDS;

    // Measure sample Avro message size
    SyntheticAvroGenerator sampleGen = new SyntheticAvroGenerator();
    try {
      byte[] sample = sampleGen.generateSerializedRecord(new Random());
      long totalBytes = (long) numMessages * sample.length;
      double totalGb = totalBytes / 1_000_000_000.0;

      LOG.info("Kafka Avro publisher configuration:");
      LOG.info("  Bootstrap server:   {}", bootstrapServer);
      LOG.info("  Topic:              {}", kafkaTopic);
      LOG.info("  Total messages:     {}", numMessages);
      LOG.info("  Avro message size:  {} bytes (schema-determined)", sample.length);
      LOG.info("  Total data:         {} GB", String.format("%.1f", totalGb));
      LOG.info("  Seeds:              {}", NUM_SEEDS);
      LOG.info("  Messages per seed:  {} (+{} remainder)", msgsPerSeed, remainder);
    } catch (Exception e) {
      LOG.error("Failed to generate sample message", e);
    }

    // Build seed elements: (seed_index, message_count)
    List<KV<Integer, Integer>> seedsWithCounts = new ArrayList<>();
    for (int i = 0; i < NUM_SEEDS; i++) {
      int count = msgsPerSeed + (i < remainder ? 1 : 0);
      if (count > 0) {
        seedsWithCounts.add(KV.of(i, count));
      }
    }

    Pipeline pipeline = Pipeline.create(options);

    pipeline
        .apply("CreateSeeds", Create.of(seedsWithCounts))
        .apply("GenerateAvroMessages", ParDo.of(new GenerateAvroBatchFn()))
        .apply(
            "WriteToKafka",
            KafkaIO.<byte[], byte[]>write()
                .withBootstrapServers(bootstrapServer)
                .withTopic(kafkaTopic)
                .withKeySerializer(ByteArraySerializer.class)
                .withValueSerializer(ByteArraySerializer.class)
                .withGCPApplicationDefaultCredentials());

    pipeline.run();
  }
}
