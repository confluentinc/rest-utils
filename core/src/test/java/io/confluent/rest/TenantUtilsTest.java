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
    
    // test successful V3 extraction - standard case
    when(request.getRequestURI()).thenReturn("/kafka/v3/clusters/lkc-devccovmzyj/topics");
    String tenantId = TenantUtils.extractTenantId(request, RestConfig.DOS_FILTER_TENANT_EXTRACTION_MODE_V3);
    assertEquals("lkc-devccovmzyj", tenantId);
    
    // test V3 extraction with different endpoint
    when(request.getRequestURI()).thenReturn("/kafka/v3/clusters/lkc-abc123def/consumer-groups");
    tenantId = TenantUtils.extractTenantId(request, RestConfig.DOS_FILTER_TENANT_EXTRACTION_MODE_V3);
    assertEquals("lkc-abc123def", tenantId);
    
    // test V3 extraction with realistic tenant ID from documentation
    when(request.getRequestURI()).thenReturn("/kafka/v3/clusters/lkc-6787w2/topics");
    tenantId = TenantUtils.extractTenantId(request, RestConfig.DOS_FILTER_TENANT_EXTRACTION_MODE_V3);
    assertEquals("lkc-6787w2", tenantId);
    
    // test V3 extraction with path ending at tenant ID (previously failing case)
    when(request.getRequestURI()).thenReturn("/kafka/v3/clusters/lkc-devc80y73q");
    tenantId = TenantUtils.extractTenantId(request, RestConfig.DOS_FILTER_TENANT_EXTRACTION_MODE_V3);
    assertEquals("lkc-devc80y73q", tenantId);
    
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

    // test successful V4 extraction with realistic pattern
    when(request.getServerName()).thenReturn("lkc-6787w2-env5qj75n.us-west-2.aws.private.glb.stag.cpdev.cloud");
    String tenantId = TenantUtils.extractTenantId(request, RestConfig.DOS_FILTER_TENANT_EXTRACTION_MODE_V4);
    assertEquals("lkc-6787w2", tenantId);
    
    // test V4 extraction with different realistic patterns
    when(request.getServerName()).thenReturn("lkc-abc123def-prod789.us-east-1.aws.private.confluent.cloud");
    tenantId = TenantUtils.extractTenantId(request, RestConfig.DOS_FILTER_TENANT_EXTRACTION_MODE_V4);
    assertEquals("lkc-abc123def", tenantId);
    
    // test V4 extraction with shorter environment ID
    when(request.getServerName()).thenReturn("lkc-xyz123-env.eu-central-1.azure.confluent.cloud");
    tenantId = TenantUtils.extractTenantId(request, RestConfig.DOS_FILTER_TENANT_EXTRACTION_MODE_V4);
    assertEquals("lkc-xyz123", tenantId);
    
    // test V4 extraction failure - wrong pattern (no lkc prefix)
    when(request.getServerName()).thenReturn("api.confluent.cloud");
    tenantId = TenantUtils.extractTenantId(request, RestConfig.DOS_FILTER_TENANT_EXTRACTION_MODE_V4);
    assertEquals("UNKNOWN", tenantId);
    
    // test V4 extraction failure - wrong pattern (lsrc instead of lkc)
    when(request.getServerName()).thenReturn("lsrc-123-env.domain.com");
    tenantId = TenantUtils.extractTenantId(request, RestConfig.DOS_FILTER_TENANT_EXTRACTION_MODE_V4);
    assertEquals("UNKNOWN", tenantId);
    
    // test V4 extraction failure - null hostname
    when(request.getServerName()).thenReturn(null);
    tenantId = TenantUtils.extractTenantId(request, RestConfig.DOS_FILTER_TENANT_EXTRACTION_MODE_V4);
    assertEquals("UNKNOWN", tenantId);
  }

  @Test
  public void testAutoTenantIdExtraction() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    
    // test AUTO mode - V3 pattern should be found first
    when(request.getRequestURI()).thenReturn("/kafka/v3/clusters/lkc-6787w2/topics");
    when(request.getServerName()).thenReturn("lkc-6787w2-env5qj75n.us-west-2.aws.private.glb.stag.cpdev.cloud");
    String tenantId = TenantUtils.extractTenantId(request, RestConfig.DOS_FILTER_TENANT_EXTRACTION_MODE_AUTO);
    assertEquals("lkc-6787w2", tenantId);
    
    // test AUTO mode - V3 extraction with path ending at tenant ID (edge case)
    when(request.getRequestURI()).thenReturn("/kafka/v3/clusters/lkc-devc80y73q");
    when(request.getServerName()).thenReturn("kafka.pkc-devcyypqg6.svc.cluster.local");
    tenantId = TenantUtils.extractTenantId(request, RestConfig.DOS_FILTER_TENANT_EXTRACTION_MODE_AUTO);
    assertEquals("lkc-devc80y73q", tenantId);
    
    // test AUTO mode - fallback to V4 when V3 fails
    when(request.getRequestURI()).thenReturn("/some/other/path");
    when(request.getServerName()).thenReturn("lkc-abc123-env456.domain.com");
    tenantId = TenantUtils.extractTenantId(request, RestConfig.DOS_FILTER_TENANT_EXTRACTION_MODE_AUTO);
    assertEquals("lkc-abc123", tenantId);
    
    // test AUTO mode - both V3 and V4 fail
    when(request.getRequestURI()).thenReturn("/api/v1/other");
    when(request.getServerName()).thenReturn("api.confluent.cloud");
    tenantId = TenantUtils.extractTenantId(request, RestConfig.DOS_FILTER_TENANT_EXTRACTION_MODE_AUTO);
    assertEquals("UNKNOWN", tenantId);
  }
} 