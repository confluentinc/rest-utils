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

package io.confluent.rest.exceptions;

import javax.ws.rs.core.Response;

public class RestNotAuthorizedException extends RestException {

  public static final int DEFAULT_ERROR_CODE = Response.Status.UNAUTHORIZED.getStatusCode();

  public RestNotAuthorizedException(String message, int errorCode) {
    super(message, Response.Status.UNAUTHORIZED.getStatusCode(), errorCode);
  }

  public RestNotAuthorizedException(String message, int errorCode, Throwable cause) {
    super(message, Response.Status.UNAUTHORIZED.getStatusCode(), errorCode, cause);
  }
}
