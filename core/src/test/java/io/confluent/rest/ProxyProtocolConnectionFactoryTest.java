/*
 * Copyright 2026 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.rest;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.ConnectionFactory.Detecting;
import org.eclipse.jetty.server.ConnectionFactory.Detecting.Detection;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.DetectorConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.ProxyConnectionFactory;
import org.eclipse.jetty.server.ProxyConnectionFactory.ProxyEndPoint;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Checks that {@link ProxyProtocolConnectionFactory#create} behaves exactly like Jetty's
 * {@link ProxyConnectionFactory}, except that PROXY V2 headers larger than Jetty's default read
 * buffer are accepted up to the configured maximum.
 */
public class ProxyProtocolConnectionFactoryTest {

  private static final String NEXT_PROTOCOL = "http/1.1";
  private static final int MAX_PROXY_HEADER = 8192;
  private static final int IDLE_TIMEOUT_MS = 1000;
  private static final int TEST_TLV_TYPE = 0xE0;

  private static final byte[] V2_SIGNATURE = {
      0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A
  };
  private static final int V2_PROXY = 0x21;
  private static final int V2_LOCAL = 0x20;
  private static final int V2_TCP4 = 0x11;
  private static final int V2_TCP6 = 0x21;
  private static final int V2_UNSPEC = 0x00;
  // Ports outside the usual ephemeral ranges, so they can't collide with real client ports.
  private static final int PROXIED_SRC_PORT = 1111;
  private static final int PROXIED_DST_PORT = 2222;

  private Server jettyServer;
  private Server confluentServer;

  @BeforeEach
  public void setUp() throws Exception {
    ProxyConnectionFactory jettyFactory = new ProxyConnectionFactory(NEXT_PROTOCOL);
    jettyFactory.setMaxProxyHeader(MAX_PROXY_HEADER);
    jettyServer = startServer(jettyFactory, false);
    confluentServer = startServer(
        ProxyProtocolConnectionFactory.create(NEXT_PROTOCOL, MAX_PROXY_HEADER), true);
  }

  @AfterEach
  public void tearDown() throws Exception {
    if (jettyServer != null) {
      jettyServer.stop();
    }
    if (confluentServer != null) {
      confluentServer.stop();
    }
  }

  /**
   * Jetty's factory is a {@link DetectorConnectionFactory} over its V1 and V2 factories that only
   * adds max header accessors. If a Jetty upgrade adds behaviour to it, this fails so that
   * {@link ProxyProtocolConnectionFactory} can be reviewed.
   */
  @Test
  public void jettyFactoryOnlyAddsMaxProxyHeaderAccessors() {
    assertEquals(DetectorConnectionFactory.class, ProxyConnectionFactory.class.getSuperclass());
    assertThat(overridableMethods(ProxyConnectionFactory.class),
        is(Set.of("getMaxProxyHeader", "setMaxProxyHeader")));
    assertThat(overridableMethods(ProxyProtocolConnectionFactory.class),
        is(Set.of("getMaxProxyHeader")));
  }

  @Test
  public void createMatchesJettyFactoryConfiguration() {
    ProxyConnectionFactory jetty = new ProxyConnectionFactory(NEXT_PROTOCOL);
    jetty.setMaxProxyHeader(MAX_PROXY_HEADER);
    ProxyProtocolConnectionFactory confluent =
        ProxyProtocolConnectionFactory.create(NEXT_PROTOCOL, MAX_PROXY_HEADER);

    assertThat(confluent.getProtocol(), is(jetty.getProtocol()));
    assertThat(confluent.getProtocols(), is(jetty.getProtocols()));
    assertThat(confluent.getInputBufferSize(), is(jetty.getInputBufferSize()));
    assertThat(confluent.getMaxProxyHeader(), is(jetty.getMaxProxyHeader()));

    List<Detecting> jettyDetecting = new ArrayList<>(jetty.getBeans(Detecting.class));
    List<Detecting> confluentDetecting = new ArrayList<>(confluent.getBeans(Detecting.class));
    assertThat(confluentDetecting.size(), is(jettyDetecting.size()));
    for (int i = 0; i < jettyDetecting.size(); i++) {
      Detecting expected = jettyDetecting.get(i);
      Detecting actual = confluentDetecting.get(i);
      assertThat(actual.getProtocol(), is(expected.getProtocol()));
      assertThat(actual.getProtocols(), is(expected.getProtocols()));
      for (byte[] input : detectionInputs()) {
        assertThat("detection of " + Arrays.toString(input),
            actual.detect(ByteBuffer.wrap(input)), is(expected.detect(ByteBuffer.wrap(input))));
      }
    }
    assertThat(confluent.detect(ByteBuffer.wrap(V2_SIGNATURE)),
        is(jetty.detect(ByteBuffer.wrap(V2_SIGNATURE))));
  }

