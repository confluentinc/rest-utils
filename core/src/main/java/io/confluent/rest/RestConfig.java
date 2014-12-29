/**
 * Copyright 2014 Confluent Inc.
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

import io.confluent.common.config.AbstractConfig;
import io.confluent.common.config.ConfigDef;
import io.confluent.common.config.ConfigDef.Type;
import io.confluent.common.config.ConfigDef.Importance;

import java.util.Map;
import java.util.TreeMap;

public abstract class RestConfig extends AbstractConfig {
  protected static final ConfigDef config;

  public static final String DEBUG_CONFIG = "debug";
  protected static final String DEBUG_CONFIG_DOC =
      "Boolean indicating whether extra debugging information is generated in some " +
      "error response entities.";
  protected static final boolean DEBUG_CONFIG_DEFAULT = true;

  public static final String PORT_CONFIG = "port";
  protected static final String PORT_CONFIG_DOC = "Port to listen on for new connections.";
  protected static final int PORT_CONFIG_DEFAULT = 8080;

  public static final String RESPONSE_MEDIATYPE_PREFERRED_CONFIG = "response.mediatype.preferred";
  protected static final String RESPONSE_MEDIATYPE_PREFERRED_CONFIG_DOC =
      "An ordered list of the server's preferred media types used for responses, " +
      "from most preferred to least.";
  protected static final String RESPONSE_MEDIATYPE_PREFERRED_CONFIG_DEFAULT = "application/json";

  public static final String RESPONSE_MEDIATYPE_DEFAULT_CONFIG = "response.mediatype.default";
  protected static final String RESPONSE_MEDIATYPE_DEFAULT_CONFIG_DOC =
      "The default response media type that should be used if no specify types are requested in " +
      "an Accept header.";
  protected static final String RESPONSE_MEDIATYPE_DEFAULT_CONFIG_DEFAULT = "application/json";

  static {
    config = new ConfigDef()
        .define(DEBUG_CONFIG, Type.BOOLEAN,
                DEBUG_CONFIG_DEFAULT, Importance.HIGH, DEBUG_CONFIG_DOC)
        .define(PORT_CONFIG, Type.INT, PORT_CONFIG_DEFAULT, Importance.HIGH,
                PORT_CONFIG_DOC)
        .define(RESPONSE_MEDIATYPE_PREFERRED_CONFIG, Type.LIST,
                RESPONSE_MEDIATYPE_PREFERRED_CONFIG_DEFAULT, Importance.HIGH,
                RESPONSE_MEDIATYPE_PREFERRED_CONFIG_DOC)
        .define(RESPONSE_MEDIATYPE_DEFAULT_CONFIG, Type.STRING,
                RESPONSE_MEDIATYPE_DEFAULT_CONFIG_DEFAULT, Importance.HIGH,
                RESPONSE_MEDIATYPE_DEFAULT_CONFIG_DOC);
  }

  public RestConfig() {
    super(config, new TreeMap<Object,Object>());
  }

  public RestConfig(Map<?, ?> props) {
    super(config, props);
  }

  public static void main(String[] args) {
    System.out.println(config.toHtmlTable());
  }
}
