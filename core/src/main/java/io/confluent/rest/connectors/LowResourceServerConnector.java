/*
 * Copyright 2019 Confluent Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.confluent.rest.connectors;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.NetworkTrafficServerConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.thread.Scheduler;

public class LowResourceServerConnector extends NetworkTrafficServerConnector {

  private static final long MAX_MEMORY = 2L * 1024 * 1024 * 1024;

  public LowResourceServerConnector(Server server, Executor executor, Scheduler scheduler,
      ByteBufferPool pool, int acceptors, int selectors, ConnectionFactory... factories) {
    super(server, executor, scheduler, pool, acceptors, selectors, factories);
  }

  @Override
  public void accept(final int acceptorID) throws IOException {
    long memory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    if (memory >= MAX_MEMORY) {
      throw new RejectedExecutionException("Request rejected due to high memory usage");
    } else {
      super.accept(acceptorID);
    }
  }
}
