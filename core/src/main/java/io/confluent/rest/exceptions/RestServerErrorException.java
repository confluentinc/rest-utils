package io.confluent.rest.exceptions;

import javax.ws.rs.ServerErrorException;
import javax.ws.rs.core.Response;

public class RestServerErrorException extends ServerErrorException {

  private int errorCode;

  public RestServerErrorException(String message, int errorCode) {
    this(message, errorCode, null);
  }

  public RestServerErrorException(String message, int errorCode, Throwable cause) {
    super(message, errorCode, cause);
    this.errorCode = errorCode;
  }

  public int getStatus() {
    return Response.Status.INTERNAL_SERVER_ERROR.getStatusCode();
  }

  public int getErrorCode() {
    return errorCode;
  }
}
