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
 **/

package io.confluent.rest.exceptions;

import javax.ws.rs.WebApplicationException;

/*
 * RestException is a subclass of WebApplicationException that always includes an error code and
 * can be converted to the standard error format. It is automatically handled by the rest-utils
 * exception mappers. For convenience, it provides subclasses for a few of the most common
 * responses, e.g. NotFoundException. If you're using the standard error format, you
 * should prefer these exceptions to the normal JAX-RS ones since they provide better error
 * responses and force you to provide both the HTTP status code and a more specific error code.
 */
public class RestException extends WebApplicationException {

  private int errorCode;

  public RestException(final String message, final int status, final int errorCode) {
    this(message, status, errorCode, (Throwable) null);
  }

  public RestException(final String message, final int status, final int errorCode,
                final Throwable cause) {
    super(message, cause, status);
    this.errorCode = errorCode;
  }

  public int getStatus() {
    return getResponse().getStatus();
  }

  public int getErrorCode() {
    return errorCode;
  }
}
