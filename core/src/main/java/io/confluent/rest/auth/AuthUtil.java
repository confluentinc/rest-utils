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

import io.confluent.rest.RestConfig;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.util.security.Constraint;

public final class AuthUtil {

  private AuthUtil() {
  }

  /**
   * Checks if {@link RestConfig#ACCESS_CONTROL_ALLOW_ORIGIN_CONFIG} is not empty.
   *
   * @param restConfig the rest app's config.
   * @return true if not empty, false otherwise.
   */
  public static boolean isCorsEnabled(final RestConfig restConfig) {
    String allowedOrigins = restConfig.getString(RestConfig.ACCESS_CONTROL_ALLOW_ORIGIN_CONFIG);
    return !allowedOrigins.trim().isEmpty();
  }

  /**
   * Checks if {@link RestConfig#ACCESS_CONTROL_SKIP_OPTIONS} is set.
   *
   * @param restConfig the rest app's config.
   * @return true if not empty, false otherwise.
   */
  public static boolean isSkipOptionsAuth(final RestConfig restConfig) {
    boolean skipOption = restConfig.getBoolean(RestConfig.ACCESS_CONTROL_SKIP_OPTIONS);
    return skipOption;
  }

  /**
   * Checks if {@link RestConfig#REJECT_OPTIONS_REQUEST} is set.
   *
   * @param restConfig the rest app's config.
   * @return true if not empty, false otherwise.
   */
  public static boolean isRejectOptions(final RestConfig restConfig) {
    boolean rejectOption = restConfig.getBoolean(RestConfig.REJECT_OPTIONS_REQUEST);
    return rejectOption;
  }

  /**
   * Build the standard global auth constraint from standard RestConfig.
   *
   * <p>The valid roles is extracted from {@link RestConfig#AUTHENTICATION_ROLES_CONFIG}
   *
   * <p>OPTIONS requests will not require auth if
   * {@link RestConfig#ACCESS_CONTROL_ALLOW_ORIGIN_CONFIG} is not empty.
   *
   * @param restConfig the rest app's config.
   * @return the constraint mapping.
   */
  public static ConstraintMapping createGlobalAuthConstraint(final RestConfig restConfig) {
    return createConstraint(restConfig, true, "/*");
  }

  /**
   * Build a secured auth constraint from standard RestConfig for a path.
   *
   * <p>The valid roles is extracted from {@link RestConfig#AUTHENTICATION_ROLES_CONFIG}
   *
   * <p>OPTIONS requests will not require auth if
   * {@link RestConfig#ACCESS_CONTROL_ALLOW_ORIGIN_CONFIG} is not empty.
   *
   * @param restConfig the rest app's config.
   * @param pathSpec path for constraint.
   * @return the constraint mapping.
   */
  public static ConstraintMapping createSecuredConstraint(
      final RestConfig restConfig,
      final String pathSpec
  ) {
    return createConstraint(restConfig, true, pathSpec);
  }

  /**
   * Build an unsecured auth constraint from standard RestConfig for a path.
   *
   * <p>The valid roles is extracted from {@link RestConfig#AUTHENTICATION_ROLES_CONFIG}
   *
   * <p>OPTIONS requests will not require auth if
   * {@link RestConfig#ACCESS_CONTROL_ALLOW_ORIGIN_CONFIG} is not empty.
   *
   * @param restConfig the rest app's config.
   * @param pathSpec path for constraint.
   * @return the constraint mapping.
   */
  public static ConstraintMapping createUnsecuredConstraint(
      final RestConfig restConfig,
      final String pathSpec
  ) {
    return createConstraint(restConfig, false, pathSpec);
  }

  /**
   * Build constraints for any unsecured paths defined in standard RestConfig.
   *
   * @param restConfig the rest app's config.
   * @return the list of constraint mappings.
   */
  public static List<ConstraintMapping> createUnsecuredConstraints(final RestConfig restConfig) {
    final List<String> unsecuredPaths = restConfig.getList(RestConfig.AUTHENTICATION_SKIP_PATHS);

    return unsecuredPaths.stream()
        .map(p -> createConstraint(restConfig, false, p))
        .collect(Collectors.toList());
  }

  /**
   * Build a secure or unsecure constraint using standard RestConfig for a path.
   *
   * @param restConfig the rest app's config.
   * @param authenticate authentication flag.
   * @param pathSpec path for constraint.
   * @return the constraint mapping.
   */
  private static ConstraintMapping createConstraint(
      final RestConfig restConfig,
      final boolean authenticate,
      final String pathSpec
  ) {
    final Constraint constraint = new Constraint();
    constraint.setAuthenticate(authenticate);
    if (authenticate) {
      final List<String> roles = restConfig.getList(RestConfig.AUTHENTICATION_ROLES_CONFIG);
      constraint.setRoles(roles.toArray(new String[0]));
    }

    final ConstraintMapping mapping = new ConstraintMapping();
    mapping.setConstraint(constraint);
    mapping.setMethod("*");

    if (isRejectOptions(restConfig)) {
      mapping.setMethodOmissions(new String[]{"OPTIONS"});
    }

    if (authenticate && AuthUtil.isCorsEnabled(restConfig) && isSkipOptionsAuth(restConfig)) {
      mapping.setMethodOmissions(new String[]{"OPTIONS"});
    }
    mapping.setPathSpec(pathSpec);
    return mapping;
  }

  public static Optional<ConstraintMapping>
        createDisableOptionsConstraint(final RestConfig config) {

    if (isRejectOptions(config)) {

      Constraint forbidConstraint = new Constraint();
      forbidConstraint.setName("Disable OPTIONS");
      //equivalent of setting an empty <auth-constraint> if no setRoles(String []) is set,
      // forbidding access
      forbidConstraint.setAuthenticate(true);

      ConstraintMapping forbidMapping = new ConstraintMapping();
      forbidMapping.setMethod("OPTIONS");
      forbidMapping.setPathSpec("/*");
      forbidMapping.setConstraint(forbidConstraint);
      return Optional.of(forbidMapping);

    }
    return Optional.empty();
  }
}
