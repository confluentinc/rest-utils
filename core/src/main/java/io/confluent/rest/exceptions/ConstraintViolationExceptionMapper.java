/**
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

import javax.validation.ConstraintViolationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

import io.confluent.rest.entities.ErrorMessage;
import io.confluent.rest.validation.ConstraintViolations;

@Provider
public class ConstraintViolationExceptionMapper
    implements ExceptionMapper<ConstraintViolationException> {

  public static final int UNPROCESSABLE_ENTITY_CODE = 422;
  public static final Response.StatusType UNPROCESSABLE_ENTITY = new Response.StatusType() {
    @Override
    public int getStatusCode() {
      return UNPROCESSABLE_ENTITY_CODE;
    }

    @Override
    public Response.Status.Family getFamily() {
      return Response.Status.Family.CLIENT_ERROR;
    }

    @Override
    public String getReasonPhrase() {
      return "Unprocessable entity";
    }
  };

  @Override
  public Response toResponse(ConstraintViolationException exception) {
    final ErrorMessage message;
    if (exception instanceof RestConstraintViolationException) {
      RestConstraintViolationException restException = (RestConstraintViolationException)exception;
      message = new ErrorMessage(restException.getErrorCode(), restException.getMessage());
    } else {
      String violationMessage = ConstraintViolations.formatUntyped(
          exception.getConstraintViolations()
      );
      if (violationMessage == null || violationMessage.length() == 0) {
        violationMessage = exception.getMessage();
      }
      message = new ErrorMessage(
          UNPROCESSABLE_ENTITY_CODE,
          violationMessage
      );
    }

    return Response.status(UNPROCESSABLE_ENTITY_CODE)
        .entity(message)
        .build();
  }
}
