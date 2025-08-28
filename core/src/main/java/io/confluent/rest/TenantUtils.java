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
   * Extracts tenant ID for request
   * Attempts hostname extraction first (applies to V4 networking - majority case),
   * then falls back to request path extraction (applies to V3 networking).
   *
   * @param request the HTTP request
   * @return the tenant ID, or "UNKNOWN" if extraction fails
   */
  public static String extractTenantId(HttpServletRequest request) {
    if (request == null) {
      log.warn("Cannot extract tenant ID: request is null");
      return UNKNOWN_TENANT;
    }

    // Try hostname extraction first (works for V4 networking and is more reliable)
    String tenantId = extractTenantIdFromHostname(request);
    if (!tenantId.equals(UNKNOWN_TENANT)) {
      log.debug("Tenant extracted from hostname: tenant='{}', host='{}'", tenantId, request.getServerName());
      return tenantId;
    }

    // Fall back to path extraction for V3 networking
    tenantId = extractTenantIdFromPath(request);
    if (!tenantId.equals(UNKNOWN_TENANT)) {
      log.debug("Tenant extracted from path: tenant='{}', uri='{}'", tenantId, request.getRequestURI());
      return tenantId;
    }

    return UNKNOWN_TENANT;
  }

  /**
   * Extracts tenant ID from URL path
   * Example: /kafka/v3/clusters/lkc-devccovmzyj => lkc-devccovmzyj
   */
  private static String extractTenantIdFromPath(HttpServletRequest request) {
    String requestURI = request.getRequestURI();
    if (requestURI == null || requestURI.isEmpty()) {
      return UNKNOWN_TENANT;
    }

    // Find the V3 cluster prefix
    int prefixIndex = requestURI.indexOf(CLUSTER_PREFIX);
    if (prefixIndex == -1) {
      return UNKNOWN_TENANT;
    }

    int startIndex = prefixIndex + CLUSTER_PREFIX.length();
    if (startIndex >= requestURI.length() || !requestURI.startsWith(LKC_ID_PREFIX, startIndex)) {
      return UNKNOWN_TENANT;
    }

    int endIndex = startIndex + LKC_ID_PREFIX.length();
    while (endIndex < requestURI.length() 
           && Character.isLetterOrDigit(requestURI.charAt(endIndex))) {
      endIndex++;
    }

    // Validate we found at least one character after the prefix
    if (endIndex == startIndex + LKC_ID_PREFIX.length()) {
      return UNKNOWN_TENANT;
    }

    return requestURI.substring(startIndex, endIndex);
  }

  /**
   * Extracts tenant ID from hostname
   * Example: lkc-6787w2-env5qj75n.us-west-2.aws.private.glb.stag.cpdev.cloud => lkc-6787w2
   */
  private static String extractTenantIdFromHostname(HttpServletRequest request) {
    String serverName = request.getServerName();
    if (serverName == null || !serverName.startsWith(LKC_ID_PREFIX)) {
      return UNKNOWN_TENANT;
    }

    int endIndex = LKC_ID_PREFIX.length();
    while (endIndex < serverName.length() 
           && Character.isLetterOrDigit(serverName.charAt(endIndex))) {
      endIndex++;
    }

    // Validate we found at least one character after the prefix
    if (endIndex == LKC_ID_PREFIX.length()) {
      return UNKNOWN_TENANT;
    }

    return serverName.substring(0, endIndex);
  }
}
