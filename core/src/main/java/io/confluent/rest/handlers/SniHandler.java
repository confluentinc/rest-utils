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

import java.io.IOException;
import java.util.List;
import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLSession;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslConnection.DecryptedEndPoint;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SniHandler extends AbstractHandler {
  private static final Logger log = LoggerFactory.getLogger(SniHandler.class);

  @Override
  public void handle(String target, Request baseRequest,
      HttpServletRequest request,
      HttpServletResponse response) throws IOException, ServletException {
    String serverName = request.getServerName();
    String sniServerName = getSniServerName(baseRequest);
    if (sniServerName != null && !sniServerName.equals(serverName)) {
      log.error("Sni check failed, host header: {}, sni value: {}", serverName, sniServerName);
      baseRequest.setHandled(true);
      response.sendError(MISDIRECTED_REQUEST.getCode(), MISDIRECTED_REQUEST.getMessage());
    }
  }

  private static String getSniServerName(Request baseRequest) {
    EndPoint endpoint = baseRequest.getHttpChannel().getEndPoint();
    if (endpoint instanceof DecryptedEndPoint) {
      SSLSession session = ((DecryptedEndPoint) endpoint)
          .getSslConnection()
          .getSSLEngine()
          .getSession();
      if (session instanceof ExtendedSSLSession) {
        List<SNIServerName> servers = ((ExtendedSSLSession) session).getRequestedServerNames();
        if (servers != null) {
          return servers.stream()
              .findAny()
              .filter(SNIHostName.class::isInstance)
              .map(SNIHostName.class::cast)
              .map(SNIHostName::getAsciiName)
              .orElse(null);
        }
      }
    }
    return null;
  }
}
