package io.confluent.rest;

/*
 * Copyright 2014 - 2023 Confluent Inc.
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import org.eclipse.jetty.servlets.DoSFilter;
import org.eclipse.jetty.servlets.DoSFilter.Action;
import org.eclipse.jetty.servlets.DoSFilter.Listener;
import org.eclipse.jetty.servlets.DoSFilter.OverLimit;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class JettyDosFilterMultiListenerTest {

  private final DoSFilter.Listener listener1 = Mockito.mock(DoSFilter.Listener.class);
  private final DoSFilter.Listener listener2 = Mockito.mock(DoSFilter.Listener.class);
  private final List<Listener> listeners = Arrays.asList(listener1, listener2);

  private final JettyDosFilterMultiListener multiListener = new JettyDosFilterMultiListener(
      listeners);

  private final DoSFilter dosfilter = Mockito.mock(DoSFilter.class);

  private final long rejectDelayMs = -1;

  @Test
  public void test_2Listeners_bothListenersAreCalled() {
    when(dosfilter.getDelayMs()).thenReturn(rejectDelayMs);
    when(listener1.onRequestOverLimit(any(), any(), any())).thenReturn(Action.REJECT);
    when(listener2.onRequestOverLimit(any(), any(), any())).thenReturn(Action.REJECT);
    Action action = multiListener.onRequestOverLimit(Mockito.mock(HttpServletRequest.class),
        Mockito.mock(
            OverLimit.class), dosfilter);
    assertEquals(action, Action.REJECT);
    verify(listener1, times(1)).onRequestOverLimit(any(), any(), any());
    verify(listener2, times(1)).onRequestOverLimit(any(), any(), any());
  }

  @Test
  public void test_2Listeners_1ListenersThrows_OtherListenerIsCalled() {
    when(dosfilter.getDelayMs()).thenReturn(rejectDelayMs);
    // 1st listener throws, but 2nd should still get called.
    when(listener1.onRequestOverLimit(any(), any(), any())).thenThrow(new RuntimeException());
    when(listener2.onRequestOverLimit(any(), any(), any())).thenReturn(Action.REJECT);
    Action action = multiListener.onRequestOverLimit(Mockito.mock(HttpServletRequest.class),
        Mockito.mock(
            OverLimit.class), dosfilter);
    assertEquals(action, Action.REJECT);
    verify(listener1, times(1)).onRequestOverLimit(any(), any(), any());
    verify(listener2, times(1)).onRequestOverLimit(any(), any(), any());
  }

}
