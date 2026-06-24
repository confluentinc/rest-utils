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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

public class ExpectedSniHandlerTest {

  private static final List<String> EXPECTED_SNIS =
      Arrays.asList("host1.example.com", "host2.example.com");

  /**
   * Records whether the wrapped (downstream) handler was invoked, so tests can verify
   * that rejected requests do NOT reach the wrapped handler.
   */
  private static final class RecordingHandler extends Handler.Abstract {
    boolean invoked = false;

    @Override
    public boolean handle(Request request, Response response, Callback callback) {
      invoked = true;
      return true;
    }
  }

  @Test
  public void handle_sniMatches_passesThrough() throws Exception {
    RecordingHandler next = new RecordingHandler();
    Request request = mock(Request.class);
    Response response = mock(Response.class);
    Callback callback = mock(Callback.class);

    ExpectedSniHandler handler = new ExpectedSniHandler(EXPECTED_SNIS, true);
    handler.setHandler(next);

    try (MockedStatic<SniUtils> sniUtils = mockStatic(SniUtils.class);
         MockedStatic<Response> responseStatic = mockStatic(Response.class)) {
      sniUtils.when(() -> SniUtils.getSniServerName(request)).thenReturn("host1.example.com");

      handler.handle(request, response, callback);

      assertTrue(next.invoked, "wrapped handler should be invoked when SNI matches");
      responseStatic.verify(
          () -> Response.writeError(any(Request.class), any(Response.class),
              any(Callback.class), anyInt(), anyString()),
          never());
    }
  }

  @Test
  public void handle_sniMismatch_rejectDisabled_passesThrough() throws Exception {
    RecordingHandler next = new RecordingHandler();
    Request request = mock(Request.class);
    Response response = mock(Response.class);
    Callback callback = mock(Callback.class);

    when(request.getHttpURI()).thenReturn(HttpURI.from("https://other.example.com/path"));

    ExpectedSniHandler handler = new ExpectedSniHandler(EXPECTED_SNIS, false);
    handler.setHandler(next);

    try (MockedStatic<SniUtils> sniUtils = mockStatic(SniUtils.class);
         MockedStatic<Response> responseStatic = mockStatic(Response.class)) {
      sniUtils.when(() -> SniUtils.getSniServerName(request)).thenReturn("other.example.com");

      handler.handle(request, response, callback);

      assertTrue(next.invoked, "wrapped handler should still be invoked when reject is disabled");
      responseStatic.verify(
          () -> Response.writeError(any(Request.class), any(Response.class),
              any(Callback.class), anyInt(), anyString()),
          never());
    }
  }

  @Test
  public void handle_sniMismatch_rejectEnabled_returns400InvalidSni() throws Exception {
    RecordingHandler next = new RecordingHandler();
    Request request = mock(Request.class);
    Response response = mock(Response.class);
    Callback callback = mock(Callback.class);

    when(request.getHttpURI()).thenReturn(HttpURI.from("https://other.example.com/path"));

    ExpectedSniHandler handler = new ExpectedSniHandler(EXPECTED_SNIS, true);
    handler.setHandler(next);

    try (MockedStatic<SniUtils> sniUtils = mockStatic(SniUtils.class);
         MockedStatic<Response> responseStatic = mockStatic(Response.class)) {
      sniUtils.when(() -> SniUtils.getSniServerName(request)).thenReturn("other.example.com");

      boolean handled = handler.handle(request, response, callback);

      assertTrue(handled, "handle() should return true when the request was rejected");
      assertFalse(next.invoked, "wrapped handler must not be invoked when rejected");
      responseStatic.verify(
          () -> Response.writeError(eq(request), eq(response), eq(callback),
              eq(400), eq("Invalid SNI")),
          times(1));
    }
  }

