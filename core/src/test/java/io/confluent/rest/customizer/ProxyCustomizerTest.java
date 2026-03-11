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
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.ProxyConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ProxyCustomizerTest {

  /**
   * When no accepted IP range is configured, the customizer should always wrap
   * the request with ProxyRequest (existing behavior preserved).
   */
  @Test
  public void testNoRangeConfigured_alwaysWraps() {
    ProxyCustomizer customizer = new ProxyCustomizer();

    Request request = mock(Request.class);
    ConnectionMetaData connMeta = mock(ConnectionMetaData.class);
    Connection connection = mock(Connection.class);
    ProxyConnectionFactory.ProxyEndPoint proxyEndPoint =
        mock(ProxyConnectionFactory.ProxyEndPoint.class);
    EndPoint underlyingEndpoint = mock(EndPoint.class);

    when(request.getConnectionMetaData()).thenReturn(connMeta);
    when(connMeta.getConnection()).thenReturn(connection);
    when(connection.getEndPoint()).thenReturn(proxyEndPoint);
    when(proxyEndPoint.unwrap()).thenReturn(underlyingEndpoint);
    when(underlyingEndpoint.getLocalSocketAddress())
        .thenReturn(new InetSocketAddress("127.0.0.1", 8080));
    when(underlyingEndpoint.getRemoteSocketAddress())
        .thenReturn(new InetSocketAddress("192.168.1.100", 12345));

    HttpFields.Mutable headers = mock(HttpFields.Mutable.class);
    Request result = customizer.customize(request, headers);

    // Should be wrapped (different object from original request)
    assertNotSame(request, result);
    // The result should have proxy attributes set
    assertNotNull(result.getAttribute(ProxyCustomizer.REMOTE_ADDRESS_ATTRIBUTE_NAME));
  }

  /**
   * When accepted IP range is configured and the peer IP IS in the range,
   * the request should be wrapped with ProxyRequest.
   */
  @Test
  public void testPeerInRange_wraps() {
    CidrRange range = CidrRange.parse("10.240.0.0/16");
    ProxyCustomizer customizer = new ProxyCustomizer(range);

    Request request = mock(Request.class);
    ConnectionMetaData connMeta = mock(ConnectionMetaData.class);
    Connection connection = mock(Connection.class);
    ProxyConnectionFactory.ProxyEndPoint proxyEndPoint =
        mock(ProxyConnectionFactory.ProxyEndPoint.class);
    EndPoint underlyingEndpoint = mock(EndPoint.class);

    when(request.getConnectionMetaData()).thenReturn(connMeta);
    when(connMeta.getConnection()).thenReturn(connection);
    when(connection.getEndPoint()).thenReturn(proxyEndPoint);
    // The underlying endpoint's remote address is the peer (Envoy) IP
    when(proxyEndPoint.unwrap()).thenReturn(underlyingEndpoint);
    when(underlyingEndpoint.getRemoteSocketAddress())
        .thenReturn(new InetSocketAddress("10.240.5.10", 54321));
    when(underlyingEndpoint.getLocalSocketAddress())
        .thenReturn(new InetSocketAddress("127.0.0.1", 8080));

    HttpFields.Mutable headers = mock(HttpFields.Mutable.class);
    Request result = customizer.customize(request, headers);

    // Should be wrapped — peer is in the accepted range
    assertNotSame(request, result);
    assertNotNull(result.getAttribute(ProxyCustomizer.REMOTE_ADDRESS_ATTRIBUTE_NAME));
  }

  /**
   * When accepted IP range is configured and the peer IP is NOT in the range,
   * the request should be wrapped with RawPeerRequest that overrides the
   * connection metadata to use the raw TCP peer addresses. PROXY data
   * attributes (like TLV_PROVIDER) should NOT be set.
   */
  @Test
  public void testPeerNotInRange_wrapsWithRawPeerAddress() {
    CidrRange range = CidrRange.parse("10.240.0.0/16");
    ProxyCustomizer customizer = new ProxyCustomizer(range);

    InetSocketAddress rawRemoteAddr = new InetSocketAddress("192.168.1.100", 54321);
    InetSocketAddress rawLocalAddr = new InetSocketAddress("127.0.0.1", 8080);

    Request request = mock(Request.class);
    ConnectionMetaData connMeta = mock(ConnectionMetaData.class);
    Connection connection = mock(Connection.class);
    ProxyConnectionFactory.ProxyEndPoint proxyEndPoint =
        mock(ProxyConnectionFactory.ProxyEndPoint.class);
    EndPoint underlyingEndpoint = mock(EndPoint.class);

    when(request.getConnectionMetaData()).thenReturn(connMeta);
    when(connMeta.getConnection()).thenReturn(connection);
    when(connection.getEndPoint()).thenReturn(proxyEndPoint);
    // The underlying endpoint's remote address is outside the accepted range
    when(proxyEndPoint.unwrap()).thenReturn(underlyingEndpoint);
    when(underlyingEndpoint.getRemoteSocketAddress()).thenReturn(rawRemoteAddr);
    when(underlyingEndpoint.getLocalSocketAddress()).thenReturn(rawLocalAddr);

    HttpFields.Mutable headers = mock(HttpFields.Mutable.class);
    Request result = customizer.customize(request, headers);

    // Should be wrapped with RawPeerRequest (not the original request)
    assertNotSame(request, result);

    // TLV_PROVIDER should NOT be set (PROXY data ignored)
    assertNull(result.getAttribute(ProxyCustomizer.TLV_PROVIDER_ATTRIBUTE_NAME));

    // Connection metadata should return raw TCP addresses
    assertEquals(rawRemoteAddr, result.getConnectionMetaData().getRemoteSocketAddress());
    assertEquals(rawLocalAddr, result.getConnectionMetaData().getLocalSocketAddress());
  }

  /**
   * When the endpoint is not a ProxyEndPoint (no PROXY protocol),
   * the request should pass through unchanged regardless of range config.
   */
  @Test
  public void testNonProxyEndpoint_passesThrough() {
    CidrRange range = CidrRange.parse("10.240.0.0/16");
    ProxyCustomizer customizer = new ProxyCustomizer(range);

    Request request = mock(Request.class);
    ConnectionMetaData connMeta = mock(ConnectionMetaData.class);
    Connection connection = mock(Connection.class);
    EndPoint regularEndpoint = mock(EndPoint.class);

    when(request.getConnectionMetaData()).thenReturn(connMeta);
    when(connMeta.getConnection()).thenReturn(connection);
    when(connection.getEndPoint()).thenReturn(regularEndpoint);

    HttpFields.Mutable headers = mock(HttpFields.Mutable.class);
    Request result = customizer.customize(request, headers);

    // Should return the original request unchanged
    assertSame(request, result);
  }

  /**
   * When no accepted IP range is configured and endpoint is not a ProxyEndPoint,
   * the request should pass through unchanged.
   */
  @Test
  public void testNoRange_nonProxyEndpoint_passesThrough() {
    ProxyCustomizer customizer = new ProxyCustomizer();

    Request request = mock(Request.class);
    ConnectionMetaData connMeta = mock(ConnectionMetaData.class);
    Connection connection = mock(Connection.class);
    EndPoint regularEndpoint = mock(EndPoint.class);

    when(request.getConnectionMetaData()).thenReturn(connMeta);
    when(connMeta.getConnection()).thenReturn(connection);
    when(connection.getEndPoint()).thenReturn(regularEndpoint);

    HttpFields.Mutable headers = mock(HttpFields.Mutable.class);
    Request result = customizer.customize(request, headers);

    assertSame(request, result);
  }
}
