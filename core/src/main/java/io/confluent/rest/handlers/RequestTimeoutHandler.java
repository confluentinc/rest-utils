/*
 * Copyright 2024 Confluent Inc.
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

package io.confluent.rest.handlers;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enforces a blanket wall-clock timeout on every request flowing through it. If a request takes
 * longer than {@code timeoutMs} to complete, it is aborted with an HTTP 504 Gateway Timeout
 * response.
 *
 * <p>The deadline is scheduled on Jetty's own scheduler thread (via
 * {@link Request#getComponents()}), so it fires independently of the worker thread handling the
 * request. This is deliberate: a worker thread blocked deep inside a synchronous resource method
 * (e.g. waiting on leader forwarding) never returns to the servlet container, so a servlet async
 * {@code AsyncContext.setTimeout} would never start counting. Scheduling out-of-band lets us
 * return a response to the client even while the worker thread remains blocked.
 *
 * <p>Note: this frees the client and the response/connection, but cannot force-kill the stuck
 * worker thread (Java has no safe way to do so). The thread is reclaimed only once its downstream
 * call returns or its own socket/idle timeout trips.
 */
public class RequestTimeoutHandler extends Handler.Wrapper {

  private static final Logger log = LoggerFactory.getLogger(RequestTimeoutHandler.class);

  private final long timeoutMs;

  public RequestTimeoutHandler(long timeoutMs) {
    this.timeoutMs = timeoutMs;
  }

  @Override
  public boolean handle(Request request, Response response, Callback callback) throws Exception {
    // Shared between the timeout task and the wrapped callback so that whichever fires first
    // wins and the request is completed exactly once.
    final AtomicBoolean done = new AtomicBoolean(false);

    final Scheduler.Task timeoutTask = request.getComponents().getScheduler().schedule(() -> {
      if (done.compareAndSet(false, true)) {
        log.warn("Request to {} exceeded the configured timeout of {} ms; returning 504",
            request.getHttpURI(), timeoutMs);
        Response.writeError(request, response, callback,
            HttpStatus.GATEWAY_TIMEOUT_504, "Request timed out");
      }
    }, timeoutMs, TimeUnit.MILLISECONDS);

    // Forward only the first completion downstream and cancel the timer when the request
    // completes normally. If the timeout already fired, these become no-ops.
    final Callback guarded = new Callback() {
      @Override
      public void succeeded() {
        if (done.compareAndSet(false, true)) {
          timeoutTask.cancel();
          callback.succeeded();
        }
      }

      @Override
      public void failed(Throwable x) {
        if (done.compareAndSet(false, true)) {
          timeoutTask.cancel();
          callback.failed(x);
        }
      }
    };

    return super.handle(request, response, guarded);
  }
}
