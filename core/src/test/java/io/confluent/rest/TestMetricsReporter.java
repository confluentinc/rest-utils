/*
 * Copyright 2016 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.confluent.rest;

import org.apache.kafka.common.metrics.KafkaMetric;
import org.apache.kafka.common.metrics.MetricsReporter;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class TestMetricsReporter implements MetricsReporter {

  private static List<KafkaMetric> metricTimeseries = new LinkedList<>();

  private Map<String, ?> configs;

  public void metricChange(KafkaMetric metric) {
    metricTimeseries.add(metric);
  }

  @Override
  public void metricRemoval(KafkaMetric kafkaMetric) {

  }

  public static List<KafkaMetric> getMetricTimeseries() {
    return metricTimeseries;
  }

  public static void reset() { metricTimeseries = new LinkedList<>(); }

  public static void print() {
    for (KafkaMetric metric : metricTimeseries) {
      System.out.println("\t" + metric.metricName() + ": " + metric.metricValue());
    }
  }

  public void configure(Map<String, ?> configs) {
    this.configs = configs;
  }

  public Map<String, ?> getConfigs() {
    return this.configs;
  }

  public void init(List<KafkaMetric> metrics) {
  }

  public void close() {
  }
}
