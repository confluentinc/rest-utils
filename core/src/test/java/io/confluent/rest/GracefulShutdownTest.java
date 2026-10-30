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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Configurable;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
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
 * Graceful shutdown lets in-flight requests finish within {@code shutdown.graceful.ms} over
 * HTTP/1.1 and HTTP/2, cuts them off at that deadline, and is not held up by idle connections.
 */
public class GracefulShutdownTest {

  private static final long GRACEFUL_MS = 10_000;
  // Longer than Jetty's 1s shutdown idle timeout, so the request has been quiet when stop starts.
  private static final long QUIET_BEFORE_STOP_MS = 1_500;
  private static final long SLOW_REQUEST_MS = 3_000;

  private SlowApplication app;
  private HTTP2Client http2Client;
  private HttpClient httpClient;

  @AfterEach
  public void tearDown() throws Exception {
    if (app != null) {
      try {
        app.stop();
      } catch (Exception e) {
        // Already stopped by the test.
      }
    }
    if (httpClient != null) {
      httpClient.stop();
    }
    if (http2Client != null) {
      http2Client.stop();
    }
  }

  @Test
  public void testQuietHttp2RequestFinishesDuringGracefulShutdown() throws Exception {
    Server server = startServer(GRACEFUL_MS, new Properties());
    Session session = connectHttp2(server, null);

    assertRequestFinishesWhileStopping(get(session, server, SLOW_REQUEST_MS));
  }

  @Test
  public void testQuietHttp2OverTlsRequestFinishesDuringGracefulShutdown() throws Exception {
    Server server = startServer(GRACEFUL_MS, tlsListener());
    Session session = connectHttp2(server, new SslContextFactory.Client(true));

    assertRequestFinishesWhileStopping(get(session, server, SLOW_REQUEST_MS));
  }

  @Test
  public void testHttp2GraceIsMeasuredFromShutdownNotFromLastActivity() throws Exception {
    long gracefulMs = 3_000;
    Server server = startServer(gracefulMs, new Properties());
    Session session = connectHttp2(server, null);
    CompletableFuture<String> response = get(session, server, 4_000);
    assertTrue(app.resource.started.await(10, TimeUnit.SECONDS), "request did not start");
    // Send the GOAWAY early, so the connection has been quiet for longer than the whole graceful
    // period when the server stops. The request needs only about 0.5s more after that.
    server.getConnectors()[0].getConnectionFactory(HTTP2CServerConnectionFactory.class)
        .getBean(SessionContainer.class).shutdown();
    Thread.sleep(gracefulMs + 500);

    CompletableFuture<Long> stop = stopAsync();

    assertEquals("done", response.get(10, TimeUnit.SECONDS));
    assertTrue(stop.get(10, TimeUnit.SECONDS) < gracefulMs);
  }

  @Test
  public void testHttp11RequestFinishesDuringGracefulShutdown() throws Exception {
    Server server = startServer(GRACEFUL_MS, new Properties());
    httpClient = new HttpClient();
    httpClient.start();

    CompletableFuture<ContentResponse> response = new CompletableResponseListener(
        httpClient.newRequest(server.getURI()).path("/slow").param("ms", "" + SLOW_REQUEST_MS))
        .send();

    assertRequestFinishesWhileStopping(response.thenApply(result -> {
      assertEquals(HttpVersion.HTTP_1_1, result.getVersion());
      assertEquals(200, result.getStatus());
      return result.getContentAsString();
    }));
  }

  @Test
  public void testHttp2RequestIsCutOffAtTheGracefulDeadline() throws Exception {
    long gracefulMs = 2_000;
    Server server = startServer(gracefulMs, new Properties());
    Session session = connectHttp2(server, null);
    CompletableFuture<String> response = get(session, server, 60_000);
    assertTrue(app.resource.started.await(10, TimeUnit.SECONDS), "request did not start");

    long stopMs = stopAsync().get(30, TimeUnit.SECONDS);

    assertTrue(stopMs >= gracefulMs, "stop() returned before the deadline: " + stopMs + "ms");
    assertTrue(stopMs < gracefulMs + 3_000, "stop() overran the deadline: " + stopMs + "ms");
    assertThrows(ExecutionException.class, () -> response.get(5, TimeUnit.SECONDS));
  }

