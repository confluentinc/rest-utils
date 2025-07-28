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

package io.confluent.rest;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for extracting tenant IDs from HTTP requests.
 * Supports both V3 and V4 network for tenant identification.
 */
public final class TenantUtils {

  private static final Logger log = LoggerFactory.getLogger(TenantUtils.class);

  // V3 pattern: /kafka/v3/clusters/lkc-xxx/...
  // TODO: Regex matching is slow, optimize if performance becomes an issue
  private static final Pattern V3_TENANT_PATTERN = 
      Pattern.compile("/kafka/v3/clusters/(lkc-[a-zA-Z0-9]+)/");

  // V4 pattern: lkc-xxx-env.domain.com
  private static final Pattern V4_TENANT_PATTERN = 
      Pattern.compile("^(lkc-[a-zA-Z0-9]+)-");

  private TenantUtils() {}

  /**
   * Extracts tenant ID from an HTTP request based on the specified extraction mode.
   *
   * @param request the HTTP request
   * @param extractionMode the extraction mode (V3, V4, or AUTO)
   * @return the tenant ID, or "UNKNOWN" if extraction fails
   */
  public static String extractTenantId(HttpServletRequest request, String extractionMode) {
    switch (extractionMode.toUpperCase()) {
      case RestConfig.DOS_FILTER_TENANT_EXTRACTION_MODE_V3:
        return extractTenantIdFromV3(request);
      case RestConfig.DOS_FILTER_TENANT_EXTRACTION_MODE_V4:
        return extractTenantIdFromV4(request);
      case RestConfig.DOS_FILTER_TENANT_EXTRACTION_MODE_AUTO:
        return extractTenantIdAuto(request);
      default:
        log.warn("Unknown tenant extraction mode: {}, falling back to AUTO", extractionMode);
        return extractTenantIdAuto(request);
    }
  }

  /**
   * Extracts tenant ID from URL path (V3 pattern).
   * Example: /kafka/v3/clusters/lkc-devccovmzyj/topics => lkc-devccovmzyj
   */
  private static String extractTenantIdFromV3(HttpServletRequest request) {
    String requestURI = request.getRequestURI();
    if (requestURI == null) {
      log.debug("Request URI is null, cannot extract tenant ID from path");
      return "UNKNOWN";
    }

    Matcher matcher = V3_TENANT_PATTERN.matcher(requestURI);
    if (matcher.find()) {
      String tenantId = matcher.group(1);
      log.debug("Extracted tenant ID from path: {} from URI: {}", tenantId, requestURI);
      return tenantId;
    }

    log.debug("Could not extract tenant ID from path: {}", requestURI);
    return "UNKNOWN";
  }

  /**
   * Extracts tenant ID from hostname (V4 pattern).
   * Example: lkc-6787w2-env5qj75n.us-west-2.aws.private.glb.stag.cpdev.cloud => lkc-6787w2
   */
  private static String extractTenantIdFromV4(HttpServletRequest request) {
    String serverName = request.getServerName();
    if (serverName == null) {
      log.debug("Server name is null, cannot extract tenant ID from hostname");
      return "UNKNOWN";
    }

    Matcher matcher = V4_TENANT_PATTERN.matcher(serverName);
    if (matcher.find()) {
      String tenantId = matcher.group(1);
      log.debug("Extracted tenant ID from hostname: {} from server: {}", tenantId, serverName);
      return tenantId;
    }

    log.debug("Could not extract tenant ID from hostname: {}", serverName);
    return "UNKNOWN";
  }

  /**
   * Automatically detects the tenant extraction method by trying both V3 and V4 patterns.
   */
  private static String extractTenantIdAuto(HttpServletRequest request) {
    String tenantId = extractTenantIdFromV3(request);
    if (!"UNKNOWN".equals(tenantId)) {
      return tenantId;
    }

    tenantId = extractTenantIdFromV4(request);
    if (!"UNKNOWN".equals(tenantId)) {
      return tenantId;
    }

    log.debug("Could not extract tenant ID from request. URI: {}, Host: {}", 
        request.getRequestURI(), request.getServerName());
    return "UNKNOWN";
  }
} 