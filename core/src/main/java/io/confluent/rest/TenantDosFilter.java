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
 * DoS filter that implements per-tenant rate limiting.
 * Extends the base DoSFilter to extract tenant IDs from requests and rate limit by tenant.
 */
public class TenantDosFilter extends DoSFilter {
  
  private static final Logger log = LoggerFactory.getLogger(TenantDosFilter.class);
  
  private final String tenantExtractionMode;

  public TenantDosFilter(String tenantExtractionMode) {
    super();
    this.tenantExtractionMode = tenantExtractionMode;
    log.info("NNAU: TenantDosFilter constructor - extraction mode: '{}'", tenantExtractionMode);
  }

  @Override
  protected String extractUserId(ServletRequest request) {
    if (!(request instanceof HttpServletRequest)) {
      log.info("NNAU: TENANT DOS: FAILED - Request is not an HttpServletRequest, "
          + "cannot extract tenant ID");
      return "UNKNOWN";
    }

    HttpServletRequest httpRequest = (HttpServletRequest) request;
    log.info("NNAU: TENANT DOS: processing request - Method: {}, URI: '{}', Host: '{}', "
        + "Query: '{}'", 
        httpRequest.getMethod(), 
        httpRequest.getRequestURI(),
        httpRequest.getServerName(),
        httpRequest.getQueryString());

    String tenantId = TenantUtils.extractTenantId(httpRequest, tenantExtractionMode);
    
    log.info("NNAU: TENANT DOS: final result - tenant ID: '{}' for request: {} '{}' "
        + "(Host: '{}')",
        tenantId, httpRequest.getMethod(), httpRequest.getRequestURI(), 
        httpRequest.getServerName());
    
    return tenantId;
  }
}

