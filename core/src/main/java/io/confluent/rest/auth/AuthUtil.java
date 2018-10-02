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
import java.util.stream.Collectors;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.util.security.Constraint;

public final class AuthUtil {

  private AuthUtil() {
  }

  public static boolean isCorsEnabled(final RestConfig restConfig) {
    String allowedOrigins = restConfig.getString(RestConfig.ACCESS_CONTROL_ALLOW_ORIGIN_CONFIG);
    return !allowedOrigins.trim().isEmpty();
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
   * @return the constraint mapping
   */
  public static ConstraintMapping createGlobalAuthConstraint(final RestConfig restConfig) {
    final List<String> roles = restConfig.getList(RestConfig.AUTHENTICATION_ROLES_CONFIG);
    final boolean omitOptions = isCorsEnabled(restConfig);

    final Constraint constraint = new Constraint();
    constraint.setAuthenticate(true);
    constraint.setRoles(roles.toArray(new String[0]));

    final ConstraintMapping mapping = new ConstraintMapping();
    mapping.setConstraint(constraint);
    mapping.setMethod("*");
    if (omitOptions) {
      mapping.setMethodOmissions(new String[]{"OPTIONS"});
    }
    mapping.setPathSpec("/*");
    return mapping;
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
        .map(AuthUtil::toUnsecuredConstraint)
        .collect(Collectors.toList());
  }

  private static ConstraintMapping toUnsecuredConstraint(final String unsecuredPath) {
    final Constraint constraint = new Constraint();
    constraint.setAuthenticate(false);

    final ConstraintMapping mapping = new ConstraintMapping();
    mapping.setConstraint(constraint);
    mapping.setMethod("*");
    mapping.setPathSpec(unsecuredPath);
    return mapping;
  }
}
