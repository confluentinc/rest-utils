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
  public static final String UNKNOWN_TENANT = "UNKNOWN";

  private static final String CLUSTER_PREFIX = "/kafka/v3/clusters/";
  private static final String LKC_ID_PREFIX = "lkc-";

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
        return extractTenantIdFromPath(request);
      case RestConfig.DOS_FILTER_TENANT_EXTRACTION_MODE_V4:
        return extractTenantIdFromHost(request);
      case RestConfig.DOS_FILTER_TENANT_EXTRACTION_MODE_AUTO:
        return extractTenantIdAuto(request);
      default:
        log.warn("NNAU: TENANT UTILS: Unknown tenant extraction mode: {}, falling back to AUTO", 
            extractionMode);
        return extractTenantIdAuto(request);
    }
  }

  /**
   * Extracts tenant ID from URL path (V3 pattern)
   * Example: /kafka/v3/clusters/lkc-devccovmzyj => lkc-devccovmzyj
   */
  private static String extractTenantIdFromPath(HttpServletRequest request) {
    String requestURI = request.getRequestURI();
    log.info("NNAU: TENANT V3 (String parse): checking URI: {}", requestURI);
    if (requestURI == null) {
      log.info("NNAU: TENANT V3: Request URI is null, cannot extract tenant ID from path");
      return UNKNOWN_TENANT;
    }

    // Find the V3 cluster prefix
    int prefixIndex = requestURI.indexOf(CLUSTER_PREFIX);
    if (prefixIndex == -1) {
      log.info("NNAU: TENANT V3: V3 cluster prefix not found in path: {}", requestURI);
      return UNKNOWN_TENANT;
    }

    int startIndex = prefixIndex + CLUSTER_PREFIX.length();
    if (startIndex >= requestURI.length() || !requestURI.startsWith(LKC_ID_PREFIX, startIndex)) {
      log.info("NNAU: TENANT V3: No tenant ID found after cluster prefix in path: {}", requestURI);
      return UNKNOWN_TENANT;
    }

    int endIndex = startIndex + LKC_ID_PREFIX.length();
    while (endIndex < requestURI.length() 
           && Character.isLetterOrDigit(requestURI.charAt(endIndex))) {
      endIndex++;
    }

    // Validate we found at least one character after the prefix
    if (endIndex == startIndex + LKC_ID_PREFIX.length()) {
      log.info("NNAU: TENANT V3: No valid tenant ID characters found in path: {}", requestURI);
      return UNKNOWN_TENANT;
    }

    String tenantId = requestURI.substring(startIndex, endIndex);
    log.info("NNAU: TENANT V3: extracted tenant ID: {} from URI: {}", tenantId, requestURI);
    return tenantId;
  }

  /**
   * Extracts tenant ID from hostname (V4 pattern)
   * Example: lkc-6787w2-env5qj75n.us-west-2.aws.private.glb.stag.cpdev.cloud => lkc-6787w2
   * Note that V4 extraction also supports path-based extraction and this serves as a fallback.
   */
  private static String extractTenantIdFromHost(HttpServletRequest request) {
    String serverName = request.getServerName();
    log.info("NNAU: TENANT V4 (String parse): checking hostname: {}", serverName);
    if (serverName == null || !serverName.startsWith(LKC_ID_PREFIX)) {
      log.info("NNAU: TENANT V4: Server name is null or doesn't start with tenant prefix: {}", 
          serverName);
      return UNKNOWN_TENANT;
    }

    int endIndex = LKC_ID_PREFIX.length();
    while (endIndex < serverName.length() 
           && Character.isLetterOrDigit(serverName.charAt(endIndex))) {
      endIndex++;
    }

    // Validate we found at least one character after the prefix
    if (endIndex == LKC_ID_PREFIX.length()) {
      log.info("NNAU: TENANT V4: Invalid tenant ID format in hostname: {}", serverName);
      return UNKNOWN_TENANT;
    }

    String tenantId = serverName.substring(0, endIndex);
    log.info("NNAU: TENANT V4: extracted tenant ID: {} from server: {}", tenantId, serverName);
    return tenantId;
  }

  /**
   * Automatically detects the tenant extraction method by trying both V3 and V4 patterns.
   */
  private static String extractTenantIdAuto(HttpServletRequest request) {
    log.info("NNAU: TENANT AUTO: trying V3 extraction first for URI: '{}' and Host: '{}'",
        request.getRequestURI(), request.getServerName());
    String tenantId = extractTenantIdFromPath(request);
    if (!UNKNOWN_TENANT.equals(tenantId)) {
      log.info("NNAU: TENANT AUTO: V3 extraction successful: '{}'", tenantId);
      return tenantId;
    }

    log.info("NNAU: TENANT AUTO: V3 failed, trying V4 extraction for Host: '{}'", 
        request.getServerName());
    tenantId = extractTenantIdFromHost(request);
    if (!UNKNOWN_TENANT.equals(tenantId)) {
      log.info("NNAU: TENANT AUTO: V4 extraction successful: '{}'", tenantId);
      return tenantId;
    }

    log.info("NNAU: TENANT AUTO: both V3 and V4 failed. URI: {}, Host: {}",
        request.getRequestURI(), request.getServerName());
    return UNKNOWN_TENANT;
  }
}