  /**
   * The V1 connection acquires the same read buffer as Jetty's; the V2 connection acquires one
   * large enough for the maximum header, and is otherwise the same connection type.
   */
  @Test
  public void createOnlyEnlargesV2ReadBuffer() {
    ProxyConnectionFactory jetty = new ProxyConnectionFactory(NEXT_PROTOCOL);
    jetty.setMaxProxyHeader(MAX_PROXY_HEADER);
    ProxyProtocolConnectionFactory confluent =
        ProxyProtocolConnectionFactory.create(NEXT_PROTOCOL, MAX_PROXY_HEADER);
    List<Detecting> jettyDetecting = new ArrayList<>(jetty.getBeans(Detecting.class));
    List<Detecting> confluentDetecting = new ArrayList<>(confluent.getBeans(Detecting.class));

    for (int i = 0; i < jettyDetecting.size(); i++) {
      RecordingPool jettyPool = new RecordingPool();
      RecordingPool confluentPool = new RecordingPool();
      Connection jettyConnection = jettyDetecting.get(i)
          .newConnection(mockConnector(jettyPool), mock(EndPoint.class));
      Connection confluentConnection = confluentDetecting.get(i)
          .newConnection(mockConnector(ProxyProtocolConnectionFactory.wrapPool(confluentPool)),
              mock(EndPoint.class));

      assertEquals(jettyConnection.getClass(), confluentConnection.getClass());
      assertThat(jettyPool.sizes.size(), is(1));
      boolean v2 = jettyDetecting.get(i).detect(ByteBuffer.wrap(V2_SIGNATURE))
          == Detection.RECOGNIZED;
      int expectedSize = v2
          ? Math.max(jettyPool.sizes.get(0), 16 + MAX_PROXY_HEADER)
          : jettyPool.sizes.get(0);
      assertThat(confluentPool.sizes, is(List.of(expectedSize)));
    }
  }

