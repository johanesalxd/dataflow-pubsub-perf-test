package com.johanesalxd;

import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.Validation;

/**
 * Pipeline options for the Kafka Avro consumer with dynamic table routing.
 *
 * <p>Mirrors {@link PubSubToBigQueryAvroOptions} but replaces
 * Pub/Sub-specific options (subscription) with Kafka-specific ones
 * (bootstrap server, topic, consumer group).
 */
public interface KafkaToBigQueryAvroOptions extends PipelineOptions {

  @Description(
      "Kafka bootstrap server address "
          + "(e.g., bootstrap.perf-test-kafka.asia-southeast1.managedkafka.PROJECT_ID.cloud.goog:9092)")
  @Validation.Required
  String getBootstrapServer();

  void setBootstrapServer(String value);

  @Description("Kafka topic to consume from")
  @Validation.Required
  String getKafkaTopic();

  void setKafkaTopic(String value);

  @Description(
      "Kafka consumer group ID. Each job should use a separate group "
          + "so both read all messages independently (e.g., perf-a, perf-b)")
  @Validation.Required
  String getConsumerGroup();

  void setConsumerGroup(String value);

  @Description(
      "BigQuery output table base reference (e.g., PROJECT_ID:DATASET.TABLE_PREFIX). "
          + "Routes to TABLE_PREFIX_enroute, TABLE_PREFIX_pickup, etc. "
          + "DLQ writes to TABLE_PREFIX_dlq.")
  @Validation.Required
  String getOutputTableBase();

  void setOutputTableBase(String value);

  @Description(
      "Consumer group name to record in BigQuery metadata "
          + "(maps to the subscription_name column for consistency)")
  @Validation.Required
  String getConsumerGroupName();

  void setConsumerGroupName(String value);
}
