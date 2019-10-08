

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
      log.info("Current running thread {}, maximum thread {}.", app.getThreads(), app.getMaxThreads());
      assertTrue("Total number of running threads less than maximum number of threads " + app.getMaxThreads(),
              app.getThreads() - app.getMaxThreads() < 0);
      app.stop();
    }
  }

  /**
   * This testing will test the number of running threads will be increased and not over the maximum threads, finally
   * throw exceptions in server.
   * The following similair exception will be seen on console when the number of running thread reach the maximum
   * threads allowed.
   * [2019-09-28 08:37:59,504] WARN QueuedThreadPool[qtp527464124]@1f7076bc{STARTED,2<=20<=20,i=0,r=2,q=2}
   * [ReservedThreadExecutor@1b1f5012{s=0/2,p=0}] rejected org.eclipse.jetty.io.ManagedSelector$Accept@26ac0324
   * (org.eclipse.jetty.util.thread.QueuedThreadPool:471)
   * ...
   * java.util.concurrent.RejectedExecutionException: CEP:NetworkTrafficSelectChannelEndPoint@3a0b8ca7{/127.0.0.1:64929
   * <->/127.0.0.1:8080,OPEN,fill=FI,flush=-,to=2/30000}{io=1/0,kio=1,kro=1}->HttpConnection@5b06a71d[p=HttpParser
   * {s=START,0 of -1},g=HttpGenerator@10ef51b0{s=START}]=>HttpChannelOverHttp@42bd59c7{r=0,c=false,c=false/false,
   * a=IDLE,uri=null,age=0}:runFillable:BLOCKING
   * Test the size of jobs in queue will not over the capacity of queue confifiured.
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
      log.info("Current running thread {}, maximum thread {}.", app.getThreads(), app.getMaxThreads());
      assertTrue("Total number of running threads reach maximum number of threads " + app.getMaxThreads(),
              app.getThreads() - app.getMaxThreads() == 0);
      app.stop();
    }
  }

  /**
   * Simualte numebr of HTTP clients sending HTTP request. Each client will send one HTTP request. The requests will be
   * put in queue if number of clients are more than the working threads.
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
      log.info("Queue size {}, queue capacity {} ", app.getQueueSize(), app.getQueueCapacity());
      assertTrue("Number of jobs in queue is not more than capacity of queue ", app.getQueueSize() <= app.getQueueCapacity());
      Thread.sleep(2000);
      if (app.getQueueSize() == 0)
        break;
    }

    for(int i = 0; i < numThread; i++) {
      threads[i].join();
    }
    log.info("End queue size {}, queue capacity {} ", app.getQueueSize(), app.getQueueCapacity());
    assertTrue("Queue is empty ", app.getQueueSize() == 0);
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



