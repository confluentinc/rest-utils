/**
 * Copyright 2014 Confluent Inc.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.confluent.rest.auth;

import io.confluent.rest.RestConfig;
import io.confluent.rest.RestConfigurable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Implements authorization for Rest based applications.
 */
public class RestAuthorizationFilter implements Filter, RestConfigurable {

  private static final Logger log = LoggerFactory.getLogger(RestAuthorizationFilter.class);

  private RestAuthorizer authorizer;
  private RestPrincipalBuilder principalBuilder;

  @Override
  public void init(FilterConfig filterConfig) throws ServletException {
  }

  public void configure(RestConfig config) {
    authorizer =
            config.getConfiguredInstance(RestConfig.AUTHORIZATION_AUTHORIZER_CLASS_CONFIG,
                    RestAuthorizer.class);
    principalBuilder =
            config.getConfiguredInstance(RestConfig.AUTHORIZATION_PRINCIPAL_BUILDER_CLASS_CONFIG,
                    RestPrincipalBuilder.class);
  }

  @Override
  public void doFilter(ServletRequest servletRequest,
                       ServletResponse servletResponse,
                       FilterChain filterChain) throws IOException, ServletException {
    HttpServletRequest request = (HttpServletRequest) servletRequest;
    String principalName = principalBuilder.getPrincipalName(request);
    boolean authorized = authorizer.authorize(principalName, request);
    if (!authorized) {
      String msg = principalName + " is unauthorized for request " + request.getRequestURI();
      HttpServletResponse httpResponse = (HttpServletResponse) servletResponse;
      httpResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED, msg);
      log.info("Unauthorized " + msg);
      return; // do not pass on request processing
    }
    filterChain.doFilter(servletRequest, servletResponse);
  }

  @Override
  public void destroy() {
    try {
      authorizer.close();
    } catch (Exception e) {
      log.error("Error while closing authorizer", e);
    }
  }
}