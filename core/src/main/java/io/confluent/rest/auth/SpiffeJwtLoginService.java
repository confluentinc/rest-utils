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

import io.spiffe.bundle.BundleSource;
import io.spiffe.bundle.jwtbundle.JwtBundle;
import io.spiffe.exception.AuthorityNotFoundException;
import io.spiffe.exception.BundleNotFoundException;
import io.spiffe.exception.JwtSvidException;
import io.spiffe.provider.SpiffeIdVerifier;
import io.spiffe.provider.SpiffeVerificationException;
import io.spiffe.spiffeid.SpiffeId;
import io.spiffe.svid.jwtsvid.JwtSvid;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import javax.security.auth.Subject;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates SPIFFE JWT-SVIDs against a JWT bundle source and the configured audiences.
 *
 * <p>Validation is local: signatures are checked against the keys in the bundle source (for a
 * java-spiffe {@code JwtSource}, the bundles it keeps in sync with the SPIFFE Workload API), so
 * no call to the agent is made per request. An optional {@link SpiffeIdVerifier} can then reject
 * callers by SPIFFE ID.
 *
 * <p>Authenticated identities carry a {@link SpiffeIdPrincipal} and no roles.
 */
public class SpiffeJwtLoginService implements LoginService {

  private static final Logger log = LoggerFactory.getLogger(SpiffeJwtLoginService.class);

  private static final String[] NO_ROLES = new String[0];

  private final String name;
  private final BundleSource<JwtBundle> bundleSource;
  private final Set<String> audiences;
  private final SpiffeIdVerifier spiffeIdVerifier;
  // Set by the SecurityHandler on start, which requires it to match its own.
  private IdentityService identityService;

  /**
   * Creates a login service.
   *
   * @param name the realm name, used in the {@code WWW-Authenticate} challenge.
   * @param bundleSource source of the JWT bundles used to verify token signatures.
   * @param audiences accepted audiences; a token must carry at least one of them. Not empty.
   * @param spiffeIdVerifier optional authorization check on the caller's SPIFFE ID; when
   *     {@code null}, any SPIFFE ID with a valid token is accepted.
   */
  public SpiffeJwtLoginService(String name,
                               BundleSource<JwtBundle> bundleSource,
                               Set<String> audiences,
                               SpiffeIdVerifier spiffeIdVerifier) {
    this.name = name;
    this.bundleSource = Objects.requireNonNull(bundleSource, "bundleSource");
    if (audiences == null || audiences.isEmpty()) {
      throw new IllegalArgumentException("At least one SPIFFE JWT audience is required");
    }
    this.audiences = Set.copyOf(audiences);
    this.spiffeIdVerifier = spiffeIdVerifier;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public UserIdentity login(String username, Object credentials, Request request,
                            Function<Boolean, Session> getOrCreateSession) {
    if (!(credentials instanceof String)) {
      return null;
    }

    JwtSvid svid = validate((String) credentials);
    if (svid == null) {
      return null;
    }

    SpiffeId spiffeId = svid.getSpiffeId();
    if (spiffeIdVerifier != null) {
      try {
        spiffeIdVerifier.verify(spiffeId, null);
      } catch (SpiffeVerificationException e) {
        log.debug("SpiffeIdVerifier rejected SPIFFE ID {}: {}", spiffeId, e.getMessage());
        return null;
      }
    }

    SpiffeIdPrincipal principal = new SpiffeIdPrincipal(spiffeId);
    Subject subject = new Subject();
    subject.getPrincipals().add(principal);
    subject.setReadOnly();
    return identityService.newUserIdentity(subject, principal, NO_ROLES);
  }

  // java-spiffe requires a token to carry *all* the expected audiences; we accept a token that
  // carries any one of the configured audiences, so validate against each in turn.
  private JwtSvid validate(String token) {
    Exception lastFailure = null;
    for (String audience : audiences) {
      try {
        return JwtSvid.parseAndValidate(token, bundleSource, Set.of(audience));
      } catch (JwtSvidException | BundleNotFoundException | AuthorityNotFoundException
               | IllegalArgumentException e) {
        // IllegalArgumentException: the token is not a parseable JWT.
        lastFailure = e;
      }
    }
    log.debug("Rejected SPIFFE JWT-SVID: {}", lastFailure.getMessage());
    return null;
  }

  // Tokens are validated on every request and nothing is cached in the session, so there is
  // nothing to re-validate here.
  @Override
  public boolean validate(UserIdentity user) {
    return true;
  }

  @Override
  public IdentityService getIdentityService() {
    return identityService;
  }

  @Override
  public void setIdentityService(IdentityService identityService) {
    this.identityService = identityService;
  }

  @Override
  public void logout(UserIdentity user) {
  }
}
