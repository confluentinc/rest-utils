/*
 * Copyright 2014 - 2026 Confluent Inc.
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

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.server.Request;

import javax.net.ssl.SNIMatcher;
import javax.net.ssl.SSLEngine;
import java.util.Collection;

public class SniUtils {
  public static String getSniServerName(Request baseRequest) {
    EndPoint endpoint = baseRequest.getConnectionMetaData().getConnection().getEndPoint();
    if (endpoint instanceof SslConnection.SslEndPoint) {
      SSLEngine engine = ((SslConnection.SslEndPoint) endpoint)
          .getSslConnection()
          .getSSLEngine();
      Collection<SNIMatcher> matchers = engine.getSSLParameters().getSNIMatchers();
      if (matchers != null) {
        for (SNIMatcher matcher : matchers) {
          if (matcher instanceof CapturingSniMatcher) {
            return ((CapturingSniMatcher) matcher).getCapturedServerName();
          }
        }
      }
    }
    return null;
  }
}
