/*
 * Copyright 2019 Confluent Inc.
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

package io.confluent.rest.contexts;

import javax.ws.rs.core.SecurityContext;

/**
 * Encapsulates extra security information that can injected into the REST calls for security
 * purposes.
 */
public class RestSecurityContext {
  private final SecurityContext securityContext;
  private final String bearerToken;

  public RestSecurityContext(final SecurityContext securityContext, final String bearerToken) {
    this.securityContext = securityContext;
    this.bearerToken = bearerToken;
  }

  public SecurityContext getSecurityContext() {
    return securityContext;
  }

  public String getBearerToken() {
    return bearerToken;
  }
}
