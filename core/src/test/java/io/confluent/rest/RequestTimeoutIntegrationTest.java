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

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Configurable;
import jakarta.ws.rs.core.MediaType;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class RequestTimeoutIntegrationTest {

  private ApplicationServer<TestRestConfig> server;

  @AfterEach
  public void tearDown() throws Exception {
    // Release any request that is still blocked so the server can shut down promptly.
    SlowResource.release();
    if (server != null) {
      server.stop();
    }
  }

  private void startServer(long requestTimeoutMs) throws Exception {
    startServer(requestTimeoutMs, false);
  }

  private void startServer(long requestTimeoutMs, boolean interruptOnTimeout) throws Exception {
    SlowResource.reset();
    Properties props = new Properties();
    props.setProperty(RestConfig.LISTENERS_CONFIG, "http://0.0.0.0:0");
    props.setProperty(RestConfig.REQUEST_TIMEOUT_MS_CONFIG, String.valueOf(requestTimeoutMs));
    props.setProperty(RestConfig.REQUEST_TIMEOUT_INTERRUPT_ENABLE_CONFIG,
        String.valueOf(interruptOnTimeout));

    TestRestConfig config = new TestRestConfig(props);
    server = new ApplicationServer<>(config);
    server.registerApplication(new TestApp(config, "/"));
    server.start();
  }

  @Test
  public void testSlowRequestTimesOutWith504() throws Exception {
    startServer(500);

    long start = System.currentTimeMillis();
    int status = makeGetRequest("/slow");
    long elapsed = System.currentTimeMillis() - start;

    assertEquals(504, status, "slow request should be aborted with a 504");
    // The endpoint blocks indefinitely (until released in teardown); the 504 must come back
    // from the scheduler thread well before that, proving the abort fires while the worker
    // thread is still blocked.
    assertTrue(elapsed < 5_000, "504 should be returned promptly, took " + elapsed + " ms");
  }

  @Test
  public void testIoBoundRequestTimesOutWith504() throws Exception {
    startServer(500);

    long start = System.currentTimeMillis();
    int status = makeGetRequest("/slow-io");
    long elapsed = System.currentTimeMillis() - start;

    assertEquals(504, status,
        "a long I/O-bound operation that blocks the worker thread should be aborted with a 504");
    // The handler sleeps far longer than the timeout; the 504 must come back from the scheduler
    // thread while the worker thread is still blocked in the I/O call.
    assertTrue(elapsed < 5_000, "504 should be returned promptly, took " + elapsed + " ms");
  }

  @Test
  public void testInterruptOnTimeoutReleasesInterruptibleThread() throws Exception {
    startServer(500, true); // interrupt-on-timeout enabled

    long start = System.currentTimeMillis();
    int status = makeGetRequest("/slow-io");

    assertEquals(504, status, "request should be aborted with a 504");

    // With interrupt enabled, the interruptible sleep should be released shortly after the
    // timeout instead of running its full 60s.
    long deadline = System.currentTimeMillis() + 5_000;
    while (SlowResource.ioEndMs == 0 && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
    long handlerDuration = SlowResource.ioEndMs - start;

    assertTrue(SlowResource.ioInterrupted,
        "worker thread should have been interrupted by the timeout");
    assertTrue(SlowResource.ioEndMs > 0 && handlerDuration < 5_000,
        "worker thread should be released shortly after the timeout (not run the full 60s); "
            + "handler ran ~" + handlerDuration + " ms");
  }

  @Test
  public void testFastRequestIsUnaffected() throws Exception {
    startServer(10_000);
    assertEquals(200, makeGetRequest("/fast"), "fast request should succeed normally");
  }

  private int makeGetRequest(final String path) throws Exception {
    final HttpGet httpget = new HttpGet(getListeners().get(0).toString() + path);
    try (CloseableHttpClient httpClient = HttpClients.createDefault();
         CloseableHttpResponse response = httpClient.execute(httpget)) {
      return response.getStatusLine().getStatusCode();
    }
  }

  private List<URL> getListeners() {
    return Arrays.stream(server.getConnectors())
        .filter(ServerConnector.class::isInstance)
        .map(ServerConnector.class::cast)
        .map(connector -> {
          try {
            String protocol = new HashSet<>(connector.getProtocols())
                .stream()
                .map(String::toLowerCase)
                .anyMatch(s -> s.equals("ssl")) ? "https" : "http";
            return new URL(protocol, "localhost", connector.getLocalPort(), "");
          } catch (final Exception e) {
            throw new RuntimeException("Malformed listener", e);
          }
        })
        .collect(Collectors.toList());
  }

  private static class TestApp extends Application<TestRestConfig> {
    TestApp(TestRestConfig config, String path) {
      super(config, path);
    }

    @Override
    public void setupResources(final Configurable<?> config, final TestRestConfig appConfig) {
      config.register(SlowResource.class);
    }
  }

  @Path("/")
  @Produces(MediaType.TEXT_PLAIN)
  public static class SlowResource {
    private static volatile CountDownLatch latch = new CountDownLatch(1);
    private static volatile Thread ioThread;
    static volatile boolean ioInterrupted;
    static volatile long ioEndMs;

    static void reset() {
      latch = new CountDownLatch(1);
      ioThread = null;
      ioInterrupted = false;
      ioEndMs = 0;
    }

    static void release() {
      latch.countDown();
      // Unblock the I/O-bound handler thread (if any) so teardown is prompt.
      Thread t = ioThread;
      if (t != null) {
        t.interrupt();
      }
    }

    @GET
    @Path("/slow")
    public String slow() throws InterruptedException {
      // Block until the test releases the latch (or give up after a generous bound so a stray
      // thread never lingers).
      latch.await(60, TimeUnit.SECONDS);
      return "slow";
    }

    @GET
    @Path("/slow-io")
    public String slowIo() {
      // Simulate a long, synchronous I/O-bound (interruptible) operation, e.g. a slow downstream
      // call, that occupies the worker thread well beyond the configured timeout.
      ioThread = Thread.currentThread();
      try {
        Thread.sleep(60_000);
      } catch (InterruptedException e) {
        ioInterrupted = true;
        Thread.currentThread().interrupt();
      } finally {
        ioEndMs = System.currentTimeMillis();
      }
      return "slow-io";
    }

    @GET
    @Path("/fast")
    public String fast() {
      return "fast";
    }
  }
}