  @Test
  public void wrappedPoolDoesNotChangeOtherAcquisitions() {
    RecordingPool pool = new RecordingPool();
    ProxyProtocolConnectionFactory.wrapPool(pool).acquire(123, false).release();
    assertThat(pool.sizes, is(List.of(123)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("sameBehaviourCases")
  public void createBehavesLikeJettyFactory(String name, byte[] prefix) throws Exception {
    String expected = send(jettyServer, prefix);
    String actual = send(confluentServer, prefix);
    assertThat(actual, is(expected));
  }

  /**
   * The only intended difference: a V2 header larger than Jetty's read buffer but within the
   * configured maximum. Jetty never finishes reading it and the request is never served. If this
   * starts passing for Jetty too, Jetty has been fixed and {@link ProxyProtocolConnectionFactory}
   * can be removed.
   */
  @Test
  public void createAcceptsV2HeaderLargerThanJettyReadBuffer() throws Exception {
    byte[] header = v2Header(V2_PROXY, V2_TCP4, tcp4Addresses(),
        tlv(TEST_TLV_TYPE, "large"), noopTlv(MAX_PROXY_HEADER - 12 - 8 - 3));
    assertThat(v2Length(header), is(MAX_PROXY_HEADER));

    assertThat(send(confluentServer, header),
        is("HTTP/1.1 200 OK\n192.168.0.1:1111 -> 192.168.0.2:2222 tlv=large proxied=true"));
    // Jetty either hangs or closes the connection, but never serves the request.
    assertThat(send(jettyServer, header), not(startsWith("HTTP/1.1 200")));
  }

  private static Stream<Arguments> sameBehaviourCases() {
    return Stream.of(
        Arguments.of("no PROXY header", new byte[0]),
        Arguments.of("v1 TCP4", ascii("PROXY TCP4 192.168.0.1 192.168.0.2 1111 2222\r\n")),
        Arguments.of("v1 TCP6", ascii("PROXY TCP6 2001:db8::1 2001:db8::2 1111 2222\r\n")),
        Arguments.of("v1 UNKNOWN", ascii("PROXY UNKNOWN\r\n")),
        Arguments.of("v1 malformed", ascii("PROXY TCP4 not-an-address\r\n")),
        Arguments.of("v2 PROXY TCP4 with TLV",
            v2Header(V2_PROXY, V2_TCP4, tcp4Addresses(), tlv(TEST_TLV_TYPE, "token"))),
        Arguments.of("v2 PROXY TCP6", v2Header(V2_PROXY, V2_TCP6, tcp6Addresses())),
        Arguments.of("v2 LOCAL", v2Header(V2_LOCAL, V2_UNSPEC)),
        Arguments.of("v2 header within Jetty read buffer",
            v2Header(V2_PROXY, V2_TCP4, tcp4Addresses(), tlv(TEST_TLV_TYPE, "token"),
                noopTlv(1500))),
        Arguments.of("v2 header over max",
            v2Header(V2_PROXY, V2_TCP4, tcp4Addresses(), noopTlv(MAX_PROXY_HEADER))),
        Arguments.of("v2 unsupported version",
            v2Header(0x11, V2_TCP4, tcp4Addresses())),
        Arguments.of("v2 truncated TLV",
            v2Header(V2_PROXY, V2_TCP4, tcp4Addresses(), new byte[] {(byte) TEST_TLV_TYPE, 0, 9}))
    );
  }

  private static List<byte[]> detectionInputs() {
    return List.of(
        new byte[0],
        ascii("PRO"),
        ascii("PROXY TCP4 192.168.0.1 192.168.0.2 1111 2222\r\n"),
        Arrays.copyOf(V2_SIGNATURE, 6),
        V2_SIGNATURE,
        v2Header(V2_PROXY, V2_TCP4, tcp4Addresses()),
        ascii("GET / HTTP/1.1\r\n"),
        new byte[] {0x16, 0x03, 0x01, 0x00, 0x10, 0x01, 0x00, 0x00, 0x0C, 0x03, 0x03, 0x00});
  }

  private static Set<String> overridableMethods(Class<?> type) {
    return Arrays.stream(type.getDeclaredMethods())
        .filter(m -> !m.isSynthetic())
        .filter(m -> !Modifier.isStatic(m.getModifiers()))
        .filter(m -> !Modifier.isPrivate(m.getModifiers()))
        .map(Method::getName)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  private static Server startServer(ConnectionFactory proxyFactory, boolean wrapPool)
      throws Exception {
    Server server = new Server();
    ByteBufferPool pool = wrapPool
        ? ProxyProtocolConnectionFactory.wrapPool(server.getByteBufferPool()) : null;
    ServerConnector connector = new ServerConnector(server, null, null, pool, 0, 1,
        proxyFactory, new HttpConnectionFactory());
    connector.setHost("localhost");
    connector.setPort(0);
    connector.setIdleTimeout(IDLE_TIMEOUT_MS);
    server.addConnector(connector);
    server.setHandler(new EchoHandler());
    server.start();
    return server;
  }

  /**
   * Sends {@code prefix} followed by an HTTP GET and returns the status line and body, with
   * real (non-proxied) ports replaced by placeholders, or {@code <closed>} if the server closed
   * the connection without a response.
   */
  private static String send(Server server, byte[] prefix) throws IOException {
    int serverPort = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    try (Socket socket = new Socket("localhost", serverPort)) {
      socket.setSoTimeout(5 * IDLE_TIMEOUT_MS);
      OutputStream output = socket.getOutputStream();
      output.write(prefix);
      output.write(ascii("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"));
      output.flush();

      ByteArrayOutputStream received = new ByteArrayOutputStream();
      try {
        InputStream input = socket.getInputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = input.read(chunk)) >= 0) {
          received.write(chunk, 0, read);
        }
      } catch (SocketTimeoutException e) {
        return "<no response>";
      } catch (IOException e) {
        // connection reset by the server
      }
      String response = received.toString(StandardCharsets.UTF_8);
      if (response.isEmpty()) {
        return "<closed>";
      }
      String statusLine = response.substring(0, response.indexOf("\r\n"));
      String body = response.substring(response.indexOf("\r\n\r\n") + 4);
      return (statusLine + "\n" + body)
          .replace(":" + socket.getLocalPort(), ":<client-port>")
          .replace(":" + serverPort, ":<server-port>");
    }
  }

  private static Connector mockConnector(ByteBufferPool pool) {
    Connector connector = mock(Connector.class);
    when(connector.getExecutor()).thenReturn(Runnable::run);
    when(connector.getByteBufferPool()).thenReturn(pool);
    when(connector.getConnectionFactory(NEXT_PROTOCOL)).thenReturn(new HttpConnectionFactory());
    return connector;
  }

  private static byte[] ascii(String s) {
    return s.getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] tcp4Addresses() {
    return ByteBuffer.allocate(12)
        .put(new byte[] {(byte) 192, (byte) 168, 0, 1})
        .put(new byte[] {(byte) 192, (byte) 168, 0, 2})
        .putShort((short) PROXIED_SRC_PORT)
        .putShort((short) PROXIED_DST_PORT)
        .array();
  }

  private static byte[] tcp6Addresses() {
    byte[] src = new byte[16];
    byte[] dst = new byte[16];
    src[0] = 0x20;
    src[1] = 0x01;
    src[15] = 1;
    dst[0] = 0x20;
    dst[1] = 0x01;
    dst[15] = 2;
    return ByteBuffer.allocate(36)
        .put(src)
        .put(dst)
        .putShort((short) PROXIED_SRC_PORT)
        .putShort((short) PROXIED_DST_PORT)
        .array();
  }

  private static byte[] tlv(int type, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    return ByteBuffer.allocate(3 + bytes.length)
        .put((byte) type)
        .putShort((short) bytes.length)
        .put(bytes)
        .array();
  }

  private static byte[] noopTlv(int valueLength) {
    // PP2_TYPE_NOOP
    return ByteBuffer.allocate(3 + valueLength)
        .put((byte) 0x04)
        .putShort((short) valueLength)
        .array();
  }

  private static byte[] v2Header(int versionAndCommand, int family, byte[]... parts) {
    int length = Arrays.stream(parts).mapToInt(p -> p.length).sum();
    ByteBuffer header = ByteBuffer.allocate(16 + length)
        .put(V2_SIGNATURE)
        .put((byte) versionAndCommand)
        .put((byte) family)
        .putShort((short) length);
    for (byte[] part : parts) {
      header.put(part);
    }
    return header.array();
  }

  private static int v2Length(byte[] header) {
    return ByteBuffer.wrap(header, 14, 2).getShort() & 0xFFFF;
  }

  private static final class EchoHandler extends Handler.Abstract {

    @Override
    public boolean handle(Request request, Response response, Callback callback) {
      EndPoint endPoint = request.getConnectionMetaData().getConnection().getEndPoint();
      String tlv = "-";
      if (endPoint instanceof ProxyEndPoint proxyEndPoint
          && proxyEndPoint.getTLV(TEST_TLV_TYPE) != null) {
        tlv = new String(proxyEndPoint.getTLV(TEST_TLV_TYPE), StandardCharsets.UTF_8);
      }
      String body = Request.getRemoteAddr(request) + ":" + Request.getRemotePort(request)
          + " -> " + Request.getLocalAddr(request) + ":" + Request.getLocalPort(request)
          + " tlv=" + tlv + " proxied=" + (endPoint instanceof ProxyEndPoint);
      response.setStatus(200);
      Content.Sink.write(response, true, body, callback);
      return true;
    }
  }

  private static final class RecordingPool extends ByteBufferPool.Wrapper {

    private final List<Integer> sizes = new ArrayList<>();

    private RecordingPool() {
      super(ByteBufferPool.NON_POOLING);
    }

    @Override
    public RetainableByteBuffer acquire(int size, boolean direct) {
      sizes.add(size);
      return super.acquire(size, direct);
    }
  }
}
