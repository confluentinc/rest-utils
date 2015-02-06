package io.confluent.rest.exceptions;

import javax.ws.rs.core.Response;

public class RestServerErrorException extends RestException {

  public static final int DEFAULT_ERROR_CODE =
      Response.Status.INTERNAL_SERVER_ERROR.getStatusCode();

  public RestServerErrorException(String message, int errorCode) {
    this(message, errorCode, null);
  }

  public RestServerErrorException(String message, int errorCode, Throwable cause) {
    super(message, Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), errorCode, cause);
  }
}
