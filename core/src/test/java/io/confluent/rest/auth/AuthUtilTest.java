/*
 * Copyright 2018 Confluent Inc.
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

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import com.google.common.collect.ImmutableMap;
import io.confluent.rest.RestConfig;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.eclipse.jetty.ee10.servlet.security.ConstraintMapping;
import org.eclipse.jetty.security.Constraint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AuthUtilTest {

  private RestConfig config;

  @BeforeEach
  public void setUp() {
    config = restConfigWith(ImmutableMap.of());
  }

  @Test
  public void shouldDetectCorsBeingEnabled() {
    // Given:
    config = restConfigWith(ImmutableMap.of(
        RestConfig.ACCESS_CONTROL_ALLOW_ORIGIN_CONFIG, "something"));

    // Then:
    assertThat(AuthUtil.isCorsEnabled(config), is(true));
  }

  @Test
  public void shouldDetectCorsDisabledWhenEmpty() {
    // Given:
    config = restConfigWith(ImmutableMap.of(
        RestConfig.ACCESS_CONTROL_ALLOW_ORIGIN_CONFIG, ""));

    // Then:
    assertThat(AuthUtil.isCorsEnabled(config), is(false));
  }

  @Test
  public void shouldCreateGlobalConstraintToCoverAllPaths() {
    // When:
    final ConstraintMapping mapping = AuthUtil.createGlobalAuthConstraint(config);

    // Then:
    assertThat(mapping.getPathSpec(), is("/*"));
  }

  @Test
  public void shouldCreateGlobalConstraintToCoverAllMethods() {
    // When:
    final ConstraintMapping mapping = AuthUtil.createGlobalAuthConstraint(config);

    // Then:
    assertThat(mapping.getMethod(), is("*"));
  }

  @Test
  public void shouldCreateGlobalConstraintWithNoMethodsOmittedForNonCor() {
    // Given:
    config = restConfigWith(ImmutableMap.of(
            RestConfig.ACCESS_CONTROL_ALLOW_ORIGIN_CONFIG, ""));

    // When:
    final ConstraintMapping mapping = AuthUtil.createGlobalAuthConstraint(config);

    // Then:
    assertThat(mapping.getMethodOmissions(), is(nullValue()));
  }

  @Test
  public void shouldCreateGlobalConstraintWithOptionsOmittedForCor() {
    // Given:
    config = restConfigWith(ImmutableMap.of(
            RestConfig.ACCESS_CONTROL_ALLOW_ORIGIN_CONFIG, "something"));

    // When:
    final ConstraintMapping mapping = AuthUtil.createGlobalAuthConstraint(config);

    // Then:
    assertThat(mapping.getMethodOmissions(), is(new String[]{"OPTIONS"}));
  }

  @Test
  public void shouldCreateGlobalConstraintWithoutOptionsOmittedForCor() {
    // Given:
    config = restConfigWith(ImmutableMap.of(
        RestConfig.ACCESS_CONTROL_ALLOW_ORIGIN_CONFIG, "something",
            RestConfig.ACCESS_CONTROL_SKIP_OPTIONS, false));

    // When:
    final ConstraintMapping mapping = AuthUtil.createGlobalAuthConstraint(config);

    // Then:
    assertThat(mapping.getMethodOmissions(), is(nullValue()));
  }

  @Test
  public void shouldCreateGlobalConstraintWithAuthRequired() {
    // When:
    final ConstraintMapping mapping = AuthUtil.createGlobalAuthConstraint(config);

    // Then:
    // Refer to https://javadoc.jetty.org/jetty-12/org/eclipse/jetty/security/Constraint.Authorization.html#KNOWN_ROLE
    assertEquals(Constraint.Authorization.KNOWN_ROLE, mapping.getConstraint().getAuthorization());
  }

  @Test
  public void shouldDefaultToCreatingGlobalConstraintWithAnyRole() {
    // When:
    final ConstraintMapping mapping = AuthUtil.createGlobalAuthConstraint(config);

    // Then:
    // Refer to https://javadoc.jetty.org/jetty-12/org/eclipse/jetty/security/Constraint.Authorization.html#KNOWN_ROLE
    assertEquals(Constraint.Authorization.KNOWN_ROLE, mapping.getConstraint().getAuthorization());
    assertTrue(mapping.getConstraint().getRoles().isEmpty());
  }

  @Test
  public void shouldCreateGlobalConstraintWithRoles() {
    // Given:
    config = restConfigWith(ImmutableMap.of(
        RestConfig.AUTHENTICATION_ROLES_CONFIG, " r1 , r2 "));

    // When:
    final ConstraintMapping mapping = AuthUtil.createGlobalAuthConstraint(config);

    // Then:
    // Refer to https://javadoc.jetty.org/jetty-12/org/eclipse/jetty/security/Constraint.Authorization.html#SPECIFIC_ROLE
    assertEquals(Constraint.Authorization.SPECIFIC_ROLE,mapping.getConstraint().getAuthorization());
    assertThat(mapping.getConstraint().getRoles(), is(Set.of("r1", "r2")));
  }

  @Test
  public void shouldCreateGlobalConstraintWithNoRoles() {
    // Given:
    config = restConfigWith(ImmutableMap.of(
        RestConfig.AUTHENTICATION_ROLES_CONFIG, ""));

    // When:
    final ConstraintMapping mapping = AuthUtil.createGlobalAuthConstraint(config);

    // Then:
    // Refer to https://javadoc.jetty.org/jetty-12/org/eclipse/jetty/security/Constraint.Authorization.html#FORBIDDEN
    assertEquals(Constraint.Authorization.FORBIDDEN, mapping.getConstraint().getAuthorization());
    assertTrue(mapping.getConstraint().getRoles().isEmpty());
  }

  @Test
  public void shouldCreateGlobalConstraintWithOptionsMethodOmission() {
    // Given:
    config = restConfigWith(ImmutableMap.of(
        RestConfig.REJECT_OPTIONS_REQUEST, true));

    // When:
    final ConstraintMapping mapping = AuthUtil.createGlobalAuthConstraint(config);

    // Then:
    assertThat(mapping.getMethodOmissions().length, is(1));
    assertThat(Arrays.stream(mapping.getMethodOmissions()).findFirst().get(), is("OPTIONS"));
  }

  @Test
  public void shouldCreateNoUnsecuredPathConstraints() {
    // Given:
    config = restConfigWith(ImmutableMap.of(
        RestConfig.AUTHENTICATION_SKIP_PATHS, ""));

    // When:
    final List<ConstraintMapping> mappings = AuthUtil.createUnsecuredConstraints(config);

    // Then:
    assertThat(mappings.size(), is(0));
  }

  @Test
  public void shouldCreateUnsecuredPathConstraints() {
    // Given:
    config = restConfigWith(ImmutableMap.of(
        RestConfig.AUTHENTICATION_SKIP_PATHS, "/path/1,/path/2"));

    // When:
    final List<ConstraintMapping> mappings = AuthUtil.createUnsecuredConstraints(config);

    // Then:
    assertThat(mappings.size(), is(2));
    assertThat(mappings.get(0).getMethod(), is("*"));
    assertThat(mappings.get(0).getPathSpec(), is("/path/1"));
    // Refer to https://javadoc.jetty.org/jetty-12/org/eclipse/jetty/security/Constraint.Authorization.html#INHERIT
    assertEquals(Constraint.Authorization.INHERIT, mappings.get(0).getConstraint().getAuthorization());
    assertThat(mappings.get(1).getMethod(), is("*"));
    assertThat(mappings.get(1).getPathSpec(), is("/path/2"));
    assertEquals(Constraint.Authorization.INHERIT, mappings.get(1).getConstraint().getAuthorization());
  }

  @Test
  public void shouldCreateUnsecuredPathConstraint() {
    // Given:
    config = restConfigWith(ImmutableMap.of());

    // When:
    final ConstraintMapping mappings =
        AuthUtil.createUnsecuredConstraint(config, "/path/*");

    // Then:
    // Refer to https://javadoc.jetty.org/jetty-12/org/eclipse/jetty/security/Constraint.Authorization.html#INHERIT
    assertThat(mappings.getMethod(), is("*"));
    assertThat(mappings.getPathSpec(), is("/path/*"));
    assertEquals(Constraint.Authorization.INHERIT, mappings.getConstraint().getAuthorization());
  }

  @Test
  public void shouldCreateSecuredPathConstraint() {
    // Given:
    config = restConfigWith(ImmutableMap.of());

    // When:
    final ConstraintMapping mappings =
        AuthUtil.createSecuredConstraint(config, "/path/*");

    // Then:
    assertThat(mappings.getMethod(), is("*"));
    assertThat(mappings.getPathSpec(), is("/path/*"));
    // Refer to https://javadoc.jetty.org/jetty-12/org/eclipse/jetty/security/Constraint.Authorization.html#KNOWN_ROLE
    assertEquals(Constraint.Authorization.KNOWN_ROLE, mappings.getConstraint().getAuthorization());
  }

  @Test
  public void shouldCreateConstraintWithNoOptions() {
    //Given:
    config = restConfigWith(ImmutableMap.of(RestConfig.REJECT_OPTIONS_REQUEST, true));

    //When:
    final Optional<ConstraintMapping> mappings =
        AuthUtil.createDisableOptionsConstraint(config);

    //Then:
    assertThat(mappings.isPresent(), is(true));
    assertThat(mappings.get().getMethod(), is("OPTIONS"));
    assertThat(mappings.get().getPathSpec(), is("/*"));
    // Refer to https://javadoc.jetty.org/jetty-12/org/eclipse/jetty/security/Constraint.Authorization.html#FORBIDDEN
    assertEquals(Constraint.Authorization.FORBIDDEN, mappings.get().getConstraint().getAuthorization());
  }

  private static RestConfig restConfigWith(final Map<String, Object> config) {
    return new RestConfig(RestConfig.baseConfigDef(), config);
  }
}