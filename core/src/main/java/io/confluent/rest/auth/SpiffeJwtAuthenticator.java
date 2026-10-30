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

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.security.AuthenticationState;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/**
 * Authenticates requests carrying a SPIFFE JWT-SVID in an {@code Authorization: Bearer} header.
 *
 * <p>The token is handed to the configured {@link SpiffeJwtLoginService}, which validates it.
 * Requests without a bearer token, or whose token is rejected, get a 401 with a
 * {@code WWW-Authenticate: Bearer} challenge.
 */
public class SpiffeJwtAuthenticator extends LoginAuthenticator {

  public static final String AUTH_TYPE = "SPIFFE_JWT";

  private static final String BEARER_PREFIX = "bearer ";

  @Override
  public String getAuthenticationType() {
    return AUTH_TYPE;
  }

  @Override
  public AuthenticationState validateRequest(Request request, Response response,
                                             Callback callback) {
    String token = bearerToken(request.getHeaders().get(HttpHeader.AUTHORIZATION));
    if (token != null) {
      UserIdentity user = login(null, token, request, response);
      if (user != null) {
        return new UserAuthenticationSucceeded(getAuthenticationType(), user);
      }
    }

    if (response.isCommitted()) {
      return null;
    }
    response.getHeaders().put(HttpHeader.WWW_AUTHENTICATE,
        "Bearer realm=\"" + getLoginService().getName() + "\"");
    Response.writeError(request, response, callback, HttpStatus.UNAUTHORIZED_401);
    return AuthenticationState.SEND_FAILURE;
  }

  // The auth scheme is case-insensitive (RFC 7235, section 2.1).
  static String bearerToken(String authorizationHeader) {
    if (authorizationHeader == null
        || authorizationHeader.length() <= BEARER_PREFIX.length()
        || !authorizationHeader.regionMatches(true, 0, BEARER_PREFIX, 0,
            BEARER_PREFIX.length())) {
      return null;
    }
    String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
    return token.isEmpty() ? null : token;
  }
}
