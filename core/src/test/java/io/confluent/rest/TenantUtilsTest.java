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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

public class TenantUtilsTest {

  @Test
  public void testPathBasedTenantIdExtraction_SuccessCases() {
    HttpServletRequest request = mock(HttpServletRequest.class);

    // Standard topic path
    assertTenantExtraction(request, "/kafka/v3/clusters/lkc-devccovmzyj/topics", 
        null, "lkc-devccovmzyj");
    
    // Consumer groups path
    assertTenantExtraction(request, "/kafka/v3/clusters/lkc-abc123def/consumer-groups", 
        null, "lkc-abc123def");
    
    // Short tenant ID
    assertTenantExtraction(request, "/kafka/v3/clusters/lkc-6787w2/topics", 
        null, "lkc-6787w2");
    
    // Path ending at cluster ID
    assertTenantExtraction(request, "/kafka/v3/clusters/lkc-devc80y73q", 
        null, "lkc-devc80y73q");
  }

  @Test
  public void testPathBasedTenantIdExtraction_FailureCases() {
    HttpServletRequest request = mock(HttpServletRequest.class);

    // Wrong path pattern
    assertTenantExtraction(request, "/api/v1/some/other/path", 
        null, TenantUtils.UNKNOWN_TENANT);
    
    // Null URI
    assertTenantExtraction(request, null, 
        null, TenantUtils.UNKNOWN_TENANT);
  }

  @Test
  public void testHostnameBasedTenantIdExtraction_BasicPatterns() {
    HttpServletRequest request = mock(HttpServletRequest.class);

    // Basic hostname patterns
    assertTenantExtraction(request, null, 
        "lkc-6787w2-env5qj75n.us-west-2.aws.private.glb.stag.cpdev.cloud", "lkc-6787w2");
    assertTenantExtraction(request, null, 
        "lkc-abc123def-prod789.us-east-1.aws.private.confluent.cloud", "lkc-abc123def");
    assertTenantExtraction(request, null, 
        "lkc-xyz123-env.eu-central-1.azure.confluent.cloud", "lkc-xyz123");
  }

  @Test
  public void testHostnameBasedTenantIdExtraction_GLBPatterns() {
    HttpServletRequest request = mock(HttpServletRequest.class);

    // GLB-based patterns
    assertTenantExtraction(request, null, 
        "lkc-2v531-lg1y3.us-west-1.aws.glb.confluent.cloud", "lkc-2v531");
    assertTenantExtraction(request, null, 
        "lkc-3d253-lg1y3.us-west-1.aws.glb.confluent.cloud", "lkc-3d253");
  }

  @Test
  public void testHostnameBasedTenantIdExtraction_PrivateDNSPatterns() {
    HttpServletRequest request = mock(HttpServletRequest.class);

    // Private DNS Phase 1 patterns
    assertTenantExtraction(request, null, 
        "lkc-2v531.domz6wj0p.us-west-1.aws.confluent.cloud", "lkc-2v531");
    assertTenantExtraction(request, null, 
        "lkc-2v531-00aa.usw1-az1.domz6wj0p.us-west-1.aws.confluent.cloud", "lkc-2v531");

    // Private DNS Phase 2 patterns
    assertTenantExtraction(request, null, 
        "lkc-2v531-lg1y3.us-west-1.aws.glb.confluent.cloud", "lkc-2v531");
    assertTenantExtraction(request, null, 
        "lkc-2v531.lg1y3.us-west-1.aws.confluent.cloud", "lkc-2v531");
  }

  @Test
  public void testHostnameBasedTenantIdExtraction_EnterprisePatterns() {
    HttpServletRequest request = mock(HttpServletRequest.class);

    // Enterprise SKU patterns
    assertTenantExtraction(request, null, 
        "lkc-abc123.us-west-2.aws.private.confluent.cloud", "lkc-abc123");
    assertTenantExtraction(request, null, 
        "lkc-abc123-9ae1.us-west-2.aws.private.confluent.cloud", "lkc-abc123");

    // Private Network Interface-Based (Freight/Enterprise) patterns
    assertTenantExtraction(request, null, 
        "lkc-devc2qrwyy-apxxx.us-west-2.aws.accesspoint.glb.confluent.cloud", "lkc-devc2qrwyy");
    assertTenantExtraction(request, null, 
        "lkc-devc2qrwyy-1234-apxxx.usw2-az2.us-west-2.aws.accesspoint.glb.confluent.cloud", "lkc-devc2qrwyy");

    // Trusted shared network scheme patterns
    assertTenantExtraction(request, null, 
        "lkc-abc123.us-west-2.aws.intranet.confluent.cloud", "lkc-abc123");
    assertTenantExtraction(request, null, 
        "lkc-abc123-9ae1.usw2-az1.us-west-2.aws.intranet.confluent.cloud", "lkc-abc123");
  }

