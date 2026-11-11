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

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.ProxyConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HashSet;
import java.util.Set;

/**
 * Similar to {@link org.eclipse.jetty.server.ProxyCustomizer} but allowing access to tlvs as well.
 *
 * <p>When an {@code acceptedIpRange} is configured, this customizer checks whether the
 * underlying (raw) peer IP falls within the CIDR range. If it does NOT, the PROXY protocol
 * data is ignored and the request's remote address is overridden to the raw TCP peer IP.
 * This prevents IP spoofing during v3/v4 migrations where some connections arrive through
 * a proxy (e.g., Envoy) and others come directly through a load balancer.</p>
 */
public class ProxyCustomizer implements HttpConfiguration.Customizer {

  private static final Logger log = LoggerFactory.getLogger(ProxyCustomizer.class);

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
  public static final String TLV_PROVIDER_ATTRIBUTE_NAME
      = "io.confluent.rest.proxy.tlv.provider";

  private final CidrRange acceptedIpRange;

  public ProxyCustomizer() {
    this(null);
  }

  public ProxyCustomizer(CidrRange acceptedIpRange) {
    this.acceptedIpRange = acceptedIpRange;
  }

  @Override
  public Request customize(Request request, HttpFields.Mutable mutable) {
    EndPoint endPoint = request.getConnectionMetaData().getConnection().getEndPoint();

    // The EndPoint wrapping order is reversed of the connection factory order.
    if (endPoint instanceof SslConnection.SslEndPoint) {
      endPoint = ((SslConnection.SslEndPoint) endPoint).unwrap();
    }

    if (endPoint instanceof ProxyConnectionFactory.ProxyEndPoint proxyEndPoint) {
      EndPoint underlyingEndpoint = proxyEndPoint.unwrap();

      // Check peer IP against accepted range before using PROXY data.
      // Fail closed: if the peer address cannot be evaluated, ignore PROXY data.
      if (acceptedIpRange != null) {
        SocketAddress rawRemote = underlyingEndpoint.getRemoteSocketAddress();
        if (!(rawRemote instanceof InetSocketAddress inetRemote)) {
          log.debug(
              "Raw remote address {} is not an InetSocketAddress;"
                  + " ignoring PROXY protocol data",
              rawRemote);
          return new RawPeerRequest(request, underlyingEndpoint);
        }

        InetAddress peerAddress = inetRemote.getAddress();
        if (peerAddress == null) {
          log.debug(
              "Raw remote InetSocketAddress {} has null InetAddress;"
                  + " ignoring PROXY protocol data",
              inetRemote);
          return new RawPeerRequest(request, underlyingEndpoint);
        }

        if (!acceptedIpRange.contains(peerAddress)) {
          log.debug(
              "Peer IP {} is not in accepted range, ignoring PROXY protocol data",
              peerAddress.getHostAddress());
          return new RawPeerRequest(request, underlyingEndpoint);
        }
      }

      request = new ProxyRequest(request,
          underlyingEndpoint.getLocalSocketAddress(),
          underlyingEndpoint.getRemoteSocketAddress(),
          proxyEndPoint::getTLV);
    }
    return request;
  }

  /**
   * Request wrapper that overrides the connection metadata to use raw TCP peer
   * addresses instead of the PROXY-parsed addresses. This effectively "undoes"
   * the {@link ProxyConnectionFactory.ProxyEndPoint}'s address override when
   * the peer is not in the accepted IP range.
   */
  private static class RawPeerRequest extends Request.Wrapper {
    private final ConnectionMetaData rawMetaData;

    private RawPeerRequest(Request request, EndPoint rawEndpoint) {
      super(request);
      this.rawMetaData = new ConnectionMetaData.Wrapper(
          request.getConnectionMetaData()) {
        @Override
        public SocketAddress getRemoteSocketAddress() {
          return rawEndpoint.getRemoteSocketAddress();
        }

        @Override
        public SocketAddress getLocalSocketAddress() {
          return rawEndpoint.getLocalSocketAddress();
        }
      };
    }

    @Override
    public ConnectionMetaData getConnectionMetaData() {
      return rawMetaData;
    }
  }

  private static class ProxyRequest extends Request.Wrapper {
    private final String remoteAddress;
    private final String localAddress;
    private final int remotePort;
    private final int localPort;
    private final TlvProvider tlvProvider;

    private ProxyRequest(Request request,
                         SocketAddress local,
                         SocketAddress remote,
                         TlvProvider tlvProvider) {
      super(request);
      InetSocketAddress inetLocal = local instanceof InetSocketAddress
          ? (InetSocketAddress) local : null;
      InetSocketAddress inetRemote = remote instanceof InetSocketAddress
          ? (InetSocketAddress) remote : null;
      this.localAddress = inetLocal == null ? null : inetLocal.getAddress().getHostAddress();
      this.remoteAddress = inetRemote == null ? null : inetRemote.getAddress().getHostAddress();
      this.localPort = inetLocal == null ? 0 : inetLocal.getPort();
      this.remotePort = inetRemote == null ? 0 : inetRemote.getPort();
      this.tlvProvider = tlvProvider;
    }

    @Override
    public Object getAttribute(String name) {
      return switch (name) {
        case REMOTE_ADDRESS_ATTRIBUTE_NAME -> remoteAddress;
        case REMOTE_PORT_ATTRIBUTE_NAME -> remotePort;
        case LOCAL_ADDRESS_ATTRIBUTE_NAME -> localAddress;
        case LOCAL_PORT_ATTRIBUTE_NAME -> localPort;
        case TLV_PROVIDER_ATTRIBUTE_NAME -> tlvProvider;
        default -> super.getAttribute(name);
      };
    }

    @Override
    public Set<String> getAttributeNameSet() {
      Set<String> names = new HashSet<>(super.getAttributeNameSet());
      names.remove(REMOTE_ADDRESS_ATTRIBUTE_NAME);
      names.remove(LOCAL_ADDRESS_ATTRIBUTE_NAME);

      if (remoteAddress != null) {
        names.add(REMOTE_ADDRESS_ATTRIBUTE_NAME);
      }
      if (localAddress != null) {
        names.add(LOCAL_ADDRESS_ATTRIBUTE_NAME);
      }

      names.add(REMOTE_PORT_ATTRIBUTE_NAME);
      names.add(LOCAL_PORT_ATTRIBUTE_NAME);
      names.add(TLV_PROVIDER_ATTRIBUTE_NAME);

      return names;
    }
  }
}
