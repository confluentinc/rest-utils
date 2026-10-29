/*
 * Copyright 2026 Confluent Inc.
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

package io.confluent.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Configurable;
import org.apache.kafka.common.config.types.Password;
import org.apache.kafka.test.TestSslUtils;
import org.apache.kafka.test.TestSslUtils.CertificateBuilder;
import org.eclipse.jetty.client.CompletableResponseListener;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.SessionContainer;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A request still running when the server stops gets up to {@code shutdown.graceful.ms} to finish,
 * over HTTP/1.1 and HTTP/2 alike, and an idle keep-alive connection does not hold up the stop.
 */
public class ShutdownIdleTimeoutTest {

  // Long enough that a slow request is not cut off by anything but the graceful period.
  private static final long GRACEFUL_MS = 10_000;
  // Longer than Jetty's 1s default shutdown idle timeout, much shorter than GRACEFUL_MS.
  private static final long SLOW_REQUEST_MS = 3_000;
  // The request has sent nothing for longer than the 1s shutdown idle timeout when stop() starts.
  private static final long QUIET_BEFORE_STOP_MS = 1_500;

  private HttpClient client;
  private HTTP2Client http2Client;
  private SlowApplication app;

  @AfterEach
  public void tearDown() throws Exception {
    if (app != null) {
      try {
        app.stop();
      } catch (Exception e) {
        // Already stopped by the test.
      }
    }
    if (client != null) {
      client.stop();
    }
    if (http2Client != null) {
      http2Client.stop();
    }
  }

  @Test
  public void testHttp2RequestFinishesWithinGracefulPeriod() throws Exception {
    Server server = startServer();
    http2Client = new HTTP2Client();
    http2Client.start();
    URI uri = server.getURI();
    Session session = http2Client.connect(
        new InetSocketAddress(uri.getHost(), uri.getPort()), new Session.Listener() {})
        .get(10, TimeUnit.SECONDS);

    assertSlowRequestFinishesWhileStopping(getOverHttp2(session, uri.resolve("/slow")));
  }

  @Test
  public void testHttp2RequestFinishesWhenTheGoAwayIsSentLongBeforeTheConnectorShutsDown()
      throws Exception {
    Server server = startServer();
    http2Client = new HTTP2Client();
    http2Client.start();
    URI uri = server.getURI();
    Session session = http2Client.connect(
        new InetSocketAddress(uri.getHost(), uri.getPort()), new Session.Listener() {})
        .get(10, TimeUnit.SECONDS);
    CompletableFuture<String> responseBody = getOverHttp2(session, uri.resolve("/slow"));
    assertTrue(app.resource.started.await(10, TimeUnit.SECONDS), "request did not start");
    // When the server stops, Jetty sends the GOAWAY just before the connector shuts down. Send it
    // now instead, so the session has been closing and idle for longer than the shutdown idle
    // timeout when the connector shuts down.
    server.getConnectors()[0].getConnectionFactory(HTTP2CServerConnectionFactory.class)
        .getBean(SessionContainer.class).shutdown();

    assertSlowRequestFinishesWhileStopping(responseBody);
  }

  @Test
  public void testHttp2OverTlsRequestFinishesWithinGracefulPeriod() throws Exception {
    Server server = startServer(tlsListener());
    SslContextFactory.Client trustAll = new SslContextFactory.Client(true);
    http2Client = new HTTP2Client();
    http2Client.addBean(trustAll);
    http2Client.start();
    URI uri = server.getURI();
    Session session = http2Client.connect(trustAll,
        new InetSocketAddress(uri.getHost(), uri.getPort()), new Session.Listener() {})
        .get(10, TimeUnit.SECONDS);

    assertSlowRequestFinishesWhileStopping(getOverHttp2(session, uri.resolve("/slow")));
  }

  @Test
  public void testHttp11RequestFinishesWithinGracefulPeriod() throws Exception {
    Server server = startServer();
    client = new HttpClient();
    client.start();

    CompletableFuture<ContentResponse> response =
        new CompletableResponseListener(client.newRequest(server.getURI()).path("/slow")).send();
    assertSlowRequestFinishesWhileStopping(response.thenApply(result -> {
      assertEquals(HttpVersion.HTTP_1_1, result.getVersion());
      assertEquals(200, result.getStatus());
      return result.getContentAsString();
    }));
  }

  @Test
  public void testIdleHttp11ConnectionDoesNotDelayStop() throws Exception {
    Server server = startServer();
    client = new HttpClient();
    client.start();

    assertIdleConnectionDoesNotDelayStop(server, HttpVersion.HTTP_1_1);
  }

  @Test
  public void testIdleHttp2ConnectionDoesNotDelayStop() throws Exception {
    Server server = startServer();
    client = new HttpClient(new HttpClientTransportOverHTTP2(new HTTP2Client()));
    client.start();

    assertIdleConnectionDoesNotDelayStop(server, HttpVersion.HTTP_2);
  }

  private Server startServer() throws Exception {
    return startServer(new Properties());
  }

  private Server startServer(Properties props) throws Exception {
    props.put(RestConfig.SHUTDOWN_GRACEFUL_MS_CONFIG, Long.toString(GRACEFUL_MS));
    app = new SlowApplication(new TestRestConfig(props));
    Server server = app.createServer();
    server.start();
    return server;
  }

