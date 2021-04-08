/**
 * Copyright 2020 Confluent Inc.
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
 *
 */

package io.confluent.rest.auth;

import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigDef;

import java.util.Map;
import java.util.TreeMap;

public class OpenIdConnectConfig extends AbstractConfig {
  private static ConfigDef config;

  public static final String AUTHENTICATION_OIDC_CLIENT_ID = "authentication.oidc.client.id";
  public static final String AUTHENTICATION_OIDC_CLIENT_ID_DOC = "The client id as registered "
          + "with the configured OIDC Authorization Service provider.";
  public static final String AUTHENTICATION_OIDC_CLIENT_ID_DEFAULT = "RestUtils";

  public static final String AUTHENTICATION_OIDC_CLIENT_SECRET =
          "authentication.oidc.client.secret";
  public static final String AUTHENTICATION_OIDC_CLIENT_SECRET_DOC =
          "The client secret used for authenticating "
          + "to the Authorization Service.";
  public static final String AUTHENTICATION_OIDC_CLIENT_SECRET_DEFAULT = "";

  public static final String AUTHENTICATION_OIDC_PROVIDER =
          "authentication.oidc.provider";
  public static final String AUTHENTICATION_OIDC_PROVIDER_DOC =
          "Base URL for the Authorization Service provder. "
          + "This must match your Authorization Servers Issuer value exactly; "
          + "including trailing slashes if present."
          + "If you are unsure check your <your provider url>/.well-known/openid-configuration";
  public static final String AUTHENTICATION_OIDC_PROVIDER_DEFAULT = "";


  public static final String AUTHENTICATION_ERROR_PATH = "authentication.error.path";
  public static final String AUTHENTICATION_ERROR_PATH_DOC = "Path to redirect users to "
          + "upon authentication failure";
  public static final String AUTHENTICATION_ERROR_PATH_DEFAULT = "/";

  static {
    config = new ConfigDef()
            .define(
                    AUTHENTICATION_OIDC_CLIENT_ID,
                    ConfigDef.Type.STRING,
                    AUTHENTICATION_OIDC_CLIENT_ID_DEFAULT,
                    ConfigDef.Importance.MEDIUM,
                    AUTHENTICATION_OIDC_CLIENT_ID_DOC
            ).define(
                    AUTHENTICATION_OIDC_CLIENT_SECRET,
                    ConfigDef.Type.PASSWORD,
                    AUTHENTICATION_OIDC_CLIENT_SECRET_DEFAULT,
                    ConfigDef.Importance.MEDIUM,
                    AUTHENTICATION_OIDC_CLIENT_SECRET_DOC
            ).define(
                    AUTHENTICATION_OIDC_PROVIDER,
                    ConfigDef.Type.STRING,
                    AUTHENTICATION_OIDC_PROVIDER_DEFAULT,
                    ConfigDef.Importance.HIGH,
                    AUTHENTICATION_OIDC_PROVIDER_DOC
            ).define(
                    AUTHENTICATION_ERROR_PATH,
                    ConfigDef.Type.STRING,
                    AUTHENTICATION_ERROR_PATH_DEFAULT,
                    ConfigDef.Importance.HIGH,
                    AUTHENTICATION_ERROR_PATH_DOC);
  }


  public OpenIdConnectConfig(ConfigDef definition, Map<?, ?> originals) {
    super(definition, originals);
  }

  public OpenIdConnectConfig(ConfigDef definition) {
    super(definition, new TreeMap<>());
  }

  public OpenIdConnectConfig(Map<String, ?> originals) {
    super(config, originals);
  }
}