  @Test
  public void testHostnameBasedTenantIdExtraction_NonTenantEndpoints() {
    HttpServletRequest request = mock(HttpServletRequest.class);

    // Broker endpoints (start with e-)
    assertTenantExtraction(request, null, 
        "e-00aa-usw1-az1-lg1y3.us-west-1.aws.glb.confluent.cloud", TenantUtils.UNKNOWN_TENANT);
    assertTenantExtraction(request, null, 
        "e-1d39.use1-az1.domjpe506kp.us-east-1.aws.confluent.cloud", TenantUtils.UNKNOWN_TENANT);

    // Kafka API endpoints (start with lkaclkc-)
    assertTenantExtraction(request, null, 
        "lkaclkc-3d253-lg1y3.us-west-1.aws.glb.confluent.cloud", TenantUtils.UNKNOWN_TENANT);
    assertTenantExtraction(request, null, 
        "lkaclkc-2v531.domz6wj0p.us-west-1.aws.confluent.cloud", TenantUtils.UNKNOWN_TENANT);

    // KSQL endpoints (start with pksqlc-)
    assertTenantExtraction(request, null, 
        "pksqlc-3d235-lg1y3.us-west-1.aws.glb.confluent.cloud", TenantUtils.UNKNOWN_TENANT);
    assertTenantExtraction(request, null, 
        "pksqlc-3d235.domz6wj0p.us-west-1.aws.confluent.cloud", TenantUtils.UNKNOWN_TENANT);

    // Schema Registry endpoints (start with psrc-)
    assertTenantExtraction(request, null, 
        "psrc-8kz20.us-east-2.aws.confluent.cloud", TenantUtils.UNKNOWN_TENANT);

    // Flink UDF Gateway endpoints (start with pflinkudfgwc-)
    assertTenantExtraction(request, null, 
        "pflinkudfgwc-3d235.us-west-2.aws.intranet.confluent-untrusted.cloud", TenantUtils.UNKNOWN_TENANT);
  }

  @Test
  public void testHostnameBasedTenantIdExtraction_InvalidPatterns() {
    HttpServletRequest request = mock(HttpServletRequest.class);

    // Invalid patterns
    assertTenantExtraction(request, null, 
        "api.confluent.cloud", TenantUtils.UNKNOWN_TENANT);
    assertTenantExtraction(request, null, 
        "lsrc-123-env.domain.com", TenantUtils.UNKNOWN_TENANT);
    assertTenantExtraction(request, null, 
        null, TenantUtils.UNKNOWN_TENANT);
  }

  @Test
  public void testTenantIdExtractionWithFallback() {
    HttpServletRequest request = mock(HttpServletRequest.class);

    // Hostname extraction takes priority when both are available
    assertTenantExtraction(request, "/kafka/v3/clusters/lkc-6787w2/topics", 
        "lkc-6787w2-env5qj75n.us-west-2.aws.private.glb.stag.cpdev.cloud", "lkc-6787w2");
    
    // Fallback to path extraction when hostname extraction fails  
    assertTenantExtraction(request, "/kafka/v3/clusters/lkc-devc80y73q", 
        "kafka.pkc-devcyypqg6.svc.cluster.local", "lkc-devc80y73q");
    
    // Hostname extraction succeeds when path extraction would fail
    assertTenantExtraction(request, "/some/other/path", 
        "lkc-abc123-env456.domain.com", "lkc-abc123");
    
    // Both hostname and path extraction fail
    assertTenantExtraction(request, "/api/v1/other", 
        "api.confluent.cloud", TenantUtils.UNKNOWN_TENANT);
  }

  @Test
  public void testIsHealthCheckRequest_HealthEndpoint() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn("/kafka/health");
    assertTrue(TenantUtils.isHealthCheckRequest(request));
  }

  @Test
  public void testIsHealthCheckRequest_HealthCheckRestTopicProduce() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn(
        "/kafka/v3/clusters/lkc-3kv9m/topics/_confluent-healthcheck-rest_12/records");
    assertTrue(TenantUtils.isHealthCheckRequest(request));
  }

  @Test
  public void testIsHealthCheckRequest_RegularEndpoints() {
    HttpServletRequest request = mock(HttpServletRequest.class);

    when(request.getRequestURI()).thenReturn(
        "/kafka/v3/clusters/lkc-abc123/topics/my-topic/records");
    assertFalse(TenantUtils.isHealthCheckRequest(request));

    when(request.getRequestURI()).thenReturn("/kafka/v3/clusters/lkc-abc123");
    assertFalse(TenantUtils.isHealthCheckRequest(request));

    // Topic name containing the healthcheck substring should not match
    when(request.getRequestURI()).thenReturn(
        "/kafka/v3/clusters/lkc-abc123/topics/malicioususer_confluent-healthcheck/records");
    assertFalse(TenantUtils.isHealthCheckRequest(request));
  }

  @Test
  public void testIsHealthCheckRequest_NullUri() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn(null);
    assertFalse(TenantUtils.isHealthCheckRequest(request));
  }

  @Test
  public void testIsHealthCheckRequest_NullRequest() {
    assertFalse(TenantUtils.isHealthCheckRequest(null));
  }

  private void assertTenantExtraction(HttpServletRequest request, String requestURI,
      String serverName, String expectedTenantId) {
    when(request.getRequestURI()).thenReturn(requestURI);
    when(request.getServerName()).thenReturn(serverName);
    
    String actualTenantId = TenantUtils.extractTenantId(request);
    assertEquals(expectedTenantId, actualTenantId);
  }
} 