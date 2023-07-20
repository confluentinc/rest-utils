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
import com.google.common.annotations.VisibleForTesting;

import io.confluent.rest.entities.ErrorMessage;

import javax.annotation.Priority;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

@Provider
@Priority(1)
public class JsonMappingExceptionMapper implements ExceptionMapper<JsonMappingException> {

  public static final int BAD_REQUEST_CODE = 400;
  // For the exception message, it contains information like `(for Object starting
  // at
  // [Source: (org.glassfish.jersey.message.internal.ReaderInterceptorExecutor
  // $UnCloseableInputStream);
  // line: 1, column: 129])`, but we don't want to expose all these information to
  // user,
  // so we need to use this splitter to hide source part of the information.
  public static final String MESSAGE_SOURCE_SPLITTER = "\\(for Object starting at";

  @Override
  public Response toResponse(JsonMappingException exception) {
    ErrorMessage message = new ErrorMessage(BAD_REQUEST_CODE,
        sanitizeExceptionMessage(exception.getOriginalMessage()));

    return Response.status(BAD_REQUEST_CODE)
        .entity(message)
        .type(MediaType.APPLICATION_JSON_TYPE)
        .build();
  }

  @VisibleForTesting
  public String sanitizeExceptionMessage(String originalMessage) {
    if (originalMessage == null) {
      return null;
    }

    // First strip off any trailing details of the code generating the exception.
    // Example: `(for Object starting at [Source: (o.a.k.C); line: 1, column: 9])`
    String sanitizedMessage = originalMessage.split(MESSAGE_SOURCE_SPLITTER)[0].trim();

    // If the message contains a fully qualified Java class name, strip the package
    // name leaving the class name. For an inner class, retain just the inner class.

    // The exception messages use ` to enclose the Java class name.
    String[] fragments = sanitizedMessage.split("\\`", -1);
    if (fragments.length > 2) {
      // The class name is the second fragment
      String className = fragments[1];

      // Find the final . to indicate the end of the package name
      int lastDot = className.lastIndexOf('.');
      if ((lastDot != -1) && (lastDot != className.length() - 1)) {
        className = className.substring(lastDot + 1);
      }

      // Find the final $ to indicate the start of an inner class name
      int dollar = className.lastIndexOf('$');
      if ((dollar != -1) && (dollar != className.length() - 1)) {
        className = className.substring(dollar + 1);
      }

      fragments[1] = className;
      sanitizedMessage = String.join("`", fragments);
    }

    return sanitizedMessage;
  }
}
