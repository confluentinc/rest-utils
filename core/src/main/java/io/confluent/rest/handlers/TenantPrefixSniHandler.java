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
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TenantPrefixSniHandler extends SniHandler {
    private static final Logger log = LoggerFactory.getLogger(TenantPrefixSniHandler.class);

    @Override
    public void handle(String target, Request baseRequest,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException, ServletException {
        String hostHeader = request.getServerName();
        String sniServerName = getSniServerName(baseRequest);
        
        if (sniServerName != null) {
            String lsrcId = getFirstPart(sniServerName);
            
            if (lsrcId == null || !hostHeader.startsWith(lsrcId)) {
                log.debug("SNI prefix check failed, host header: {}, sni lsrcId: {}, full sni: {}", 
                    hostHeader, lsrcId, sniServerName);
                baseRequest.setHandled(true);
                response.sendError(MISDIRECTED_REQUEST.getCode(), MISDIRECTED_REQUEST.getMessage());
                return;
            }
        }
        super.handle(target, baseRequest, request, response);
    }

    /**
     * Gets the lsrcId from SNI hostname.
     * For example:
     * "lsrc-123.us-east-1.aws.private.confluent.cloud" -> "lsrc-123"
     *
     * @param hostname The SNI hostname
     * @return The lsrcId, or null if hostname is null or doesn't contain a dot
     */
    private static String getFirstPart(String hostname) {
        if (hostname == null) {
            return null;
        }
        int dotIndex = hostname.indexOf('.');
        return dotIndex == -1 ? null : hostname.substring(0, dotIndex);
    }
} 