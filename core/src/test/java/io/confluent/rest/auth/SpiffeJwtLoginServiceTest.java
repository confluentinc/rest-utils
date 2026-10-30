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

package io.confluent.rest.auth;

import static io.confluent.rest.auth.SpiffeJwtTestUtils.CALLER_ID;
import static io.confluent.rest.auth.SpiffeJwtTestUtils.SERVER_AUDIENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.spiffe.provider.SpiffeIdVerifier;
import io.spiffe.provider.SpiffeVerificationException;
import io.spiffe.spiffeid.SpiffeId;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import org.eclipse.jetty.security.DefaultIdentityService;
import org.eclipse.jetty.security.UserIdentity;
import org.junit.jupiter.api.Test;

public class SpiffeJwtLoginServiceTest {

  private final SpiffeJwtTestUtils signer = new SpiffeJwtTestUtils("key-1");

  private SpiffeJwtLoginService loginService(SpiffeIdVerifier verifier) {
    return new SpiffeJwtLoginService(
        "realm", signer.bundle(), Set.of(SERVER_AUDIENCE), verifier);
  }

  private static UserIdentity login(SpiffeJwtLoginService service, Object credentials) {
    // Normally set by the SecurityHandler on start.
    service.setIdentityService(new DefaultIdentityService());
    return service.login(null, credentials, null, create -> null);
  }

  @Test
  public void validTokenYieldsSpiffeIdPrincipalWithNoRoles() {
    UserIdentity user = login(loginService(null), signer.token());

    assertNotNull(user);
    SpiffeIdPrincipal principal = assertInstanceOf(SpiffeIdPrincipal.class,
        user.getUserPrincipal());
    assertEquals(SpiffeId.parse(CALLER_ID), principal.getSpiffeId());
    assertEquals(CALLER_ID, principal.getName());
    assertEquals(0, user.getRoles().length);
  }

  @Test
  public void anyConfiguredAudienceIsAccepted() {
    SpiffeJwtLoginService service = new SpiffeJwtLoginService("realm", signer.bundle(),
        Set.of("spiffe://example.org/other", SERVER_AUDIENCE), null);

    assertNotNull(login(service, signer.token()));
  }

  @Test
  public void wrongAudienceIsRejected() {
    String token = signer.token(CALLER_ID, "spiffe://example.org/someone-else",
        Instant.now().plus(Duration.ofMinutes(5)));

    assertNull(login(loginService(null), token));
  }

  @Test
  public void expiredTokenIsRejected() {
    String token = signer.token(CALLER_ID, SERVER_AUDIENCE,
        Instant.now().minus(Duration.ofMinutes(1)));

    assertNull(login(loginService(null), token));
  }

  @Test
  public void tokenSignedByUntrustedKeyIsRejected() {
    // Same key id, different key: signature check must fail.
    SpiffeJwtTestUtils otherSigner = new SpiffeJwtTestUtils("key-1");

    assertNull(login(loginService(null), otherSigner.token()));
  }

  @Test
  public void tokenFromUnknownTrustDomainIsRejected() {
    String token = signer.token("spiffe://other.org/caller", SERVER_AUDIENCE,
        Instant.now().plus(Duration.ofMinutes(5)));

    assertNull(login(loginService(null), token));
  }

  @Test
  public void malformedTokenIsRejected() {
    assertNull(login(loginService(null), "not-a-jwt"));
    assertNull(login(loginService(null), "a.b.c"));
  }

  @Test
  public void nonStringCredentialsAreRejected() {
    assertNull(login(loginService(null), new char[] {'x'}));
  }

  @Test
  public void verifierCanRejectCaller() {
    SpiffeIdVerifier rejectAll = (id, chain) -> {
      throw new SpiffeVerificationException("not allowed: " + id);
    };

    assertNull(login(loginService(rejectAll), signer.token()));
  }

  @Test
  public void verifierSeesCallerSpiffeId() {
    SpiffeId[] seen = new SpiffeId[1];
    SpiffeIdVerifier recording = (id, chain) -> seen[0] = id;

    assertNotNull(login(loginService(recording), signer.token()));
    assertEquals(SpiffeId.parse(CALLER_ID), seen[0]);
  }

  @Test
  public void audiencesAreRequired() {
    assertThrows(IllegalArgumentException.class,
        () -> new SpiffeJwtLoginService("realm", signer.bundle(), Collections.emptySet(), null));
  }

  @Test
  public void bundleSourceIsRequired() {
    assertThrows(NullPointerException.class,
        () -> new SpiffeJwtLoginService("realm", null, Set.of(SERVER_AUDIENCE), null));
  }
}
