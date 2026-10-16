/*
 * Copyright 2014 Confluent Inc.
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

import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Configurable;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.junit.jupiter.api.Assertions.*;

public class ShutdownTest {
  private static final Logger log = LoggerFactory.getLogger(ShutdownTest.class);

  @Test
  public void testShutdownHook() throws Exception {
    Properties props = new Properties();
    props.put("shutdown.graceful.ms", "50");
    ShutdownApplication app = new ShutdownApplication(new TestRestConfig(props));
    app.start();

    StopThread stop = new StopThread(app);
    stop.start();

    app.join();
    assertTrue(app.shutdown.get());
  }

  @Test
  public void testGracefulShutdown() throws Exception {
    Properties props = new Properties();
    props.put("shutdown.graceful.ms", "150");
    final TestRestConfig config = new TestRestConfig(props);
    ShutdownApplication app = new ShutdownApplication(config);
    Server server = app.createServer();
    server.start();

    RequestThread req = new RequestThread(server.getURI());
    req.start();
    app.resource.requestProcessingStarted.await();

    StopThread stop = new StopThread(app);
    stop.start();

    server.join();
    log.info("Application finished");

    // The request thread may not quite be done yet. Wait on it, but only give it a small amount of extra time to finish
    // to validate that we actually completed the request promptly.
    req.join(50);

    assertTrue(req.finished);
    assertEquals("done", req.response);
  }

  @Test
  public void testShutdownHookRunsWhenGracefulShutdownTimesOut() throws Exception {
    Properties props = new Properties();
    props.put("shutdown.graceful.ms", "100");
    HangingApplication app = new HangingApplication(new TestRestConfig(props));
    Server server = app.createServer();
    server.start();

    // A single attempt: the request is expected to fail once the server stops, so don't retry.
    Client client = ClientBuilder.newClient();
    Thread req = new Thread(() -> {
      try {
        client.target(server.getURI()).path("/").request().get(String.class);
      } catch (jakarta.ws.rs.ProcessingException e) {
        log.info("Request ended by shutdown", e);
      }
    });
    req.start();

    try {
      assertTrue(app.resource.requestProcessingStarted.await(10, TimeUnit.SECONDS));
      // The request is still running when the graceful period ends, so the drain times out.
      assertThrows(TimeoutException.class, app::stop);

      Thread join = new Thread(() -> {
        try {
          app.join();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      join.setDaemon(true);
      join.start();
      join.join(10_000);

      assertEquals(1, app.shutdownCalls.get(), "onShutdown should run after a timed-out drain");
      assertFalse(join.isAlive(), "join() should return after a timed-out drain");

      // A second stop(), e.g. from Jetty's shutdown hook as well as the application, must not
      // shut the application down again.
      try {
        app.stop();
      } catch (Exception e) {
        log.info("Second stop() failed", e);
      }
      assertEquals(1, app.shutdownCalls.get(), "onShutdown should run only once");
    } finally {
      app.resource.release.countDown();
      req.join(10_000);
      client.close();
    }
  }


  private static class ShutdownApplication extends Application<TestRestConfig> {
    public AtomicBoolean shutdown = new AtomicBoolean(false);
    public SlowResource resource = new SlowResource();

    ShutdownApplication(TestRestConfig props) {
      super(props);
    }

    @Override
    public void onShutdown() {
      shutdown.set(true);
    }

    @Override
    public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
      config.register(resource);
    }
  }

  @Path("/")
  public static class SlowResource {
    public CountDownLatch requestProcessingStarted = new CountDownLatch(1);

    @GET
    public String test() throws InterruptedException {
      requestProcessingStarted.countDown();
      Thread.sleep(25);
      return "done";
    }
  }

  private static class HangingApplication extends Application<TestRestConfig> {
    public AtomicInteger shutdownCalls = new AtomicInteger();
    public HangingResource resource = new HangingResource();

    HangingApplication(TestRestConfig props) {
      super(props);
    }

    @Override
    public void onShutdown() {
      shutdownCalls.incrementAndGet();
    }

    @Override
    public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
      config.register(resource);
    }
  }

  @Path("/")
  public static class HangingResource {
    public CountDownLatch requestProcessingStarted = new CountDownLatch(1);
    public CountDownLatch release = new CountDownLatch(1);

    @GET
    public String test() throws InterruptedException {
      requestProcessingStarted.countDown();
      release.await();
      return "done";
    }
  }

  private static class StopThread extends Thread {
    ShutdownApplication app;

    StopThread(ShutdownApplication app) {
      this.app = app;
    }

    @Override
    public void run() {
      try {
        log.info("Requesting application stop");
        app.stop();
      } catch (Exception e) {
      }
    }
  };

  private static class RequestThread extends Thread {
    private final URI target;
    volatile boolean finished = false;
    String response = null;

    RequestThread(URI target) {
      this.target = checkNotNull(target);
    }
    @Override
    public void run() {
      // It seems that the server isn't necessarily listening when start() returns, which makes it
      // difficult to know when it is safe to make this request. Just retry until we're able to make
      // the request.
      while(true) {
        try {
          log.info("Starting client");
          Client client = ClientBuilder.newClient();
          response = client
              .target(target)
              .path("/")
              .request()
              .get(String.class);
          log.info("Marking request finished");
          finished = true;
          return;
        } catch (jakarta.ws.rs.ProcessingException e) {
          // ignore and retry
          log.info("Request failed, will retry", e);
        }
      }
    }
  };

}
