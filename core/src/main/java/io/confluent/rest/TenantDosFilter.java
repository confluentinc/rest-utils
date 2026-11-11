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

import static io.confluent.rest.TenantUtils.UNKNOWN_TENANT;

import io.confluent.rest.jetty.DoSFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DoS filter that implements per-tenant rate limiting.
 * Extends the base DoSFilter to extract tenant IDs from requests and rate limit by tenant.
 */
public class TenantDosFilter extends DoSFilter {

  private static final Logger log = LoggerFactory.getLogger(TenantDosFilter.class);
  
  public TenantDosFilter() {
    super();
  }

  @Override
  protected void doFilter(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws IOException, ServletException {
    if (TenantUtils.isHealthCheckRequest(request)) {
      filterChain.doFilter(request, response);
      return;
    }
    super.doFilter(request, response, filterChain);
  }

  @Override
  protected String extractUserId(ServletRequest request) {
    // IMPORTANT: If we can't identify the tenant (or get a bad request), return null to skip
    // tenant-based rate limiting.
    // This results in the base DoSFilter to fall back to session-based (if enabled) or
    // IP-based rate limiting, as this is the best we can do in this scenario.

    if (!(request instanceof HttpServletRequest)) {
      log.warn("Request is not an HttpServletRequest, cannot extract tenant ID");
      return null;
    }

    HttpServletRequest httpRequest = (HttpServletRequest) request;
    String tenantId = TenantUtils.extractTenantId(httpRequest);
    
    if (tenantId.equals(UNKNOWN_TENANT)) {
      log.warn("Skipping tenant-based rate limiting for unidentified tenant. "
          + "Request: {} '{}' (Host: '{}'), falling back to IP-based rate limiting",
          httpRequest.getMethod(), httpRequest.getRequestURI(), httpRequest.getServerName());
      return null;
    }

    return tenantId;
  }

  @Override
  protected boolean isTenantBasedTracking() {
    return true;
  }
}

