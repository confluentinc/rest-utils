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
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TenantPrefixSniHandler extends SniHandler {

  private static final Logger log = LoggerFactory.getLogger(TenantPrefixSniHandler.class);
  private static final String DOT_SEPARATOR = ".";
  private static final String DASH_SEPARATOR = "-";

  @Override
  public void handle(String target, Request baseRequest,
      HttpServletRequest request,
      HttpServletResponse response) throws IOException, ServletException {
    String hostHeader = request.getServerName();
    String sniServerName = getSniServerName(baseRequest);

    if (sniServerName != null) {
      // Extract the tenantID from the sniServerName, which is always the first segment before '.'
      // Example: "lsrc-123.us-east-1.aws.private.confluent.cloud" → "lsrc-123"
      String tenantID = getFirstPart(sniServerName);
      // The logical cluster ID should also appear at the start of the hostHeader.
      // It may be followed by either a dot (.) or a dash (-).
      // hostHeader format examples:
      // - "lsrc-123-$dom<slug>.us-east-1.aws.glb.confluent.cloud"
      // - "lsrc-123.$dom<slug>.us-east-1.aws.aws.confluent.cloud"
      if (tenantID == null
              || !(hostHeader.startsWith(tenantID + DOT_SEPARATOR)
                  || hostHeader.startsWith(tenantID + DASH_SEPARATOR))) {
        log.debug("SNI prefix check failed, host header: {}, sni tenantId: {}, full sni: {}",
            hostHeader, tenantID, sniServerName);
        baseRequest.setHandled(true);
        response.sendError(MISDIRECTED_REQUEST.getCode(), MISDIRECTED_REQUEST.getMessage());
        return;
      }
    }
    super.handle(target, baseRequest, request, response);
  }

  private static String getFirstPart(String hostname) {
    if (hostname == null) {
      return null;
    }
    int dotIndex = hostname.indexOf(DOT_SEPARATOR);
    return dotIndex == -1 ? null : hostname.substring(0, dotIndex);
  }
} 