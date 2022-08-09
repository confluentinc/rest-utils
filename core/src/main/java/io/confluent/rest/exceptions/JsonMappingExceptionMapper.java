/*
 * Copyright 2021 - 2022 Confluent Inc.
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

import com.fasterxml.jackson.databind.JsonMappingException;
import io.confluent.rest.entities.ErrorMessage;

import javax.annotation.Priority;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

@Provider
@Priority(1)
public class JsonMappingExceptionMapper implements ExceptionMapper<JsonMappingException> {

  public static final int BAD_REQUEST_CODE = 400;
  // For the exception message, it contains information like `(for Object starting at
  // [Source: (org.glassfish.jersey.message.internal.ReaderInterceptorExecutor
  // $UnCloseableInputStream);
  // line: 1, column: 129])`, but we don't want to expose all these information to user,
  // so we need to use this splitter to hide source part of the information.
  public static final String MESSAGE_SOURCE_SPLITTER = "\\(for Object starting at";

  @Override
  public Response toResponse(JsonMappingException exception) {
    String originalMessage = exception.getOriginalMessage();
    String messageWithoutSource = originalMessage == null ? null :
            originalMessage.split(MESSAGE_SOURCE_SPLITTER)[0].trim();

    ErrorMessage message = new ErrorMessage(
        BAD_REQUEST_CODE,
            messageWithoutSource
    );

    return Response.status(BAD_REQUEST_CODE)
        .entity(message)
        .build();
  }
}
