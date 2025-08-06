/*
 * Copyright 2025 Confluent Inc.
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

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * // TODO: This is a temporary class and to be removed this code after tenant rate limit testing
 * A dry-run filter for measuring tenant classification accuracy
 * without actually performing rate limiting. This filter extracts tenant IDs
 * and logs the results for monitoring and analysis purposes.
 */
public class TenantDryRunFilter implements Filter {

  private static final Logger log = LoggerFactory.getLogger(TenantDryRunFilter.class);
  
  @Override
  public void init(FilterConfig filterConfig) throws ServletException {
    log.info("Tenant dry-run classifier initialized. This filter will extract and log "
        + "tenant IDs without performing rate limiting.");
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    
    try {
      if (request instanceof HttpServletRequest) {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String tenantId = TenantUtils.extractTenantId(httpRequest);

        if (log.isInfoEnabled()) {
          log.info("Tenant classification: tenant='{}', request='{} {}', host='{}'",
              tenantId, httpRequest.getMethod(), httpRequest.getRequestURI(), 
              httpRequest.getServerName());
        }
      }
    } catch (Exception e) {
      log.warn("Exception during tenant extraction in dry-run mode", e);
    }
    
    // Continue with the request chain with no rate limiting applied at the tenant level
    chain.doFilter(request, response);
  }

  @Override
  public void destroy() {
    log.info("Tenant dry-run classifier destroyed.");
  }
}