/*
 * Copyright 2026 Confluent Inc.
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SslConfig#getAcceptedSpiffeIdPatterns()} (the SPIFFE-ID allowlist).
 */
final class SslConfigTest {

  private static SslConfig sslConfig(Map<String, Object> props) {
    return new SslConfig(new RestConfig(RestConfig.baseConfigDef(), props));
  }

  @Test
  void acceptedSpiffeIdPatterns_defaultsToEmpty() {
    assertTrue(sslConfig(new HashMap<>()).getAcceptedSpiffeIdPatterns().isEmpty(),
        "default (unset) must be an empty allowlist = accept-any-chained");
    assertTrue(SslConfig.defaultConfig().getAcceptedSpiffeIdPatterns().isEmpty());
  }

  @Test
  void acceptedSpiffeIdPatterns_parsesCommaSeparatedList() {
    Map<String, Object> props = new HashMap<>();
    props.put(RestConfig.SSL_SPIRE_ACCEPTED_SPIFFE_ID_PATTERNS_CONFIG,
        ".*/service-a/.*,.*/service-b/.*");
    assertEquals(
        List.of(".*/service-a/.*", ".*/service-b/.*"),
        sslConfig(props).getAcceptedSpiffeIdPatterns());
  }

  @Test
  void acceptedSpiffeIdPatterns_singleEntry() {
    Map<String, Object> props = new HashMap<>();
    props.put(RestConfig.SSL_SPIRE_ACCEPTED_SPIFFE_ID_PATTERNS_CONFIG, ".*/mixed");
    assertEquals(List.of(".*/mixed"), sslConfig(props).getAcceptedSpiffeIdPatterns());
  }
}
