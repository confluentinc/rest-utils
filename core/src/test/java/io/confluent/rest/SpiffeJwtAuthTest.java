/*
 * Copyright 2026 Confluent Inc.
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

import static io.confluent.rest.auth.SpiffeJwtTestUtils.CALLER_ID;
import static io.confluent.rest.auth.SpiffeJwtTestUtils.SERVER_AUDIENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.confluent.rest.auth.SpiffeIdPrincipal;
import io.confluent.rest.auth.SpiffeJwtTestUtils;
import io.spiffe.bundle.BundleSource;
import io.spiffe.bundle.jwtbundle.JwtBundle;
import io.spiffe.provider.SpiffeIdVerifier;
import io.spiffe.provider.SpiffeVerificationException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Configurable;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import org.apache.kafka.common.config.ConfigException;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for {@code authentication.method=SPIFFE_JWT} against a running Jetty server.
 */
public class SpiffeJwtAuthTest {

  private final SpiffeJwtTestUtils signer = new SpiffeJwtTestUtils("key-1");
  private final Client client = ClientBuilder.newClient();
  private Server server;

  @AfterEach
  public void tearDown() throws Exception {
    client.close();
    if (server != null) {
      server.stop();
      server.join();
    }
  }

  private static Properties spiffeJwtProps() {
    Properties props = new Properties();
    props.put(RestConfig.LISTENERS_CONFIG, "http://localhost:0");
    props.put(RestConfig.AUTHENTICATION_METHOD_CONFIG,
        RestConfig.AUTHENTICATION_METHOD_SPIFFE_JWT);
    props.put(RestConfig.AUTHENTICATION_REALM_CONFIG, "spiffe");
    props.put(RestConfig.AUTHENTICATION_SPIFFE_JWT_AUDIENCES_CONFIG, SERVER_AUDIENCE);
    props.put(RestConfig.AUTHENTICATION_ROLES_CONFIG, "**");
    props.put(RestConfig.AUTHENTICATION_SKIP_PATHS, "/health");
    return props;
  }

  private void start(Properties props, SpiffeIdVerifier verifier) throws Exception {
    server = new SpiffeJwtApplication(new TestRestConfig(props), signer.bundle(), verifier)
        .createServer();
    server.start();
  }

  private Response get(String path, String authorization) {
    var request = client.target(server.getURI()).path(path).request();
    if (authorization != null) {
      request = request.header(HttpHeaders.AUTHORIZATION, authorization);
    }
    return request.get();
  }

  @Test
  public void validTokenIsAuthenticated() throws Exception {
    start(spiffeJwtProps(), null);

    Response response = get("/principal", "Bearer " + signer.token());

    assertEquals(200, response.getStatus());
    assertEquals(CALLER_ID, response.readEntity(String.class));
  }

  @Test
  public void missingTokenGets401WithBearerChallenge() throws Exception {
    start(spiffeJwtProps(), null);

    Response response = get("/principal", null);

    assertEquals(401, response.getStatus());
    assertEquals("Bearer realm=\"spiffe\"", response.getHeaderString(HttpHeaders.WWW_AUTHENTICATE));
  }

  @Test
  public void otherAuthSchemeGets401() throws Exception {
    start(spiffeJwtProps(), null);

    assertEquals(401, get("/principal", "Basic bmVoYTpha2Zhaw==").getStatus());
  }

  @Test
  public void wrongAudienceGets401() throws Exception {
    start(spiffeJwtProps(), null);
    String token = signer.token(CALLER_ID, "spiffe://example.org/someone-else",
        Instant.now().plus(Duration.ofMinutes(5)));

    assertEquals(401, get("/principal", "Bearer " + token).getStatus());
  }

  @Test
  public void expiredTokenGets401() throws Exception {
    start(spiffeJwtProps(), null);
    String token = signer.token(CALLER_ID, SERVER_AUDIENCE,
        Instant.now().minus(Duration.ofMinutes(1)));

    assertEquals(401, get("/principal", "Bearer " + token).getStatus());
  }

  @Test
  public void untrustedSignerGets401() throws Exception {
    start(spiffeJwtProps(), null);
    String token = new SpiffeJwtTestUtils("key-1").token();

    assertEquals(401, get("/principal", "Bearer " + token).getStatus());
  }

  @Test
  public void malformedTokenGets401() throws Exception {
    start(spiffeJwtProps(), null);

    assertEquals(401, get("/principal", "Bearer not-a-jwt").getStatus());
  }

  @Test
  public void skipPathNeedsNoToken() throws Exception {
    start(spiffeJwtProps(), null);

    assertEquals(200, get("/health", null).getStatus());
  }

  @Test
  public void verifierRejectionGets401() throws Exception {
    start(spiffeJwtProps(), (id, chain) -> {
      throw new SpiffeVerificationException("not allowed: " + id);
    });

    assertEquals(401, get("/principal", "Bearer " + signer.token()).getStatus());
  }

  // SPIFFE_JWT principals carry no roles, so the default authentication.roles=* (any known
  // role) forbids them; '**' is needed to admit any authenticated caller.
  @Test
  public void defaultRolesForbidSpiffePrincipals() throws Exception {
    Properties props = spiffeJwtProps();
    props.remove(RestConfig.AUTHENTICATION_ROLES_CONFIG);
    start(props, null);

    assertEquals(403, get("/principal", "Bearer " + signer.token()).getStatus());
  }

  @Test
  public void missingAudiencesFailsFast() {
    Properties props = spiffeJwtProps();
    props.remove(RestConfig.AUTHENTICATION_SPIFFE_JWT_AUDIENCES_CONFIG);
    SpiffeJwtApplication app =
        new SpiffeJwtApplication(new TestRestConfig(props), signer.bundle(), null);

    ConfigException e = assertThrows(ConfigException.class, app::createSpiffeJwtSecurityHandler);
    assertTrue(e.getMessage().contains(RestConfig.AUTHENTICATION_SPIFFE_JWT_AUDIENCES_CONFIG));
  }

  @Test
  public void missingBundleSourceFailsFast() {
    SpiffeJwtApplication app =
        new SpiffeJwtApplication(new TestRestConfig(spiffeJwtProps()), null, null);

    assertThrows(IllegalStateException.class, app::createSpiffeJwtSecurityHandler);
  }

  private static class SpiffeJwtApplication extends Application<TestRestConfig> {

    private final SpiffeIdVerifier verifier;

    SpiffeJwtApplication(TestRestConfig config, BundleSource<JwtBundle> bundleSource,
                         SpiffeIdVerifier verifier) {
      super(config, "/", null, null, null, bundleSource);
      this.verifier = verifier;
    }

    @Override
    public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
      config.register(new SpiffeJwtResource());
    }

    @Override
    protected SpiffeIdVerifier createSpiffeIdVerifier() {
      return verifier;
    }
  }

  @Path("/")
  @Produces(MediaType.TEXT_PLAIN)
  public static class SpiffeJwtResource {

    @GET
    @Path("/principal")
    public String principal(@Context SecurityContext context) {
      return ((SpiffeIdPrincipal) context.getUserPrincipal()).getSpiffeId().toString();
    }

    @GET
    @Path("/health")
    public String health() {
      return "ok";
    }
  }
}
