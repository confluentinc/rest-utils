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

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for extracting tenant IDs from HTTP requests.
 * Supports both V3 and V4 network for tenant identification.
 */
public final class TenantUtils {

  private static final Logger log = LoggerFactory.getLogger(TenantUtils.class);

  // V3 path prefix to search for
  private static final String V3_CLUSTER_PREFIX = "/kafka/v3/clusters/";
  
  // V4 tenant prefix
  private static final String V4_TENANT_PREFIX = "lkc-";
  
  // Tenant ID prefix for validation
  private static final String TENANT_ID_PREFIX = "lkc-";

  private TenantUtils() {}

  /**
   * Extracts tenant ID from an HTTP request based on the specified extraction mode.
   *
   * @param request the HTTP request
   * @param extractionMode the extraction mode (V3, V4, or AUTO)
   * @return the tenant ID, or "UNKNOWN" if extraction fails
   */
  public static String extractTenantId(HttpServletRequest request, String extractionMode) {
    log.info("NNAU: TENANT UTILS: extracting tenant ID with mode: {} for URI: {} and Host: {}", 
        extractionMode, request.getRequestURI(), request.getServerName());
    switch (extractionMode.toUpperCase()) {
      case RestConfig.DOS_FILTER_TENANT_EXTRACTION_MODE_V3:
        return extractTenantIdFromV3(request);
      case RestConfig.DOS_FILTER_TENANT_EXTRACTION_MODE_V4:
        return extractTenantIdFromV4(request);
      case RestConfig.DOS_FILTER_TENANT_EXTRACTION_MODE_AUTO:
        return extractTenantIdAuto(request);
      default:
        log.warn("NNAU: TENANT UTILS: Unknown tenant extraction mode: {}, falling back to AUTO", 
            extractionMode);
        return extractTenantIdAuto(request);
    }
  }

  /**
   * Extracts tenant ID from URL path (V3 pattern) using efficient string parsing.
   * Example: /kafka/v3/clusters/lkc-devccovmzyj/topics => lkc-devccovmzyj
   * Example: /kafka/v3/clusters/lkc-devccovmzyj => lkc-devccovmzyj
   */
  private static String extractTenantIdFromV3(HttpServletRequest request) {
    String requestURI = request.getRequestURI();
    log.info("NNAU: TENANT V3: checking URI: {}", requestURI);
    if (requestURI == null) {
      log.info("NNAU: TENANT V3: Request URI is null, cannot extract tenant ID from path");
      return "UNKNOWN";
    }

    // Find the V3 cluster prefix
    int prefixIndex = requestURI.indexOf(V3_CLUSTER_PREFIX);
    if (prefixIndex == -1) {
      log.info("NNAU: TENANT V3: V3 cluster prefix not found in path: {}", requestURI);
      return "UNKNOWN";
    }

    // Extract the part after the prefix
    int startIndex = prefixIndex + V3_CLUSTER_PREFIX.length();
    if (startIndex >= requestURI.length()) {
      log.info("NNAU: TENANT V3: No content after cluster prefix in path: {}", requestURI);
      return "UNKNOWN";
    }

    // Find the end of the tenant ID (next slash or end of string)
    int endIndex = requestURI.indexOf('/', startIndex);
    if (endIndex == -1) {
      endIndex = requestURI.length();
    }

    String tenantId = requestURI.substring(startIndex, endIndex);
    
    // Validate that the extracted tenant ID looks like a valid tenant ID
    if (isValidTenantId(tenantId)) {
      log.info("NNAU: TENANT V3: extracted tenant ID: {} from URI: {}", tenantId, requestURI);
      return tenantId;
    } else {
      log.info("NNAU: TENANT V3: extracted invalid tenant ID: {} from URI: {}",
          tenantId, requestURI);
      return "UNKNOWN";
    }
  }

  /**
   * Extracts tenant ID from hostname (V4 pattern) using efficient string parsing.
   * Example: lkc-6787w2-env5qj75n.us-west-2.aws.private.glb.stag.cpdev.cloud => lkc-6787w2
   */
  private static String extractTenantIdFromV4(HttpServletRequest request) {
    String serverName = request.getServerName();
    log.info("NNAU: TENANT V4: checking hostname: {}", serverName);
    if (serverName == null) {
      log.info("NNAU: TENANT V4: Server name is null, cannot extract tenant ID from hostname");
      return "UNKNOWN";
    }

    // Check if hostname starts with tenant prefix
    if (!serverName.startsWith(V4_TENANT_PREFIX)) {
      log.info("NNAU: TENANT V4: Hostname does not start with tenant prefix: {}", serverName);
      return "UNKNOWN";
    }

    // Find the end of the tenant ID (next dash after the prefix)
    int firstDashIndex = serverName.indexOf('-', V4_TENANT_PREFIX.length());
    if (firstDashIndex == -1) {
      log.info("NNAU: TENANT V4: No dash found after tenant prefix in hostname: {}", 
          serverName);
      return "UNKNOWN";
    }

    String tenantId = serverName.substring(0, firstDashIndex);
    
    // Validate that the extracted tenant ID looks like a valid tenant ID
    if (isValidTenantId(tenantId)) {
      log.info("NNAU: TENANT V4: extracted tenant ID: {} from server: {}", tenantId, serverName);
      return tenantId;
    } else {
      log.info("NNAU: TENANT V4: extracted invalid tenant ID: {} from server: {}", 
          tenantId, serverName);
      return "UNKNOWN";
    }
  }

  /**
   * Automatically detects the tenant extraction method by trying both V3 and V4 patterns.
   */
  private static String extractTenantIdAuto(HttpServletRequest request) {
    log.info("NNAU: TENANT AUTO: trying V3 extraction first for URI: '{}' and Host: '{}'", 
        request.getRequestURI(), request.getServerName());
    String tenantId = extractTenantIdFromV3(request);
    if (!"UNKNOWN".equals(tenantId)) {
      log.info("NNAU: TENANT AUTO: V3 extraction successful: '{}'", tenantId);
      return tenantId;
    }

    log.info("NNAU: TENANT AUTO: V3 failed, trying V4 extraction for Host: '{}'", 
        request.getServerName());
    tenantId = extractTenantIdFromV4(request);
    if (!"UNKNOWN".equals(tenantId)) {
      log.info("NNAU: TENANT AUTO: V4 extraction successful: '{}'", tenantId);
      return tenantId;
    }

    log.info("NNAU: TENANT AUTO: both V3 and V4 failed. URI: {}, Host: {}",
        request.getRequestURI(), request.getServerName());
    return "UNKNOWN";
  }

  /**
   * Validates that a tenant ID has the expected format.
   * A valid tenant ID should:
   * - Start with "lkc-"
   * - Be followed by alphanumeric characters
   * - Be at least 5 characters long (lkc- + at least 1 char)
   * - Be at most 64 characters long (reasonable upper bound)
   */
  private static boolean isValidTenantId(String tenantId) {
    if (tenantId == null || tenantId.length() < 5 || tenantId.length() > 64) {
      return false;
    }
    
    if (!tenantId.startsWith(TENANT_ID_PREFIX)) {
      return false;
    }
    
    // Check that characters after "lkc-" are alphanumeric
    for (int i = TENANT_ID_PREFIX.length(); i < tenantId.length(); i++) {
      char c = tenantId.charAt(i);
      if (!Character.isLetterOrDigit(c)) {
        return false;
      }
    }
    
    return true;
  }
}
