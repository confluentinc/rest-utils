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

import io.confluent.rest.jetty.DoSFilter;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A DoS filter that applies rate limiting per tenant.
 * This prevents noisy tenants from exhausting the global rate limiter
 * by grouping requests by tenant ID
 */
public final class TenantDosFilter extends DoSFilter {

  private static final Logger log = LoggerFactory.getLogger(TenantDosFilter.class);

  private final String tenantExtractionMode;

  /**
   * Creates a new TenantDosFilter with the specified tenant extraction mode.
   *
   * @param tenantExtractionMode the mode for extracting tenant IDs (V3, V4, or AUTO)
   */
  public TenantDosFilter(String tenantExtractionMode) {
    super();
    this.tenantExtractionMode = tenantExtractionMode;
    log.info("TenantDosFilter initialized with extraction mode: {}", tenantExtractionMode);
  }

  /**
   * Extracts the tenant ID from the request to use as key for rate limiting.
   *
   * @param request the servlet request
   * @return the tenant ID to use for rate limiting, or "UNKNOWN" if extraction fails
   */
  @Override
  protected String extractUserId(ServletRequest request) {
    if (!(request instanceof HttpServletRequest)) {
      log.debug("Request is not an HttpServletRequest, cannot extract tenant ID");
      return "UNKNOWN";
    }

    HttpServletRequest httpRequest = (HttpServletRequest) request;
    String tenantId = TenantUtils.extractTenantId(httpRequest, tenantExtractionMode);

    log.debug("Extracted tenant ID: {} for request: {} {}",
        tenantId, httpRequest.getMethod(), httpRequest.getRequestURI());

    return tenantId;
  }
}

