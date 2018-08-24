/*
 * Copyright 2018 Confluent Inc.
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

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Interface implemented by custom Authenticators.
 */
public interface Authenticator {

  /**
   * Get the authentication method this authorizer can handle.
   *
   * @return the authentication method.
   */
  String getAuthMethod();

  /**
   * Called to authenticate a request.
   *
   * @param request the request to be authenticated
   * @param response the request to be authenticated
   * @return an {@link AuthenticationResult} indicating the state of authentication.
   * @throws RuntimeException any exception thrown will result in a 500 / "Internal Server Error"
   *     being returned to the client.
   */
  AuthenticationResult authenticate(HttpServletRequest request, HttpServletResponse response);

  /**
   * Called to stop the authenticator and release any resources.
   */
  default void shutdown() {}
}
