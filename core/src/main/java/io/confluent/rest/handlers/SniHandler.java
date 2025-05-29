/*
 * Copyright 2014 - 2023 Confluent Inc.
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

import static org.eclipse.jetty.http.HttpStatus.Code.MISDIRECTED_REQUEST;


import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.server.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SniHandler extends Handler.Wrapper {
  private static final Logger log = LoggerFactory.getLogger(SniHandler.class);

  @Override
  public boolean handle(Request baseRequest,
      Response response,
      Callback callback) throws Exception {
    String serverName = Request.getServerName(baseRequest);
    String sniServerName = SniUtils.getSniServerName(baseRequest);
    log.debug("host header: {}, sni value: {}", serverName, sniServerName);

    if (sniServerName != null && !sniServerName.equals(serverName)) {
      log.debug("Sni check failed, host header: {}, sni value: {}", serverName, sniServerName);
      Response.writeError(baseRequest, response, callback,
          MISDIRECTED_REQUEST.getCode(), MISDIRECTED_REQUEST.getMessage());
    }
    return super.handle(baseRequest, response, callback);
  }
}