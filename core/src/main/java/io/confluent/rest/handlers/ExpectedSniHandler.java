/*
 * Copyright 2014 - 2024 Confluent Inc.
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

package io.confluent.rest.handlers;

import static org.eclipse.jetty.http.HttpStatus.Code.BAD_REQUEST;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class ExpectedSniHandler extends Handler.Wrapper {
  private static final Logger log = LoggerFactory.getLogger(ExpectedSniHandler.class);
  private static final String INVALID_SNI_MESSAGE = "Invalid SNI";

  private final List<String> expectedSniHeaders;
  private final boolean rejectInvalidSniHeaders;

  public ExpectedSniHandler(List<String> expectedSniHeaders) {
    this(expectedSniHeaders, false);
  }

  public ExpectedSniHandler(List<String> expectedSniHeaders, boolean rejectInvalidSniHeaders) {
    this.expectedSniHeaders = expectedSniHeaders;
    this.rejectInvalidSniHeaders = rejectInvalidSniHeaders;
  }

  @Override
  public boolean handle(Request baseRequest,
                     Response response,
                     Callback callback) throws Exception {
    String sniServerName = SniUtils.getSniServerName(baseRequest);

    // Check whether the SNI is either missing or not in the valid list
    boolean invalid = false;
    if (sniServerName == null) {
      log.warn("No SNI header present on request; request URI is {}", baseRequest.getHttpURI());
      invalid = true;
    } else if (!expectedSniHeaders.contains(sniServerName)){
      log.warn("SNI header {} is not in the configured list of expected headers {}; "
              + "request URI is {}", sniServerName, expectedSniHeaders, baseRequest.getHttpURI());
      invalid = true;
    }

    // If the SNI is invalid and the rejection flag is set, return an error
    if (invalid && rejectInvalidSniHeaders) {
      Response.writeError(baseRequest, response, callback,
              BAD_REQUEST.getCode(), INVALID_SNI_MESSAGE);
      return true;
    }

    return super.handle(baseRequest, response, callback);
  }
}