  @Test
  public void handle_sniNull_rejectEnabled_returns400InvalidSni() throws Exception {
    // A null SNI (e.g. plain HTTP, or a non-SSL endpoint) is treated the same as a
    // non-matching SNI: rejected with HTTP 400 when reject mode is enabled.
    RecordingHandler next = new RecordingHandler();
    Request request = mock(Request.class);
    Response response = mock(Response.class);
    Callback callback = mock(Callback.class);

    when(request.getHttpURI()).thenReturn(HttpURI.from("https://anything/"));

    ExpectedSniHandler handler = new ExpectedSniHandler(EXPECTED_SNIS, true);
    handler.setHandler(next);

    try (MockedStatic<SniUtils> sniUtils = mockStatic(SniUtils.class);
         MockedStatic<Response> responseStatic = mockStatic(Response.class)) {
      sniUtils.when(() -> SniUtils.getSniServerName(request)).thenReturn(null);

      boolean handled = handler.handle(request, response, callback);

      assertTrue(handled);
      assertFalse(next.invoked, "wrapped handler must not be invoked when rejected");
      responseStatic.verify(
          () -> Response.writeError(eq(request), eq(response), eq(callback),
              eq(400), eq("Invalid SNI")),
          times(1));
    }
  }

  @Test
  public void handle_sniNull_rejectDisabled_passesThrough() throws Exception {
    RecordingHandler next = new RecordingHandler();
    Request request = mock(Request.class);
    Response response = mock(Response.class);
    Callback callback = mock(Callback.class);

    when(request.getHttpURI()).thenReturn(HttpURI.from("https://anything/"));

    ExpectedSniHandler handler = new ExpectedSniHandler(EXPECTED_SNIS, false);
    handler.setHandler(next);

    try (MockedStatic<SniUtils> sniUtils = mockStatic(SniUtils.class);
         MockedStatic<Response> responseStatic = mockStatic(Response.class)) {
      sniUtils.when(() -> SniUtils.getSniServerName(request)).thenReturn(null);

      handler.handle(request, response, callback);

      assertTrue(next.invoked);
      responseStatic.verify(
          () -> Response.writeError(any(Request.class), any(Response.class),
              any(Callback.class), anyInt(), anyString()),
          never());
    }
  }

  @Test
  public void handle_emptyExpectedList_rejectEnabled_rejectsAnySni() throws Exception {
    RecordingHandler next = new RecordingHandler();
    Request request = mock(Request.class);
    Response response = mock(Response.class);
    Callback callback = mock(Callback.class);

    when(request.getHttpURI()).thenReturn(HttpURI.from("https://x/"));

    ExpectedSniHandler handler = new ExpectedSniHandler(Collections.emptyList(), true);
    handler.setHandler(next);

    try (MockedStatic<SniUtils> sniUtils = mockStatic(SniUtils.class);
         MockedStatic<Response> responseStatic = mockStatic(Response.class)) {
      sniUtils.when(() -> SniUtils.getSniServerName(request)).thenReturn("foo");

      boolean handled = handler.handle(request, response, callback);

      assertTrue(handled);
      assertFalse(next.invoked);
      responseStatic.verify(
          () -> Response.writeError(eq(request), eq(response), eq(callback),
              eq(400), eq("Invalid SNI")),
          times(1));
    }
  }

  @Test
  public void singleArgConstructor_defaultsToWarnOnly() throws Exception {
    // The single-arg constructor preserves the original warn-only behavior so existing
    // call sites that don't opt in keep their current semantics.
    RecordingHandler next = new RecordingHandler();
    Request request = mock(Request.class);
    Response response = mock(Response.class);
    Callback callback = mock(Callback.class);

    when(request.getHttpURI()).thenReturn(HttpURI.from("https://other/"));

    ExpectedSniHandler handler = new ExpectedSniHandler(EXPECTED_SNIS);
    handler.setHandler(next);

    try (MockedStatic<SniUtils> sniUtils = mockStatic(SniUtils.class);
         MockedStatic<Response> responseStatic = mockStatic(Response.class)) {
      sniUtils.when(() -> SniUtils.getSniServerName(request)).thenReturn("not-in-list");

      handler.handle(request, response, callback);

      assertTrue(next.invoked);
      responseStatic.verify(
          () -> Response.writeError(any(Request.class), any(Response.class),
              any(Callback.class), anyInt(), anyString()),
          never());
    }
  }
}
