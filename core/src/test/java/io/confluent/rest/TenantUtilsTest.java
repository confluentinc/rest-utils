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
  public void testPathBasedTenantIdExtraction() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    
    // test successful path-based extraction - standard case
    when(request.getRequestURI()).thenReturn("/kafka/v3/clusters/lkc-devccovmzyj/topics");
    String tenantId = TenantUtils.extractTenantId(request);
    assertEquals("lkc-devccovmzyj", tenantId);
    
    // test V3 extraction with different endpoint
    when(request.getRequestURI()).thenReturn("/kafka/v3/clusters/lkc-abc123def/consumer-groups");
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals("lkc-abc123def", tenantId);
    
    // test V3 extraction with realistic tenant ID from documentation
    when(request.getRequestURI()).thenReturn("/kafka/v3/clusters/lkc-6787w2/topics");
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals("lkc-6787w2", tenantId);
    
    // test V3 extraction with path ending at tenant ID (previously failing case)
    when(request.getRequestURI()).thenReturn("/kafka/v3/clusters/lkc-devc80y73q");
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals("lkc-devc80y73q", tenantId);
    
    // test V3 extraction failure - wrong pattern
    when(request.getRequestURI()).thenReturn("/api/v1/some/other/path");
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals(TenantUtils.UNKNOWN_TENANT, tenantId);
    
    // test V3 extraction failure - null URI
    when(request.getRequestURI()).thenReturn(null);
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals(TenantUtils.UNKNOWN_TENANT, tenantId);
  }

  @Test
  public void testHostnameBasedTenantIdExtraction() {
    HttpServletRequest request = mock(HttpServletRequest.class);

    // ========================================================================================
    // POSITIVE TEST CASES
    // ========================================================================================

    // === Basic V4 patterns ===
    when(request.getServerName()).thenReturn("lkc-6787w2-env5qj75n.us-west-2.aws.private.glb.stag.cpdev.cloud");
    String tenantId = TenantUtils.extractTenantId(request);
    assertEquals("lkc-6787w2", tenantId);
    
    when(request.getServerName()).thenReturn("lkc-abc123def-prod789.us-east-1.aws.private.confluent.cloud");
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals("lkc-abc123def", tenantId);
    
    when(request.getServerName()).thenReturn("lkc-xyz123-env.eu-central-1.azure.confluent.cloud");
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals("lkc-xyz123", tenantId);

    // === V4 GLB-based patterns ===
    when(request.getServerName()).thenReturn("lkc-2v531-lg1y3.us-west-1.aws.glb.confluent.cloud");
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals("lkc-2v531", tenantId);
    
    when(request.getServerName()).thenReturn("lkc-3d253-lg1y3.us-west-1.aws.glb.confluent.cloud");
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals("lkc-3d253", tenantId);

    // === V4 non-GLB (Private DNS Phase 1) patterns ===
    when(request.getServerName()).thenReturn("lkc-2v531.domz6wj0p.us-west-1.aws.confluent.cloud");
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals("lkc-2v531", tenantId);
    
    when(request.getServerName()).thenReturn("lkc-2v531-00aa.usw1-az1.domz6wj0p.us-west-1.aws.confluent.cloud");
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals("lkc-2v531", tenantId);

    // === V4 concurrent endpoints (Private DNS Phase 2) ===
    when(request.getServerName()).thenReturn("lkc-2v531-lg1y3.us-west-1.aws.glb.confluent.cloud");
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals("lkc-2v531", tenantId);
    
    when(request.getServerName()).thenReturn("lkc-2v531.lg1y3.us-west-1.aws.confluent.cloud");
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals("lkc-2v531", tenantId);

    // === Enterprise SKU patterns ===
    when(request.getServerName()).thenReturn("lkc-abc123.us-west-2.aws.private.confluent.cloud");
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals("lkc-abc123", tenantId);
    
    when(request.getServerName()).thenReturn("lkc-abc123-9ae1.us-west-2.aws.private.confluent.cloud");
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals("lkc-abc123", tenantId);

    // === Private Network Interface-Based (Freight/Enterprise) patterns ===
    when(request.getServerName()).thenReturn("lkc-devc2qrwyy-apxxx.us-west-2.aws.accesspoint.glb.confluent.cloud");
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals("lkc-devc2qrwyy", tenantId);
    
    when(request.getServerName()).thenReturn("lkc-devc2qrwyy-1234-apxxx.usw2-az2.us-west-2.aws.accesspoint.glb.confluent.cloud");
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals("lkc-devc2qrwyy", tenantId);

    // === Trusted shared network scheme patterns ===
    when(request.getServerName()).thenReturn("lkc-abc123.us-west-2.aws.intranet.confluent.cloud");
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals("lkc-abc123", tenantId);
    
    when(request.getServerName()).thenReturn("lkc-abc123-9ae1.usw2-az1.us-west-2.aws.intranet.confluent.cloud");
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals("lkc-abc123", tenantId);

    // ========================================================================================
    // NEGATIVE TEST CASES
    // ========================================================================================

    // === Broker endpoints (start with e-) ===
    when(request.getServerName()).thenReturn("e-00aa-usw1-az1-lg1y3.us-west-1.aws.glb.confluent.cloud");
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals(TenantUtils.UNKNOWN_TENANT, tenantId);
    
    when(request.getServerName()).thenReturn("e-1d39.use1-az1.domjpe506kp.us-east-1.aws.confluent.cloud");
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals(TenantUtils.UNKNOWN_TENANT, tenantId);

    // === Kafka API endpoints (start with lkaclkc-) ===
    when(request.getServerName()).thenReturn("lkaclkc-3d253-lg1y3.us-west-1.aws.glb.confluent.cloud");
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals(TenantUtils.UNKNOWN_TENANT, tenantId);
    
    when(request.getServerName()).thenReturn("lkaclkc-2v531.domz6wj0p.us-west-1.aws.confluent.cloud");
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals(TenantUtils.UNKNOWN_TENANT, tenantId);

    // === KSQL endpoints (start with pksqlc-) ===
    when(request.getServerName()).thenReturn("pksqlc-3d235-lg1y3.us-west-1.aws.glb.confluent.cloud");
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals(TenantUtils.UNKNOWN_TENANT, tenantId);
    
    when(request.getServerName()).thenReturn("pksqlc-3d235.domz6wj0p.us-west-1.aws.confluent.cloud");
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals(TenantUtils.UNKNOWN_TENANT, tenantId);

    // === Schema Registry endpoints (start with psrc-) ===
    when(request.getServerName()).thenReturn("psrc-8kz20.us-east-2.aws.confluent.cloud");
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals(TenantUtils.UNKNOWN_TENANT, tenantId);

    // === Flink UDF Gateway endpoints (start with pflinkudfgwc-) ===
    when(request.getServerName()).thenReturn("pflinkudfgwc-3d235.us-west-2.aws.intranet.confluent-untrusted.cloud");
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals(TenantUtils.UNKNOWN_TENANT, tenantId);

    // === Invalid patterns ===
    when(request.getServerName()).thenReturn("api.confluent.cloud");
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals(TenantUtils.UNKNOWN_TENANT, tenantId);
    
    when(request.getServerName()).thenReturn("lsrc-123-env.domain.com");
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals(TenantUtils.UNKNOWN_TENANT, tenantId);
    
    when(request.getServerName()).thenReturn(null);
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals(TenantUtils.UNKNOWN_TENANT, tenantId);
  }

  @Test
  public void testTenantIdExtractionWithFallback() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    
    // test path extraction takes priority - path pattern should be found first
    when(request.getRequestURI()).thenReturn("/kafka/v3/clusters/lkc-6787w2/topics");
    when(request.getServerName()).thenReturn("lkc-6787w2-env5qj75n.us-west-2.aws.private.glb.stag.cpdev.cloud");
    String tenantId = TenantUtils.extractTenantId(request);
    assertEquals("lkc-6787w2", tenantId);
    
    // test path extraction with path ending at tenant ID (edge case)
    when(request.getRequestURI()).thenReturn("/kafka/v3/clusters/lkc-devc80y73q");
    when(request.getServerName()).thenReturn("kafka.pkc-devcyypqg6.svc.cluster.local");
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals("lkc-devc80y73q", tenantId);
    
    // test fallback to hostname when path extraction fails
    when(request.getRequestURI()).thenReturn("/some/other/path");
    when(request.getServerName()).thenReturn("lkc-abc123-env456.domain.com");
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals("lkc-abc123", tenantId);
    
    // test both path and hostname extraction fail
    when(request.getRequestURI()).thenReturn("/api/v1/other");
    when(request.getServerName()).thenReturn("api.confluent.cloud");
    tenantId = TenantUtils.extractTenantId(request);
    assertEquals(TenantUtils.UNKNOWN_TENANT, tenantId);
  }
} 