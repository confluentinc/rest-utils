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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

public class TenantUtilsTest {

  @Test
  public void testV3TenantIdExtraction() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    
    // test successful V3 extraction
    when(request.getRequestURI()).thenReturn("/kafka/v3/clusters/lkc-devccovmzyj/topics");
    String tenantId = TenantUtils.extractTenantId(request, RestConfig.DOS_FILTER_TENANT_EXTRACTION_MODE_V3);
    assertEquals("lkc-devccovmzyj", tenantId);
    
    // test V3 extraction with different endpoint
    when(request.getRequestURI()).thenReturn("/kafka/v3/clusters/lkc-abc123def/consumer-groups");
    tenantId = TenantUtils.extractTenantId(request, RestConfig.DOS_FILTER_TENANT_EXTRACTION_MODE_V3);
    assertEquals("lkc-abc123def", tenantId);
    
    // test V3 extraction failure - wrong pattern
    when(request.getRequestURI()).thenReturn("/api/v1/some/other/path");
    tenantId = TenantUtils.extractTenantId(request, RestConfig.DOS_FILTER_TENANT_EXTRACTION_MODE_V3);
    assertEquals("UNKNOWN", tenantId);
    
    // test V3 extraction failure - null URI
    when(request.getRequestURI()).thenReturn(null);
    tenantId = TenantUtils.extractTenantId(request, RestConfig.DOS_FILTER_TENANT_EXTRACTION_MODE_V3);
    assertEquals("UNKNOWN", tenantId);
  }

  @Test
  public void testV4TenantIdExtraction() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    
    // test successful V4 extraction
    when(request.getServerName()).thenReturn("lkc-devccovmzyj-env.confluent.cloud");
    String tenantId = TenantUtils.extractTenantId(request, RestConfig.DOS_FILTER_TENANT_EXTRACTION_MODE_V4);
    assertEquals("lkc-devccovmzyj", tenantId);
    
    // test V4 extraction with different environment
    when(request.getServerName()).thenReturn("lkc-abc123def-prod.example.com");
    tenantId = TenantUtils.extractTenantId(request, RestConfig.DOS_FILTER_TENANT_EXTRACTION_MODE_V4);
    assertEquals("lkc-abc123def", tenantId);
    
    // test V4 extraction failure - wrong pattern
    when(request.getServerName()).thenReturn("api.confluent.cloud");
    tenantId = TenantUtils.extractTenantId(request, RestConfig.DOS_FILTER_TENANT_EXTRACTION_MODE_V4);
    assertEquals("UNKNOWN", tenantId);
    
    // test V4 extraction failure - null hostname
    when(request.getServerName()).thenReturn(null);
    tenantId = TenantUtils.extractTenantId(request, RestConfig.DOS_FILTER_TENANT_EXTRACTION_MODE_V4);
    assertEquals("UNKNOWN", tenantId);
  }
} 