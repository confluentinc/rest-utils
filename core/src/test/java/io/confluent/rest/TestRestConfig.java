/**
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
 **/

package io.confluent.rest;

import java.util.Map;

import io.confluent.common.config.ConfigDef;

/**
 * Test config class that only uses the built-in properties of RestConfig.
 */
public class TestRestConfig extends RestConfig {
  private static final ConfigDef config;
  static {
    config = baseConfigDef();
  }
  public TestRestConfig(Map<?,?> originals) {
    super(config, originals);
  }
}