  /** An HTTPS listener with a self-signed certificate; HTTP/2 is negotiated with ALPN. */
  private static Properties tlsListener() throws Exception {
    File keystore = File.createTempFile("ShutdownIdleTimeoutTest-keystore", ".jks");
    keystore.deleteOnExit();
    String password = "test-password";
    KeyPair keyPair = TestSslUtils.generateKeyPair("RSA");
    X509Certificate cert = new CertificateBuilder(30, "SHA256withRSA")
        .sanDnsNames("localhost")
        .generate("CN=localhost", keyPair);
    TestSslUtils.createKeyStore(keystore.getPath(), new Password(password),
        new Password(password), "server", keyPair.getPrivate(), cert);

    Properties props = new Properties();
    props.put(RestConfig.LISTENERS_CONFIG, "https://localhost:0");
    props.put(RestConfig.SSL_KEYSTORE_LOCATION_CONFIG, keystore.getAbsolutePath());
    props.put(RestConfig.SSL_KEYSTORE_PASSWORD_CONFIG, password);
    props.put(RestConfig.SSL_KEY_PASSWORD_CONFIG, password);
    return props;
  }

  /**
   * Waits until the slow request is running and has sent nothing for longer than the shutdown idle
   * timeout, stops the server, and checks that the request still gets its whole response.
   */
  private void assertSlowRequestFinishesWhileStopping(CompletableFuture<String> responseBody)
      throws Exception {
    assertTrue(app.resource.started.await(10, TimeUnit.SECONDS), "request did not start");
    Thread.sleep(QUIET_BEFORE_STOP_MS);

    long stopStart = System.nanoTime();
    CompletableFuture<Void> stop = CompletableFuture.runAsync(() -> {
      try {
        app.stop();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });

    assertEquals("done", responseBody.get(GRACEFUL_MS * 2, TimeUnit.MILLISECONDS));
    stop.get(GRACEFUL_MS * 2, TimeUnit.MILLISECONDS);
    long stopMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopStart);
    assertTrue(stopMs < GRACEFUL_MS, "stop() should return once the request finishes, took "
        + stopMs + "ms");
  }

  /**
   * Sends a GET on its own HTTP/2 stream and returns the response body once the stream ends, or
   * fails if the server resets the stream.
   *
   * <p>This uses the low-level HTTP/2 API rather than {@link HttpClient}: after the server's GOAWAY,
   * {@link HttpClient} closes the connection as soon as the last stream ends, and that can abort
   * the response it is still handing over to the caller.
   */
  private static CompletableFuture<String> getOverHttp2(Session session, URI uri) {
    CompletableFuture<String> body = new CompletableFuture<>();
    MetaData.Request request =
        new MetaData.Request("GET", HttpURI.from(uri), HttpVersion.HTTP_2, HttpFields.EMPTY);
    session.newStream(new HeadersFrame(request, null, true), new Stream.Listener() {
      private final StringBuilder content = new StringBuilder();

      @Override
      public void onHeaders(Stream stream, HeadersFrame frame) {
        int status = ((MetaData.Response) frame.getMetaData()).getStatus();
        if (status != 200) {
          body.completeExceptionally(new AssertionError("status " + status));
        } else if (frame.isEndStream()) {
          body.complete(content.toString());
        } else {
          stream.demand();
        }
      }

      @Override
      public void onDataAvailable(Stream stream) {
        Stream.Data data = stream.readData();
        if (data == null) {
          stream.demand();
          return;
        }
        content.append(StandardCharsets.UTF_8.decode(data.frame().getByteBuffer()));
        boolean last = data.frame().isEndStream();
        data.release();
        if (last) {
          body.complete(content.toString());
        } else {
          stream.demand();
        }
      }

      @Override
      public void onReset(Stream stream, ResetFrame frame, Callback callback) {
        body.completeExceptionally(new AssertionError(
            "stream reset by the server: " + ErrorCode.toString(frame.getError(), null)));
        callback.succeeded();
      }

      @Override
      public void onFailure(Stream stream, int error, String reason, Throwable failure,
                            Callback callback) {
        body.completeExceptionally(failure);
        callback.succeeded();
      }
    }).exceptionally(failure -> {
      body.completeExceptionally(failure);
      return null;
    });
    return body;
  }

  private void assertIdleConnectionDoesNotDelayStop(Server server, HttpVersion version)
      throws Exception {
    // A completed request leaves the client's connection open and idle.
    ContentResponse result = client.newRequest(server.getURI()).path("/fast").send();
    assertEquals(version, result.getVersion());
    assertEquals(200, result.getStatus());

    long stopStart = System.nanoTime();
    app.stop();
    long stopMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopStart);
    assertTrue(stopMs < 3_000, "an idle connection should not hold up stop(), took "
        + stopMs + "ms");
  }

  private static class SlowApplication extends Application<TestRestConfig> {
    final SlowResource resource = new SlowResource();

    SlowApplication(TestRestConfig config) {
      super(config);
    }

    @Override
    public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
      config.register(resource);
    }
  }

  @Path("/")
  public static class SlowResource {
    final CountDownLatch started = new CountDownLatch(1);

    @GET
    @Path("/slow")
    public String slow() throws InterruptedException {
      started.countDown();
      // Sends nothing while it runs, so the connection is idle for the whole time.
      Thread.sleep(SLOW_REQUEST_MS);
      return "done";
    }

    @GET
    @Path("/fast")
    public String fast() {
      return "done";
    }
  }
}
