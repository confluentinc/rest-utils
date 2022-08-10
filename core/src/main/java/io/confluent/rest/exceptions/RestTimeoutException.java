/*
 * Copyright 2021 Confluent Inc.
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

public class RestTimeoutException extends RestException {

  public static final int DEFAULT_ERROR_CODE = Response.Status.REQUEST_TIMEOUT.getStatusCode();

  public RestTimeoutException(String message, int status, int errorCode) {
    super(message, status, errorCode);
  }

  public RestTimeoutException(String message, int status, int errorCode, Throwable cause) {
    super(message, status, errorCode, cause);
  }
}
