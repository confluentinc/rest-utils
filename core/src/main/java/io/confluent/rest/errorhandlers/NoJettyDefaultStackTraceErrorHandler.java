/*
 * Copyright 2022 Confluent Inc.
 */

package io.confluent.rest.errorhandlers;

import java.io.IOException;
import jakarta.servlet.RequestDispatcher;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ErrorHandler;

public class NoJettyDefaultStackTraceErrorHandler extends ErrorHandler {
  public NoJettyDefaultStackTraceErrorHandler() {
    super();
    setShowServlet(false);
  }

  @Override
  protected void generateAcceptableResponse(Request baseRequest, Request request,
      Response response, int code, String message) throws IOException {
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
