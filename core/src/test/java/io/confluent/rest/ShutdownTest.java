/**
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
 **/

package io.confluent.rest;

import org.junit.Test;

import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.Configurable;

import static org.junit.Assert.*;

public class ShutdownTest {
  @Test
  public void testShutdownHook() throws Exception {
    Properties props = new Properties();
    props.put("shutdown.graceful.ms", "50");
    // Override normal port to 0 for unit tests to use a random, available, ephemeral port that
    // won't conflict with locally running services or parallel tests
    props.put(RestConfig.PORT_CONFIG, "0");
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
    props.put("shutdown.graceful.ms", "50");
    // Override normal port to 0 for unit tests to use a random, available, ephemeral port that
    // won't conflict with locally running services or parallel tests
    props.put(RestConfig.PORT_CONFIG, "0");
    final TestRestConfig config = new TestRestConfig(props);
    ShutdownApplication app = new ShutdownApplication(config);
    app.start();

    RequestThread req = new RequestThread(app.localPorts().get(0));
    req.start();
    app.resource.requestProcessingStarted.await();

    StopThread stop = new StopThread(app);
    stop.start();

    app.join();

    assertTrue(req.finished);
    assertEquals("done", req.response);
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

  private static class StopThread extends Thread {
    ShutdownApplication app;

    StopThread(ShutdownApplication app) {
      this.app = app;
    }

    @Override
    public void run() {
      try {
        app.stop();
      } catch (Exception e) {
      }
    }
  };

  private static class RequestThread extends Thread {
    int port;
    boolean finished = false;
    String response = null;

    RequestThread(int port) {
      this.port = port;
    }
    @Override
    public void run() {
      // It seems that the server isn't necessarily listening when start() returns, which makes it
      // difficult to know when it is safe to make this request. Just retry until we're able to make
      // the request.
      while(true) {
        try {
          Client client = ClientBuilder.newClient();
          response = client
              .target("http://localhost:" + port)
              .path("/")
              .request()
              .get(String.class);
          finished = true;
          return;
        } catch (javax.ws.rs.ProcessingException e) {
          // ignore and retry
        }
      }
    }
  };

}