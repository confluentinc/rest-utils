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

package io.confluent.rest.filters;

import io.confluent.rest.RestConfig;
import java.io.IOException;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple server-side request filter that limits the connection age.
 *
 *
 * <p>Long-running client connections can be problematic for multiple server instances behind
 * an NLB due to uneven load distribution (especially in case of HTTP/2.0 that specifically
 * encourages long-lived connections). This filter closes any connection that receives a request
 * after the connection has been open for {@link RestConfig#MAX_CONNECTION_DURATION_MS} ms.</p>
 */
public class ConnectionDurationFilter implements Filter {

  private static final Logger log = LoggerFactory.getLogger(ConnectionDurationFilter.class);
  private static long MAX_CONNECTION_DURATION_MS = -1;

  @Override
  public void init(FilterConfig filterConfig) throws ServletException {
    String maxConnectionDuration = filterConfig.getInitParameter(
        RestConfig.MAX_CONNECTION_DURATION_MS);
    if (maxConnectionDuration != null) {
      MAX_CONNECTION_DURATION_MS = Long.parseLong(maxConnectionDuration);
    }
  }

  @Override
  public void doFilter(
      ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
      throws IOException, ServletException {

    HttpServletResponse httpServletResponse = (HttpServletResponse) servletResponse;

    HttpChannel channel = ((Response) httpServletResponse).getHttpChannel();

    long connectionCreationTime = channel.getConnection().getCreatedTimeStamp();
    long connectionAge = System.currentTimeMillis() - connectionCreationTime;

    if (connectionAge > MAX_CONNECTION_DURATION_MS) {
      log.debug("Connection from remote peer {} has been active for {}ms. Closing the connection.",
          channel.getRemoteAddress(), connectionAge);
      channel.getEndPoint().close();
    } else {
      log.trace("Connection from remote peer {} is {}ms old. Leaving the connection as is",
          channel.getRemoteAddress(), connectionAge);
    }

    filterChain.doFilter(servletRequest, servletResponse);

  }

  @Override
  public void destroy() {

  }

}
