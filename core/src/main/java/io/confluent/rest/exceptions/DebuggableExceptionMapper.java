/*
 * Copyright 2014 Confluent Inc.
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

import io.confluent.rest.RestConfig;
import io.confluent.rest.entities.ErrorMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract exception mapper that checks the debug flag and generates an error message including the
 * stack trace if it is enabled.
 */
@Provider
public abstract class DebuggableExceptionMapper<E extends Throwable> implements ExceptionMapper<E> {

  RestConfig restConfig;
  private static final Logger log = LoggerFactory.getLogger(DebuggableExceptionMapper.class);

  @Context
  HttpHeaders headers;

  public static class HttpStatus implements Response.StatusType {
    private final int code;
    private final String reason;
    private final Response.Status.Family family;

    public HttpStatus(int statusCode, String reasonPhrase) {

      // Sanity check to make sure status code is within valid range
      if (statusCode < 0 || statusCode > 599) {
        throw new IllegalArgumentException("Http status code is not within valid range:"
                + "statusCode: " + Integer.toString(statusCode));
      }

      this.code = statusCode;
      this.reason = reasonPhrase;
      this.family = Response.Status.Family.familyOf(statusCode);
    }

    public HttpStatus(Response.StatusType status) {
      this.code = status.getStatusCode();
      this.reason = status.getReasonPhrase();
      this.family = status.getFamily();
    }

    public Response.Status.Family getFamily() {
      return this.family;
    }

    public int getStatusCode() {
      return this.code;
    }

    public String getReasonPhrase() {
      return this.toString();
    }

    public String toString() {
      return this.reason;
    }
  }

  public DebuggableExceptionMapper(RestConfig restConfig) {
    this.restConfig = restConfig;
  }

  /**
   * Create a Response object using the given exception, status, and message. When debugging is
   * enabled, the message will be replaced with the exception class, exception message, and
   * stacktrace.
   *
   * @param exc    Throwable that triggered this ExceptionMapper
   * @param status HTTP response status
   */
  public Response.ResponseBuilder createResponse(Throwable exc, int errorCode,
                                                 Response.StatusType status, String msg) {
    log.error("Request Failed with exception " ,  exc);
    String readableMessage = msg;
    if (restConfig != null && restConfig.getBoolean(RestConfig.DEBUG_CONFIG)) {
      readableMessage += " " + exc.getClass().getName() + ": " + exc.getMessage();
      try {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        PrintStream stream = new PrintStream(os, false, StandardCharsets.UTF_8.name());
        exc.printStackTrace(stream);
        stream.close();
        os.close();
        readableMessage += System.lineSeparator() + os.toString(StandardCharsets.UTF_8.name());
      } catch (IOException e) {
        // Ignore
      }
    }
    final ErrorMessage message = new ErrorMessage(errorCode, readableMessage);

    return Response.status(status)
        .entity(message);
  }

}
