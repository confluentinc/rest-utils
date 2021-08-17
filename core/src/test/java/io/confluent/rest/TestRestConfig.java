/*
 * Copyright 2015 Confluent Inc.
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

import static java.util.Collections.emptyMap;
import static org.apache.kafka.clients.CommonClientConfigs.METRICS_CONTEXT_PREFIX;

import io.confluent.rest.metrics.RestMetricsContext;
import io.confluent.rest.metrics.TestRestMetricsContext;
import org.apache.kafka.common.config.ConfigDef;

import java.util.HashMap;
import java.util.Map;

/**
 * Test config class that only uses the built-in properties of RestConfig.
 */
public class TestRestConfig extends RestConfig {
  private static final ConfigDef config;
  static {
    config = baseConfigDef();
  }
  public TestRestConfig() {
    this(emptyMap());
  }

  public TestRestConfig(Map<?,?> originals) {
    super(config, createConfigs(originals));
  }

  private static Map<Object, Object> createConfigs(Map<?, ?> originals) {
    HashMap<Object, Object> configs = new HashMap<>();
    configs.put("listeners", "http://localhost:0");
    configs.putAll(originals);
    return configs;
  }

  @Override
  public RestMetricsContext getMetricsContext() {
    return new TestRestMetricsContext(
            getString(METRICS_JMX_PREFIX_CONFIG),
            originalsWithPrefix(METRICS_CONTEXT_PREFIX)).metricsContext();
  }

}
