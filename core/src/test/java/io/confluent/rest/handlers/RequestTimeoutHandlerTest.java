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

package io.confluent.rest.handlers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Components;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

public class RequestTimeoutHandlerTest {

  private static final long TIMEOUT_MS = 500L;

  private Request request;
  private Response response;
  private Callback callback;
  private Scheduler scheduler;
  private Scheduler.Task task;

  @BeforeEach
  public void setUp() {
    request = mock(Request.class);
    response = mock(Response.class);
    callback = mock(Callback.class);
    scheduler = mock(Scheduler.class);
    task = mock(Scheduler.Task.class);

    Components components = mock(Components.class);
    when(request.getComponents()).thenReturn(components);
    when(components.getScheduler()).thenReturn(scheduler);
    when(request.getHttpURI()).thenReturn(HttpURI.from("http://localhost/slow"));
    when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
        .thenReturn(task);
    // Make sure no interrupt status leaks between tests run on this (the worker) thread.
    Thread.interrupted();
  }

  @AfterEach
  public void tearDown() {
    // Clear any interrupt flag set by the interrupt-on-timeout tests so it does not leak.
    Thread.interrupted();
  }

  /** Wires a wrapped handler that captures the (guarded) callback and never completes it. */
  private CapturingHandler buildChain(RequestTimeoutHandler handler) {
    CapturingHandler next = new CapturingHandler();
    handler.setHandler(next);
    return next;
  }

  private Runnable captureTimeoutTask() {
    ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler).schedule(captor.capture(), eq(TIMEOUT_MS), eq(TimeUnit.MILLISECONDS));
    return captor.getValue();
  }

  @Test
  public void timeoutFiringWritesGatewayTimeoutAndSkipsOriginalCallback() throws Exception {
    RequestTimeoutHandler handler = new RequestTimeoutHandler(TIMEOUT_MS, false);
    CapturingHandler next = buildChain(handler);

    try (MockedStatic<Response> mockedResponse = Mockito.mockStatic(Response.class)) {
      handler.handle(request, response, callback);
      Runnable timeoutTask = captureTimeoutTask();

      // Simulate the scheduler firing the deadline while the worker is still blocked.
      timeoutTask.run();

      mockedResponse.verify(() -> Response.writeError(
          request, response, callback, HttpStatus.GATEWAY_TIMEOUT_504, "Request timed out"));

      // The original callback must not be completed directly; writeError owns it now.
      verify(callback, never()).succeeded();
      verify(callback, never()).failed(any());

      // A late success from the (eventually unblocked) worker must be a no-op.
      next.received.succeeded();
      verify(callback, never()).succeeded();
      verify(task, never()).cancel();
    }
  }

  @Test
  public void normalSuccessCancelsTimeoutAndForwardsCompletion() throws Exception {
    RequestTimeoutHandler handler = new RequestTimeoutHandler(TIMEOUT_MS, false);
    CapturingHandler next = buildChain(handler);

    handler.handle(request, response, callback);

    next.received.succeeded();

    verify(task).cancel();
    verify(callback).succeeded();
    verify(callback, never()).failed(any());
  }

  @Test
  public void normalFailureCancelsTimeoutAndForwardsFailure() throws Exception {
    RequestTimeoutHandler handler = new RequestTimeoutHandler(TIMEOUT_MS, false);
    CapturingHandler next = buildChain(handler);

    handler.handle(request, response, callback);

    RuntimeException boom = new RuntimeException("boom");
    next.received.failed(boom);

    verify(task).cancel();
    verify(callback).failed(boom);
    verify(callback, never()).succeeded();
  }

  @Test
  public void completionAfterTimeoutIsIgnored() throws Exception {
    RequestTimeoutHandler handler = new RequestTimeoutHandler(TIMEOUT_MS, false);
    CapturingHandler next = buildChain(handler);

    try (MockedStatic<Response> mockedResponse = Mockito.mockStatic(Response.class)) {
      handler.handle(request, response, callback);
      captureTimeoutTask().run();

      // Worker finally returns; both completion paths must be no-ops after a timeout.
      next.received.succeeded();
      next.received.failed(new RuntimeException("late"));

      verify(callback, never()).succeeded();
      verify(callback, never()).failed(any());
      mockedResponse.verify(() -> Response.writeError(
          request, response, callback, HttpStatus.GATEWAY_TIMEOUT_504, "Request timed out"),
          times(1));
    }
  }

  @Test
  public void interruptOnTimeoutInterruptsWorkerThreadAfterWritingResponse() throws Exception {
    RequestTimeoutHandler handler = new RequestTimeoutHandler(TIMEOUT_MS, true);
    buildChain(handler);

    try (MockedStatic<Response> mockedResponse = Mockito.mockStatic(Response.class)) {
      // handle() captures Thread.currentThread() as the worker; running the task on this same
      // thread therefore lets us observe the interrupt directly.
      handler.handle(request, response, callback);
      Runnable timeoutTask = captureTimeoutTask();

      assertFalse(Thread.currentThread().isInterrupted(), "precondition: not interrupted");
      timeoutTask.run();

      // The 504 is still written even though interrupt-on-timeout is enabled, and the worker
      // thread carries the interrupt flag.
      mockedResponse.verify(() -> Response.writeError(
          request, response, callback, HttpStatus.GATEWAY_TIMEOUT_504, "Request timed out"));
      assertTrue(Thread.interrupted(), "worker thread should have been interrupted");
    }
  }

  @Test
  public void interruptDisabledDoesNotInterruptWorkerThread() throws Exception {
    RequestTimeoutHandler handler = new RequestTimeoutHandler(TIMEOUT_MS, false);
    buildChain(handler);

    try (MockedStatic<Response> mockedResponse = Mockito.mockStatic(Response.class)) {
      handler.handle(request, response, callback);
      captureTimeoutTask().run();
    }

    assertFalse(Thread.currentThread().isInterrupted(),
        "worker thread must not be interrupted when interrupt-on-timeout is disabled");
  }

  @Test
  public void schedulerRejectionFallsBackToHandlingWithoutTimeout() throws Exception {
    when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
        .thenThrow(new RejectedExecutionException("scheduler shut down"));

    RequestTimeoutHandler handler = new RequestTimeoutHandler(TIMEOUT_MS, false);
    CapturingHandler next = buildChain(handler);

    try (MockedStatic<Response> mockedResponse = Mockito.mockStatic(Response.class)) {
      boolean handled = handler.handle(request, response, callback);

      // The request is still handled by the downstream chain ...
      assertTrue(handled, "request should still be handled when the timeout cannot be scheduled");
      // ... and the original callback is forwarded unwrapped (no timeout guarding it).
      assertSame(callback, next.received,
          "downstream should receive the original callback when scheduling is rejected");
      mockedResponse.verifyNoInteractions();
    }

    verify(task, never()).cancel();
  }

  @Test
  public void schedulerRejectionStillForwardsCompletionToOriginalCallback() throws Exception {
    when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
        .thenThrow(new RejectedExecutionException("scheduler shut down"));

    RequestTimeoutHandler handler = new RequestTimeoutHandler(TIMEOUT_MS, false);
    CapturingHandler next = buildChain(handler);

    handler.handle(request, response, callback);
    next.received.succeeded();

    verify(callback).succeeded();
    verifyNoInteractions(task);
  }

  /** Wrapped handler that records the callback it is handed and never completes it itself. */
  private static class CapturingHandler extends Handler.Abstract {
    private volatile Callback received;

    @Override
    public boolean handle(Request request, Response response, Callback callback) {
      this.received = callback;
      return true;
    }
  }
}
