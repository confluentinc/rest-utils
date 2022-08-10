/*
 * Copyright 2022 Confluent Inc.
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

import com.fasterxml.jackson.core.JsonParseException;
import io.confluent.rest.entities.ErrorMessage;

import javax.annotation.Priority;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

@Provider
@Priority(1)
public class JsonParseExceptionMapper implements ExceptionMapper<JsonParseException> {

  public static final int BAD_REQUEST_CODE = 400;

  @Override
  public Response toResponse(JsonParseException exception) {

    ErrorMessage message = new ErrorMessage(
        BAD_REQUEST_CODE,
            exception.getOriginalMessage()
    );

    return Response.status(BAD_REQUEST_CODE)
        .entity(message)
        .build();
  }
}
