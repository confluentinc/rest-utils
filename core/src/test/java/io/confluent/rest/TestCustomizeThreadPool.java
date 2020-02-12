

/**
 * Copyright 2019 Confluent Inc.
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

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.Test;
import java.util.Properties;
import java.util.concurrent.RejectedExecutionException;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.MediaType;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpStatus.Code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;

public class TestCustomizeThreadPool {

  private static final Logger log = LoggerFactory.getLogger(TestCustomizeThreadPool.class);
  private static Object locker = new Object();
  private static int waitingTimeSec = 10*1000; //5 seconds

  /**
   * Good path testing.
   * Total number of running threads is less than capacity of thread pool configured.
   * Total number of jobs in queue is less than capacity of queue configured.
   */
  @Test
  public void testThreadPoolLessThreshold()throws Exception {
    int numOfClients = 3;
    TestCustomizeThreadPoolApplication app = new TestCustomizeThreadPoolApplication();
    String uri = app.getUri();
    try {
      app.start();
      makeConcurrentGetRequests(uri + "/custom/resource", numOfClients, app);
    } catch (Exception e) {
    } finally {
      log.info("Current running thread {}, maximum thread {}.", app.getServer().getThreads(), app.getServer().getMaxThreads());
      assertTrue("Total number of running threads is less than maximum number of threads " + app.getServer().getMaxThreads(),
              app.getServer().getThreads() - app.getServer().getMaxThreads() < 0);
      log.info("Total jobs in queue {}, capacity of queue {}.", app.getServer().getQueueSize(), app.getServer().getQueueCapacity());
      assertTrue("Total number of jobs in queue is less than capacity of queue " + app.getServer().getQueueCapacity(),
              app.getServer().getQueueSize() - app.getServer().getQueueCapacity() < 0);
      app.stop();
    }
  }

  /**
   * This test will test the number of running threads will be increased as more clients request coming in, but
   * the total number of threads will not over the maximum threads configured even there are more clients requests
   * coming in. Server will finally throw following exceptions when more http client send requests.
   * [ReservedThreadExecutor@1b1f5012{s=0/2,p=0}] rejected org.eclipse.jetty.io.ManagedSelector$Accept@26ac0324
   * (org.eclipse.jetty.util.thread.QueuedThreadPool:471)
   * java.util.concurrent.RejectedExecutionException:
   * This test also test the size of jobs in queue will not over the capacity of queue configured.
   **/
  @Test
  public void testThreadPoolReachThreshold()throws Exception {
    int numOfClients = 40;
    TestCustomizeThreadPoolApplication app = new TestCustomizeThreadPoolApplication();
    String uri = app.getUri();
    try {
      app.start();
      makeConcurrentGetRequests(uri + "/custom/resource", numOfClients, app);
    } catch (Exception e) {
    } finally {
      log.info("Current running thread {}, maximum thread {}.", app.getServer().getThreads(), app.getServer().getMaxThreads());
      assertTrue("Total number of running threads reach maximum number of threads " + app.getServer().getMaxThreads(),
              app.getServer().getThreads() - app.getServer().getMaxThreads() == 0);
      app.stop();
    }
  }

  /**
   * Simulate the case that the queue of thread pool is full. Http server will reject request if the queue is full and
   * throw RejectedExecutionException.
   **/
  @Test(expected = RejectedExecutionException.class)
  public void testQueueFull() throws Exception {
    int numOfClients = 1;
    Properties props = new Properties();
    props.put(RestConfig.LISTENERS_CONFIG, "http://localhost:8080");
    props.put(RestConfig.THREAD_POOL_MIN_CONFIG, "2");
    props.put(RestConfig.THREAD_POOL_MAX_CONFIG, "10");
    props.put(RestConfig.REQUEST_QUEUE_CAPACITY_INITIAL_CONFIG, "0");
    props.put(RestConfig.REQUEST_QUEUE_CAPACITY_CONFIG, "0");
    props.put(RestConfig.REQUEST_QUEUE_CAPACITY_GROWBY_CONFIG, "2");
    TestCustomizeThreadPoolApplication app = new TestCustomizeThreadPoolApplication(props);
    String uri = app.getUri();
    try {
      app.start();
      makeConcurrentGetRequests(uri + "/custom/resource", numOfClients, app);
    } finally {
      app.stop();
    }
  }

  /**
   * Simualate multiple HTTP clients sending HTTP requests samt time. Each client will send one HTTP request.
   * The requests will be put in queue if the number of clients are more than the working threads.
   * */
  @SuppressWarnings("SameParameterValue")
  private void makeConcurrentGetRequests(String uri, int numThread, TestCustomizeThreadPoolApplication app) throws Exception {
    Thread[] threads = new Thread[numThread];
    for(int i = 0; i < numThread; i++) {
      threads[i] = new Thread() {
        public void run() {
          HttpGet httpget = new HttpGet(uri);
          CloseableHttpClient httpclient = HttpClients.createDefault();
          CloseableHttpResponse response = null;
          try {
            response = httpclient.execute(httpget);
            HttpStatus.Code statusCode = HttpStatus.getCode(response.getStatusLine().getStatusCode());
            log.info("Status code {}, reason {} ", statusCode, response.getStatusLine().getReasonPhrase());
            assertThat(statusCode, is(Code.OK));
          } catch (Exception e) {
          } finally {
            try {
              if (response != null) {
                response.close();
              }
              httpclient.close();
            } catch (Exception e) {
            }
          }
        }
      };

      threads[i].start();
    }

    long startingTime = System.currentTimeMillis();
    while(System.currentTimeMillis() - startingTime < 360*1000) {
      log.info("Queue size {}, queue capacity {} ", app.getServer().getQueueSize(), app.getServer().getQueueCapacity());
      assertTrue("Number of jobs in queue is not more than capacity of queue ", app.getServer().getQueueSize() <= app.getServer().getQueueCapacity());
      Thread.sleep(2000);
      if (app.getServer().getQueueSize() == 0)
        break;
    }

    for(int i = 0; i < numThread; i++) {
      threads[i].join();
    }
    log.info("End queue size {}, queue capacity {} ", app.getServer().getQueueSize(), app.getServer().getQueueCapacity());
  }

  @Path("/custom")
  @Produces(MediaType.TEXT_PLAIN)
  public static class RestResource {
    @GET
    @Path("/resource")
    public String get() {
      synchronized(locker) {
        try {
          locker.wait(waitingTimeSec);
        } catch (Exception e) {
          log.info(e.getMessage());
        }
      }
      return "ThreadPool";
    }
  }

  private static class TestCustomizeThreadPoolApplication extends Application<TestRestConfig> {
    static Properties props = null;

    public TestCustomizeThreadPoolApplication() {
      super(createConfig());
    }
    public TestCustomizeThreadPoolApplication(Properties props) {
      super(new TestRestConfig(props));
      this.props = props;
    }

    @Override
    public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
      config.register(new RestResource());
    }

    public String getUri() {
      return (String)props.get(RestConfig.LISTENERS_CONFIG);
    }

    private static TestRestConfig createConfig() {
      props = new Properties();
      String uri = "http://localhost:8080";
      props.put(RestConfig.LISTENERS_CONFIG, uri);
      props.put(RestConfig.THREAD_POOL_MIN_CONFIG, "2");
      props.put(RestConfig.THREAD_POOL_MAX_CONFIG, "10");
      props.put(RestConfig.REQUEST_QUEUE_CAPACITY_INITIAL_CONFIG, "2");
      props.put(RestConfig.REQUEST_QUEUE_CAPACITY_CONFIG, "8");
      props.put(RestConfig.REQUEST_QUEUE_CAPACITY_GROWBY_CONFIG, "2");
      return new TestRestConfig(props);
    }
  }
}



