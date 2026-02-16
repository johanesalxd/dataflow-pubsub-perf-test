package com.johanesalxd;

import com.google.api.services.bigquery.model.TableRow;
import com.google.common.collect.ImmutableMap;
import com.johanesalxd.schemas.DeadLetterBigQuerySchema;
import com.johanesalxd.schemas.TaxiRideBigQuerySchema;
import com.johanesalxd.transforms.KafkaAvroToTableRow;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryOptions;
import org.apache.beam.sdk.io.gcp.bigquery.TableDestination;
import org.apache.beam.sdk.io.kafka.KafkaIO;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.TupleTagList;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

/**
 * Streaming pipeline that reads Avro binary from Kafka and writes to BigQuery
 * with dynamic table routing based on ride_status.
 *
 * <p>Mirrors {@link PubSubToBigQueryAvro} exactly, replacing only the source
 * connector (KafkaIO instead of PubsubIO). All downstream processing is
 * identical: same Avro deserialization, same 14-field TableRow, same dynamic
 * routing to 4 tables, same DLQ pattern, same BigQuery write configuration.
 *
 * <p>Architecture:
 * <pre>
 *   Kafka (Avro binary)
 *     -> Deserialize Avro + extract typed fields
 *     -> Route to BigQuery tables by ride_status:
 *         {outputTableBase}_enroute
 *         {outputTableBase}_pickup
 *         {outputTableBase}_dropoff
 *         {outputTableBase}_waiting
 *     -> DLQ: {outputTableBase}_dlq
 * </pre>
 *
 * <p>Uses {@code STORAGE_API_AT_LEAST_ONCE} with connection pooling enabled,
 * matching the Pub/Sub pipeline configuration. KafkaIO is configured with
 * out-of-the-box defaults — no redistribute, no custom fetch sizes, no
 * performance tuning — to isolate the Kafka source as the only variable.
 */
public class KafkaToBigQueryAvro {

  public static void main(String[] args) {
    // Parse pipeline options
    KafkaToBigQueryAvroOptions options =
        PipelineOptionsFactory.fromArgs(args)
            .withValidation()
            .as(KafkaToBigQueryAvroOptions.class);

    // Enable Storage Write API connection pooling (multiplexing).
    // Identical to PubSubToBigQueryAvro configuration.
    BigQueryOptions bqOptions = options.as(BigQueryOptions.class);
    bqOptions.setUseStorageApiConnectionPool(true);

    String outputTableBase = options.getOutputTableBase();

    // Create the pipeline
    Pipeline pipeline = Pipeline.create(options);

    // Read from Kafka and decode Avro.
    // KafkaIO with OOTB config -- only group.id and auto.offset.reset are set.
    // No redistribute, no custom fetch sizes, no performance tuning.
    PCollectionTuple results =
        pipeline
            .apply(
                "ReadFromKafka",
                KafkaIO.<byte[], byte[]>read()
                    .withBootstrapServers(options.getBootstrapServer())
                    .withTopic(options.getKafkaTopic())
                    .withKeyDeserializer(ByteArrayDeserializer.class)
                    .withValueDeserializer(ByteArrayDeserializer.class)
                    .withConsumerConfigUpdates(
                        ImmutableMap.of(
                            "group.id", options.getConsumerGroup(),
                            "auto.offset.reset", "earliest"))
                    .withGCPApplicationDefaultCredentials())
            .apply(
                "DecodeAvroAndRoute",
                ParDo.of(new KafkaAvroToTableRow(options.getConsumerGroupName()))
                    .withOutputTags(
                        KafkaAvroToTableRow.SUCCESS_TAG,
                        TupleTagList.of(KafkaAvroToTableRow.DLQ_TAG)));

    // Write success to BigQuery with dynamic routing.
    // Identical to PubSubToBigQueryAvro configuration.
    results
        .get(KafkaAvroToTableRow.SUCCESS_TAG)
        .apply(
            "WriteToBigQuery",
            BigQueryIO.<KV<String, TableRow>>write()
                .to(
                    raw -> {
                      String rideStatus = raw.getValue().getKey();
                      return new TableDestination(
                          outputTableBase + "_" + rideStatus, null);
                    })
                .withFormatFunction(KV::getValue)
                .withSchema(TaxiRideBigQuerySchema.getSchema())
                .withWriteDisposition(
                    BigQueryIO.Write.WriteDisposition.WRITE_APPEND)
                .withCreateDisposition(
                    BigQueryIO.Write.CreateDisposition.CREATE_NEVER)
                .withMethod(
                    BigQueryIO.Write.Method.STORAGE_API_AT_LEAST_ONCE));

    // Write failures to BigQuery DLQ (single table, at-least-once, low volume)
    results
        .get(KafkaAvroToTableRow.DLQ_TAG)
        .apply(
            "WriteToDLQ",
            BigQueryIO.writeTableRows()
                .to(outputTableBase + "_dlq")
                .withSchema(DeadLetterBigQuerySchema.getSchema())
                .withWriteDisposition(
                    BigQueryIO.Write.WriteDisposition.WRITE_APPEND)
                .withCreateDisposition(
                    BigQueryIO.Write.CreateDisposition.CREATE_IF_NEEDED)
                .withMethod(
                    BigQueryIO.Write.Method.STORAGE_API_AT_LEAST_ONCE));

    // Run the pipeline
    pipeline.run();
  }
}