  @Test
  public void testIdleHttp11ConnectionDoesNotDelayStop() throws Exception {
    Server server = startServer(GRACEFUL_MS, new Properties());
    httpClient = new HttpClient();
    httpClient.start();

    assertIdleConnectionDoesNotDelayStop(server, HttpVersion.HTTP_1_1);
  }

  @Test
  public void testIdleHttp2ConnectionDoesNotDelayStop() throws Exception {
    Server server = startServer(GRACEFUL_MS, new Properties());
    httpClient = new HttpClient(new HttpClientTransportOverHTTP2(new HTTP2Client()));
    httpClient.start();

    assertIdleConnectionDoesNotDelayStop(server, HttpVersion.HTTP_2);
  }

  private Server startServer(long gracefulMs, Properties props) throws Exception {
    props.put(RestConfig.SHUTDOWN_GRACEFUL_MS_CONFIG, Long.toString(gracefulMs));
    app = new SlowApplication(new TestRestConfig(props));
    Server server = app.createServer();
    server.start();
    return server;
  }

  private static Properties tlsListener() throws Exception {
    File keystore = File.createTempFile("GracefulShutdownTest-keystore", ".jks");
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

  private Session connectHttp2(Server server, SslContextFactory.Client ssl) throws Exception {
    http2Client = new HTTP2Client();
    if (ssl != null) {
      http2Client.addBean(ssl);
    }
    http2Client.start();
    URI uri = server.getURI();
    InetSocketAddress address = new InetSocketAddress(uri.getHost(), uri.getPort());
    CompletableFuture<Session> session = ssl == null
        ? http2Client.connect(address, new Session.Listener() {})
        : http2Client.connect(ssl, address, new Session.Listener() {});
    return session.get(10, TimeUnit.SECONDS);
  }

  private CompletableFuture<Long> stopAsync() {
    return CompletableFuture.supplyAsync(() -> {
      long start = System.nanoTime();
      try {
        app.stop();
      } catch (Exception e) {
        // A TimeoutException when requests outlive the graceful period.
      }
      return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    });
  }

  private void assertRequestFinishesWhileStopping(CompletableFuture<String> response)
      throws Exception {
    assertTrue(app.resource.started.await(10, TimeUnit.SECONDS), "request did not start");
    Thread.sleep(QUIET_BEFORE_STOP_MS);

    CompletableFuture<Long> stop = stopAsync();

    assertEquals("done", response.get(GRACEFUL_MS * 2, TimeUnit.MILLISECONDS));
    long stopMs = stop.get(GRACEFUL_MS * 2, TimeUnit.MILLISECONDS);
    assertTrue(stopMs < GRACEFUL_MS,
        "stop() should return once the request finishes, took " + stopMs + "ms");
  }

  private void assertIdleConnectionDoesNotDelayStop(Server server, HttpVersion version)
      throws Exception {
    ContentResponse response = httpClient.newRequest(server.getURI()).path("/slow")
        .param("ms", "0").send();
    assertEquals(version, response.getVersion());
    assertEquals(200, response.getStatus());

    long stopMs = stopAsync().get(GRACEFUL_MS * 2, TimeUnit.MILLISECONDS);
    assertTrue(stopMs < 3_000, "an idle connection should not delay stop(), took " + stopMs + "ms");
  }

  /**
   * Sends a GET on a new stream and completes with the response body, or exceptionally if the
   * stream is reset or fails. Uses the low-level API because, after a GOAWAY, {@link HttpClient}
   * may close the connection while still delivering a response it has fully received.
   */
  private static CompletableFuture<String> get(Session session, Server server, long ms) {
    CompletableFuture<String> body = new CompletableFuture<>();
    MetaData.Request request = new MetaData.Request("GET",
        HttpURI.from(server.getURI().resolve("/slow?ms=" + ms)), HttpVersion.HTTP_2,
        HttpFields.EMPTY);
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
    public String slow(@QueryParam("ms") long ms) throws InterruptedException {
      if (ms > 0) {
        started.countDown();
        // Writes nothing while it runs, so the connection stays quiet.
        Thread.sleep(ms);
      }
      return "done";
    }
  }
}
