/*
 * Copyright 2022 Confluent Inc.
 */

package io.confluent.rest.errorhandlers;

import java.io.IOException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ErrorHandler;

public class NoJettyDefaultStackTraceErrorHandler extends ErrorHandler {
  private static final String JAVAX_ERROR_EXCEPTION_ATTRIBUTE = "javax.servlet.error.exception";

  public NoJettyDefaultStackTraceErrorHandler() {
    super();
    setShowServlet(false);
  }

  @Override
  protected void generateAcceptableResponse(Request baseRequest, HttpServletRequest request,
      HttpServletResponse response, int code, String message) throws IOException {
    request.setAttribute(JAVAX_ERROR_EXCEPTION_ATTRIBUTE, null);
    super.generateAcceptableResponse(baseRequest, request, response, code,
        retrieveErrorMessage(code, message));
  }

  protected String retrieveErrorMessage(int code, String message) {
    switch (code) {
      case HttpStatus.INTERNAL_SERVER_ERROR_500:
        return HttpStatus.getMessage(code);
      default:
        return message;
    }
  }
}
