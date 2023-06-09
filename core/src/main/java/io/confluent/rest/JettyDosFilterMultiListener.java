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

package io.confluent.rest;

import java.util.List;
import javax.servlet.http.HttpServletRequest;
import org.eclipse.jetty.servlets.DoSFilter;
import org.eclipse.jetty.servlets.DoSFilter.Action;
import org.eclipse.jetty.servlets.DoSFilter.OverLimit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * This is used to run multiple DosFilter.Listeners, which are the input here.
 * Any exception is thrown by a listener is simply logged, and other listeners
 * are run.
 */
public class JettyDosFilterMultiListener extends DoSFilter.Listener {

  private static final Logger log = LoggerFactory.getLogger(JettyDosFilterMultiListener.class
  );
  private List<DoSFilter.Listener> listeners;

  public JettyDosFilterMultiListener(List<DoSFilter.Listener> listeners) {
    this.listeners = listeners;
  }

  @Override
  public Action onRequestOverLimit(HttpServletRequest request, OverLimit overlimit,
      DoSFilter dosFilter) {
    // KREST-10418: we don't use super function to get action object because
    // it will log a WARN line, in order to reduce verbosity
    Action action = Action.fromDelay(dosFilter.getDelayMs());
    for (DoSFilter.Listener listener : listeners) {
      try {
        listener.onRequestOverLimit(request, overlimit, dosFilter);
      } catch (Exception ex) {
        log.debug("{} threw exception {}", listener.getClass(), ex);
      }
    }
    return action;
  }

}
