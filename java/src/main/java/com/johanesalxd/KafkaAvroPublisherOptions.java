package com.johanesalxd;

import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.Validation;

/**
 * Pipeline options for the Kafka Avro publisher batch pipeline.
 *
 * <p>Mirrors the Python {@code PublisherPipelineOptions} but targets
 * Kafka instead of Pub/Sub. Only supports Avro format since Round 6
 * focuses on Avro binary messages.
 */
public interface KafkaAvroPublisherOptions extends PipelineOptions {

  @Description(
      "Kafka bootstrap server address "
          + "(e.g., bootstrap.perf-test-kafka.asia-southeast1.managedkafka.PROJECT_ID.cloud.goog:9092)")
  @Validation.Required
  String getBootstrapServer();

  void setBootstrapServer(String value);

  @Description("Kafka topic to publish messages to")
  @Validation.Required
  String getKafkaTopic();

  void setKafkaTopic(String value);

  @Description("Total number of messages to publish")
  @Default.Integer(400000000)
  int getNumMessages();

  void setNumMessages(int value);
}
