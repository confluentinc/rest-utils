/*
 * Copyright 2022 Confluent Inc.
 */

package io.confluent.rest.errorhandlers;

import java.io.IOException;
import jakarta.servlet.RequestDispatcher;

import org.eclipse.jetty.ee10.servlet.ServletContextRequest;
import org.eclipse.jetty.http.HttpStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.ErrorHandler;

public class NoJettyDefaultStackTraceErrorHandler extends ErrorHandler {
  public NoJettyDefaultStackTraceErrorHandler() {
    super();
    setShowServlet(false);
  }

  @Override
  protected void generateAcceptableResponse(ServletContextRequest baseRequest,
                                            HttpServletRequest request,
                                            HttpServletResponse response,
                                            int code, String message) throws IOException {
    // set Exception to null to avoid exposing stack trace to clients
    request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, null);
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
