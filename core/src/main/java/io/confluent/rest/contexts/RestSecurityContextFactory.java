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

import org.glassfish.hk2.api.Factory;

import javax.inject.Inject;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.SecurityContext;
import java.util.List;

/**
 * This class implements {@link Factory}, which allows a REST application to create
 * a new {@link RestSecurityContext} during REST requests.
 */
public class RestSecurityContextFactory implements Factory<RestSecurityContext> {
  private static final String AUTHORIZATION_HEADER_NAME = "Authorization";
  private static final String AUTHORIZATION_BEARER_TYPE = "Bearer";
  private static final String EMPTY_BEARER_TOKEN = "";

  private final SecurityContext securityContext;
  private final HttpHeaders httpHeaders;

  @Inject
  public RestSecurityContextFactory(
      final SecurityContext securityContext,
      final HttpHeaders httpHeaders
  ) {
    this.securityContext = securityContext;
    this.httpHeaders = httpHeaders;
  }

  @Override
  public RestSecurityContext provide() {
    final String token = findBearerToken();
    return new RestSecurityContext(securityContext, token);
  }

  @Override
  public void dispose(final RestSecurityContext ksqlUserContext) {
    // unused
  }

  /**
   * Search and returns the BEARER token on the HTTP headers.
   */
  private String findBearerToken() {
    /*
     * The token is found on the Authorization header in the following string form:
     * Authorization: Bearer TOKEN
     */

    final List<String> authHeaders = httpHeaders.getRequestHeader(AUTHORIZATION_HEADER_NAME);
    if (authHeaders != null) {
      for (String authMethod : authHeaders) {
        if (authMethod.startsWith(AUTHORIZATION_BEARER_TYPE + " ")) {
          return authMethod.substring(AUTHORIZATION_BEARER_TYPE.length() + 1).trim();
        }
      }
    }

    return EMPTY_BEARER_TOKEN;
  }
}
