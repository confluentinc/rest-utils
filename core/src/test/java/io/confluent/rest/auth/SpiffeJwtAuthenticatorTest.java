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

package io.confluent.rest.auth;

import static io.confluent.rest.auth.SpiffeJwtAuthenticator.bearerToken;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

public class SpiffeJwtAuthenticatorTest {

  @Test
  public void extractsBearerToken() {
    assertEquals("abc.def.ghi", bearerToken("Bearer abc.def.ghi"));
  }

  @Test
  public void schemeIsCaseInsensitive() {
    assertEquals("abc", bearerToken("bearer abc"));
    assertEquals("abc", bearerToken("BEARER abc"));
  }

  @Test
  public void surroundingWhitespaceIsTrimmed() {
    assertEquals("abc", bearerToken("Bearer   abc  "));
  }

  @Test
  public void missingOrEmptyTokenYieldsNull() {
    assertNull(bearerToken(null));
    assertNull(bearerToken(""));
    assertNull(bearerToken("Bearer"));
    assertNull(bearerToken("Bearer "));
    assertNull(bearerToken("Bearer    "));
  }

  @Test
  public void otherSchemesYieldNull() {
    assertNull(bearerToken("Basic bmVoYTpha2Zhaw=="));
    assertNull(bearerToken("Bearerabc"));
  }
}
