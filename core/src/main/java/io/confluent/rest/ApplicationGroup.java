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
 */

package io.confluent.rest;

import org.eclipse.jetty.server.Server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ApplicationGroup<T extends RestConfig> {
  private final T config;
  private Server server;
  private final List<Application> applications = new ArrayList<>();

  ApplicationGroup(T config, Application ...applications) {
    this.config = config;
    this.applications.addAll(Arrays.asList(applications));
    System.out.println(applications.toString());
  }

  public void addApplication(Application application) {
    this.applications.add(application);
  }

  public List<Application> getApplications() {
    return applications;
  }

  public RestConfig getConfiguration() {
    return this.config;
  }

  /**
   * Start the server (creating it if necessary).
   * @throws Exception If the application fails to start
   */
  public void start() throws Exception {
    this.server = new ApplicationServer(this);
  }

  /**
   * Wait for the server to exit, allowing existing requests to complete if graceful shutdown is
   * enabled and invoking the shutdown hook before returning.
   * @throws InterruptedException If the internal threadpool fails to stop
   */
  public void join() throws InterruptedException {
    server.join();
  }

  /**
   * Request that the server shutdown.
   * @throws Exception If the application fails to stop
   */
  public void stop() throws Exception {
    server.stop();
  }

  void doStop() {
    for (Application app: applications) {
      app.metrics.close();
      app.doShutdown();
      app.shutdownLatch.countDown();
    }
  }
}
