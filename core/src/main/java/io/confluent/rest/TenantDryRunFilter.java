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
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * // TODO: This is a temporary class and to be removed after tenant rate limit testing
 * A dry run filter for measuring tenant classification accuracy and rate limit detection
 * without actually performing rate limiting
 */
public class TenantDryRunFilter extends TenantDosFilter {

  private static final Logger log = LoggerFactory.getLogger(TenantDryRunFilter.class);
  
  public TenantDryRunFilter() {
    super();
    // set custom listener that logs violations but never blocks requests
    setListener(new DryRunListener());
  }

  @Override
  public void init(FilterConfig filterConfig) throws ServletException {
    super.init(filterConfig);
    log.info("Tenant dry run classifier initialized with max {} requests/sec",
        getMaxRequestsPerSec());
  }

  @Override
  protected void doFilter(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain
  )
      throws IOException, ServletException {
    // Log tenant classification for all requests (successful and violations)
    logTenantClassification(request);
    
    // Let the parent class handle rate tracking and potential violations
    super.doFilter(request, response, filterChain);
  }

  private void logTenantClassification(HttpServletRequest request) {
    try {
      String tenantId = TenantUtils.extractTenantId(request);
      
      if (UNKNOWN_TENANT.equals(tenantId)) {
        log.warn("Tenant Dry Run: Tenant classification: Failed to extract tenant ID from request: "
                + "{} '{}' (Host: '{}')",
            request.getMethod(), request.getRequestURI(), request.getServerName());
      }

      log.info("Tenant Dry Run: Tenant classification: tenant='{}', request='{} {}', host='{}'",
          tenantId, request.getMethod(), request.getRequestURI(), request.getServerName());
    } catch (Exception e) {
      log.warn("Tenant Dry Run: Exception during tenant extraction", e);
    }
  }

  @Override
  public void destroy() {
    super.destroy();
    log.info("Tenant dry run classifier destroyed.");
  }

  /**
   * Custom listener that logs rate limit violations but always returns NO_ACTION
   * to allow all requests through normally (even if limit exceeded)
   */
  private static class DryRunListener extends DoSFilter.Listener {
    private static final Logger log = LoggerFactory.getLogger(DryRunListener.class);

    @Override
    public DoSFilter.Action onRequestOverLimit(HttpServletRequest request, 
                                               DoSFilter.OverLimit overlimit, 
                                               DoSFilter dosFilter) {
      String tenantId = TenantUtils.extractTenantId(request);
      
      log.warn("Tenant Dry Run: Tenant rate limit WOULD BE EXCEEDED: tenant='{}', "
          + "rateType='{}', duration={}ms, limit={}req/sec, request='{} {}', host='{}', ip='{}'",
          tenantId, overlimit.getRateType(), overlimit.getDuration().toMillis(), 
          overlimit.getCount(), request.getMethod(), request.getRequestURI(),
          request.getServerName(), request.getRemoteAddr());

      return DoSFilter.Action.NO_ACTION;
    }
  }
}