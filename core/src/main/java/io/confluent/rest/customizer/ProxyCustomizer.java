/*
 * Copyright 2025 Confluent Inc.
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

package io.confluent.rest.customizer;

import io.confluent.rest.ProxyConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;

import java.net.InetSocketAddress;

/**
 * Similar to {@link org.eclipse.jetty.server.ProxyCustomizer} but allowing access to tlvs as well
 */
public class ProxyCustomizer implements HttpConfiguration.Customizer {

  // The remote address attribute name.
  public static final String REMOTE_ADDRESS_ATTRIBUTE_NAME =
      "io.confluent.rest.proxy.remote.address";

  // The remote port attribute name.
  public static final String REMOTE_PORT_ATTRIBUTE_NAME =
      "io.confluent.rest.proxy.remote.port";

  // The local address attribute name.
  public static final String LOCAL_ADDRESS_ATTRIBUTE_NAME
      = "io.confluent.rest.proxy.local.address";

  // The local port attribute name.
  public static final String LOCAL_PORT_ATTRIBUTE_NAME
      = "io.confluent.rest.proxy.local.port";

  // The tlvs attribute name.
  // With value is an instance of {@link TlvProvider} that can be used to retrieve TLVs
  public static final String TLV_PROVIDER_ATTRIBUTE_NAME = "io.confluent.rest.proxy.tlv.provider";

  @Override
  public void customize(Connector connector, HttpConfiguration httpConfiguration, Request request) {
    EndPoint endPoint = request.getHttpChannel().getEndPoint();
    // The EndPoint wrapping order is reversed of the connection factory order.
    if (endPoint instanceof SslConnection.DecryptedEndPoint) {
      if (endPoint.getTransport() instanceof EndPoint) {
        endPoint = (EndPoint) endPoint.getTransport();
      }
    }

    if (endPoint instanceof ProxyConnectionFactory.ProxyEndPoint) {
      ProxyConnectionFactory.ProxyEndPoint proxyEndPoint =
          (ProxyConnectionFactory.ProxyEndPoint) endPoint;
      InetSocketAddress inetLocal = proxyEndPoint.getLocalAddress();
      InetSocketAddress inetRemote = proxyEndPoint.getRemoteAddress();
      String localAddress = inetLocal == null
          ? null : inetLocal.getAddress().getHostAddress();
      String remoteAddress = inetRemote == null
          ? null : inetRemote.getAddress().getHostAddress();
      if (remoteAddress != null) {
        request.setAttribute(REMOTE_ADDRESS_ATTRIBUTE_NAME, remoteAddress);
      }
      if (localAddress != null) {
        request.setAttribute(LOCAL_ADDRESS_ATTRIBUTE_NAME, localAddress);
      }

      int localPort = inetLocal == null ? 0 : inetLocal.getPort();
      int remotePort = inetRemote == null ? 0 : inetRemote.getPort();
      request.setAttribute(REMOTE_PORT_ATTRIBUTE_NAME, remotePort);
      request.setAttribute(LOCAL_PORT_ATTRIBUTE_NAME, localPort);

      TlvProvider tlvProvider = proxyEndPoint::getTLV;
      request.setAttribute(TLV_PROVIDER_ATTRIBUTE_NAME, tlvProvider);
    }
  }

}
