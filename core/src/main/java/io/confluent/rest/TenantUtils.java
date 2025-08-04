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

/**
 * Utility class for extracting tenant IDs from HTTP requests.
 * Supports both V3 and V4 network for tenant identification.
 */
public final class TenantUtils {

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
    // Always try path extraction first (works for both V3 and V4)
    String tenantId = extractTenantIdFromPath(request);
    if (!tenantId.equals(UNKNOWN_TENANT)) {
      return tenantId;
    }

    // Fall back to hostname extraction (V4)
    tenantId = extractTenantIdFromHostname(request);
    if (!tenantId.equals(UNKNOWN_TENANT)) {
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
    if (requestURI == null) {
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

    String tenantId = requestURI.substring(startIndex, endIndex);
    return tenantId;
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

    String tenantId = serverName.substring(0, endIndex);
    return tenantId;
  }
}
