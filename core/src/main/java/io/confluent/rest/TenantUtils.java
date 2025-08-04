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
   * Extracts tenant ID from an HTTP request.
   * Attempts path extraction first, then falls back to hostname extraction.
   *
   * @param request the HTTP request
   * @return the tenant ID, or "UNKNOWN" if extraction fails
   */
  public static String extractTenantId(HttpServletRequest request) {
    log.info("NNAU: TENANT UTILS: extracting tenant ID for URI: {} and Host: {}",
        request.getRequestURI(), request.getServerName());

    // Always try path extraction first (works for both V3 and V4)
    String tenantId = extractTenantIdFromPath(request);
    if (!tenantId.equals(UNKNOWN_TENANT)) {
      log.info("NNAU: TENANT UTILS: Path extraction successful: '{}'", tenantId);
      return tenantId;
    }

    // Fall back to hostname extraction (V4)
    log.info("NNAU: TENANT UTILS: Path extraction failed, trying hostname for Host: '{}'",
        request.getServerName());
    tenantId = extractTenantIdFromHostname(request);
    if (!tenantId.equals(UNKNOWN_TENANT)) {
      log.info("NNAU: TENANT UTILS: Hostname extraction successful: '{}'", tenantId);
      return tenantId;
    }

    log.info("NNAU: TENANT UTILS: Both path and hostname extraction failed. URI: {}, Host: {}",
        request.getRequestURI(), request.getServerName());
    return UNKNOWN_TENANT;
  }

  /**
   * Extracts tenant ID from URL path
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
   * Extracts tenant ID from hostname
   * Example: lkc-6787w2-env5qj75n.us-west-2.aws.private.glb.stag.cpdev.cloud => lkc-6787w2
   */
  private static String extractTenantIdFromHostname(HttpServletRequest request) {
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
}
