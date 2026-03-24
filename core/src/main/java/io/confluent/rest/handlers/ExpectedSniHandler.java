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

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class ExpectedSniHandler extends Handler.Wrapper {
  private static final Logger log = LoggerFactory.getLogger(ExpectedSniHandler.class);
  private final List<String> expectedSniHeaders;

  public ExpectedSniHandler(List<String> expectedSniHeaders) {
    this.expectedSniHeaders = expectedSniHeaders;
  }

  @Override
  public boolean handle(Request baseRequest,
                     Response response,
                     Callback callback) throws Exception {
    String sniServerName = SniUtils.getSniServerName(baseRequest);
    if (sniServerName == null) {
      log.warn("No SNI header present on request; request URI is {}", baseRequest.getHttpURI());
    } else if (!expectedSniHeaders.contains(sniServerName)) {
      log.warn("SNI header {} is not in the configured list of expected headers {}; "
          + "request URI is {}", sniServerName, this.expectedSniHeaders, baseRequest.getHttpURI());
    }
    return super.handle(baseRequest, response, callback);
  }
}
