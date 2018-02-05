/**
 * Copyright © 2016-2018 Cloudera, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cloudera.labs.envelope.input;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.streaming.Duration;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.kafka010.ConsumerStrategies;
import org.apache.spark.streaming.kafka010.HasOffsetRanges;
import org.apache.spark.streaming.kafka010.KafkaUtils;
import org.apache.spark.streaming.kafka010.LocationStrategies;
import org.apache.spark.streaming.kafka010.OffsetRange;

import com.cloudera.labs.envelope.output.Output;
import com.cloudera.labs.envelope.output.OutputFactory;
import com.cloudera.labs.envelope.output.RandomOutput;
import com.cloudera.labs.envelope.plan.MutationType;
import com.cloudera.labs.envelope.plan.PlannedRow;
import com.cloudera.labs.envelope.spark.Contexts;
import com.cloudera.labs.envelope.spark.RowWithSchema;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.typesafe.config.Config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple2;

public class KafkaInput implements StreamInput, CanRecordProgress {

  public static final String BROKERS_CONFIG = "brokers";
  public static final String TOPIC_CONFIG = "topic";
  public static final String ENCODING_CONFIG = "encoding";
  public static final String PARAMETER_CONFIG_PREFIX = "parameter.";
  public static final String WINDOW_ENABLED_CONFIG = "window.enabled";
  public static final String WINDOW_MILLISECONDS_CONFIG = "window.milliseconds";
  public static final String OFFSETS_MANAGE_CONFIG = "offsets.manage";
  public static final String OFFSETS_OUTPUT_CONFIG = "offsets.output";
  public static final String GROUP_ID_CONFIG = "group.id";

  private Config config;
  private String groupID;
  private String topic;
  private OffsetRange[] offsetRanges;
  private RandomOutput offsetsOutput;

  private static Logger LOG = LoggerFactory.getLogger(KafkaInput.class);

  @Override
  public void configure(Config config) {
    this.config = config;
  }

  @Override
  public JavaDStream<?> getDStream() throws Exception {
    Map<String, Object> kafkaParams = Maps.newHashMap();

    String brokers = config.getString(BROKERS_CONFIG);
    kafkaParams.put("bootstrap.servers", brokers);

    topic = config.getString(TOPIC_CONFIG);
    Set<String> topicSet = Sets.newHashSet(topic);

    String encoding = config.getString(ENCODING_CONFIG);
    if (encoding.equals("string")) {
      kafkaParams.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
      kafkaParams.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
    }
    else if (encoding.equals("bytearray")) {
      kafkaParams.put("key.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
      kafkaParams.put("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
    }
    else {
      throw new RuntimeException("Invalid Kafka input encoding type. Valid types are 'string' and 'bytearray'.");
    }
    
    if (config.hasPath(GROUP_ID_CONFIG)) {
      groupID = config.getString(GROUP_ID_CONFIG);
    }
    else {
      groupID = UUID.randomUUID().toString();
    }
    kafkaParams.put("group.id", groupID);
    
    kafkaParams.put("enable.auto.commit", "false");

    addCustomParams(kafkaParams);

    JavaStreamingContext jssc = Contexts.getJavaStreamingContext();
    JavaDStream<?> dStream = null;

    if (encoding.equals("string")) {
      if (doesRecordProgress() && hasLastOffsets()) {
        dStream = KafkaUtils.createDirectStream(jssc, LocationStrategies.PreferConsistent(),
            ConsumerStrategies.<String, String>Subscribe(topicSet, kafkaParams, getLastOffsets()));
      }
      else {
        dStream = KafkaUtils.createDirectStream(jssc, LocationStrategies.PreferConsistent(),
            ConsumerStrategies.<String, String>Subscribe(topicSet, kafkaParams));
      }
    }
    else if (encoding.equals("bytearray")) {
      if (doesRecordProgress() && hasLastOffsets()) {      
        dStream = KafkaUtils.createDirectStream(jssc, LocationStrategies.PreferConsistent(),
            ConsumerStrategies.<byte[], byte[]>Subscribe(topicSet, kafkaParams, getLastOffsets()));
      }
      else {
        dStream = KafkaUtils.createDirectStream(jssc, LocationStrategies.PreferConsistent(),
            ConsumerStrategies.<byte[], byte[]>Subscribe(topicSet, kafkaParams));
      }
    }
    else {
      throw new RuntimeException("Invalid Kafka input encoding type. Valid types are 'string' and 'bytearray'.");
    }

    if (config.hasPath(WINDOW_ENABLED_CONFIG) && config.getBoolean(WINDOW_ENABLED_CONFIG)) {
      int windowDuration = config.getInt(WINDOW_MILLISECONDS_CONFIG);

      dStream = dStream.window(new Duration(windowDuration));
    }

    return dStream;
  }

  private void addCustomParams(Map<String, Object> params) {
    for (String propertyName : config.root().keySet()) {
      if (propertyName.startsWith(PARAMETER_CONFIG_PREFIX)) {
        String paramName = propertyName.substring(PARAMETER_CONFIG_PREFIX.length());
        String paramValue = config.getString(propertyName);

        params.put(paramName, paramValue);
      }
    }
  }

  @Override
  public PairFunction<?, ?, ?> getPrepareFunction() {
    return new UnwrapConsumerRecordFunction();
  }
  
  @SuppressWarnings({ "serial", "rawtypes" })
  private static class UnwrapConsumerRecordFunction implements PairFunction {
    @Override
    public Tuple2 call(Object recordObject) throws Exception {
      ConsumerRecord record = (ConsumerRecord)recordObject;
      return new Tuple2<>(record.key(), record.value());
    }
  }
  
  private boolean doesRecordProgress() {
    boolean managed = config.hasPath(OFFSETS_MANAGE_CONFIG) && config.getBoolean(OFFSETS_MANAGE_CONFIG);
    
    if (managed && !config.hasPath(GROUP_ID_CONFIG)) {
      throw new RuntimeException("Kafka input can not manage offsets without a provided group ID");
    }
    
    return managed;
  }

  @Override
  public void stageProgress(JavaRDD<?> batch) {
    LOG.info("In stageProgress function call ");
    offsetRanges = ((HasOffsetRanges) batch.rdd()).offsetRanges();
    if (null == offsetRanges) {
      LOG.info("No Offsets found.");
    } else {
      LOG.info("Kafka Offsets: ");
      for (OffsetRange o : offsetRanges) {
        LOG.info("Topic = " + o.topic() + ", Partition = " + o.partition() + ", fromOffset = " + o.fromOffset() + ", untilOffset = " + o.untilOffset());
      }
    }
  }

  @Override
  public void recordProgress() throws Exception {
    if (doesRecordProgress()) {
      // Plan the offset ranges as an upsert 
      List<PlannedRow> planned = Lists.newArrayList();
      StructType schema = DataTypes.createStructType(Lists.newArrayList(
          DataTypes.createStructField("group_id", DataTypes.StringType, false),
          DataTypes.createStructField("topic", DataTypes.StringType, false),
          DataTypes.createStructField("partition", DataTypes.IntegerType, false),
          DataTypes.createStructField("offset", DataTypes.LongType, false)));
      for (OffsetRange offsetRange : offsetRanges) {
        Row offsetRow = new RowWithSchema(schema, groupID, offsetRange.topic(), offsetRange.partition(), offsetRange.untilOffset());
        PlannedRow plan = new PlannedRow(offsetRow, MutationType.UPSERT);
        planned.add(plan);
      }
      
      // Upsert the offset ranges at the output
      RandomOutput output = getOffsetsOutput();
      output.applyRandomMutations(planned);
      
      // Retrieve back the offset ranges and assert that they were stored correctly
      Map<TopicPartition, Long> storedOffsets = getLastOffsets();
      for (OffsetRange offsetRange : offsetRanges) {
        TopicPartition tp = new TopicPartition(offsetRange.topic(), offsetRange.partition());
        if (!storedOffsets.get(tp).equals(offsetRange.untilOffset())) {
          String exceptionMessage = String.format(
              "Kafka input failed to assert that offset ranges were stored correctly! " + 
              "For group ID '%s', topic '%s', partition '%d' expected offset '%d' but found offset '%d'",
              groupID, topic, tp.partition(), offsetRange.untilOffset(), storedOffsets.get(tp));
          throw new RuntimeException(exceptionMessage);
        }
      }
    }
  }
  
  private RandomOutput getOffsetsOutput() {
    if (offsetsOutput == null) {
      Config outputConfig = config.getConfig(OFFSETS_OUTPUT_CONFIG);
      Output output = OutputFactory.create(outputConfig);
      
      if (!(output instanceof RandomOutput) ||
          !((RandomOutput)output).getSupportedRandomMutationTypes().contains(MutationType.UPSERT)) {
        throw new RuntimeException("Output used for Kafka offsets must support random upsert mutations");
      }
      
      offsetsOutput = (RandomOutput)output;
    }
    
    return offsetsOutput;
  }
  
  private boolean hasLastOffsets() throws Exception {
    return !getLastOffsets().isEmpty();
  }
  
  private Map<TopicPartition, Long> getLastOffsets() throws Exception {
    // Create filter for groupid/topic
    StructType filterSchema = DataTypes.createStructType(Lists.newArrayList(
        DataTypes.createStructField("group_id", DataTypes.StringType, false),
        DataTypes.createStructField("topic", DataTypes.StringType, false)));
    Row groupIDTopicFilter = new RowWithSchema(filterSchema, groupID, topic);
    Iterable<Row> filters = Collections.singleton(groupIDTopicFilter);
    
    // Get results
    RandomOutput output = getOffsetsOutput();
    Iterable<Row> results = output.getExistingForFilters(filters);
    
    // Transform results into map
    Map<TopicPartition, Long> offsetRanges = Maps.newHashMap();
    for (Row result : results) {
      Integer partition = result.getInt(result.fieldIndex("partition"));
      Long offset = result.getLong(result.fieldIndex("offset"));
      TopicPartition topicPartition = new TopicPartition(topic, partition);
      
      offsetRanges.put(topicPartition, offset);
    }
    
    return offsetRanges;
  }

}
