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
import org.eclipse.jetty.util.StringUtil;

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
   * Checks if {@link RestConfig#RESPONSE_HTTP_HEADERS_CONFIG} is not empty.
   *
   * @param restConfig the rest app's config.
   * @return true if not empty, false otherwise.
   */
  public static boolean isHttpResponseHeadersConfigured(final RestConfig restConfig) {
    List<String> headers = restConfig.getList(RestConfig.RESPONSE_HTTP_HEADERS_CONFIG);
    return !headers.isEmpty();
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
   * Validate Http response headers config.
   *
   * @param headerConfig the header config which is in format of [action] [name]:[value]
   *        which use same foramt defined in Jetty HeaderFilter defined in following link
   * @link https://www.eclipse.org/jetty/javadoc/9.4.27.v20200227/org/eclipse/jetty/servlets/HeaderFilter.html
   * @return void
   */
  public static void validateHttpResponseHeadersConfigs(String headerConfig) {
    String[] configs = StringUtil.csvSplit(headerConfig);
    for (String config : configs) {
      validateHeaderConfig(config);
    }
  }

  private static void validateHeaderConfig(String config) {
    try {
      String[] configTokens = config.trim().split(" ", 2);
      String method = configTokens[0].trim();
      RestConfig.validateHeaderConfigAction(method.toLowerCase());
      String header = configTokens[1];
      String[] headerTokens = header.trim().split(":", 2);
      String headerName = headerTokens[0].trim();
      String headerValue = headerTokens[1].trim();
    } catch (ArrayIndexOutOfBoundsException e) {
      RestConfig.throwInvalidHttpResponseHeaderConfigException(config, e);
    }
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
    if (authenticate && AuthUtil.isCorsEnabled(restConfig)) {
      mapping.setMethodOmissions(new String[]{"OPTIONS"});
    }
    mapping.setPathSpec(pathSpec);
    return mapping;
  }
}
