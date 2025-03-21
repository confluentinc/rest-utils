/*
 * Copyright 2022 Confluent Inc.
 */

package io.confluent.rest.errorhandlers;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.Callback;

public class StackTraceErrorHandler extends ErrorHandler {

  public StackTraceErrorHandler(boolean suppressErrors) {
    super();
    setShowCauses(!suppressErrors);
    setShowStacks(!suppressErrors);
  }

  public StackTraceErrorHandler() {
    this(true);
  }

  @Override
  protected boolean generateAcceptableResponse(Request request, Response response,
                                               Callback callback, String contentType,
                                               List<Charset> charsets,
                                               int code, String message,
                                               Throwable cause) throws IOException {
    return super.generateAcceptableResponse(request, response, callback,
        contentType, charsets, code, retrieveErrorMessage(code, message), cause);
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
