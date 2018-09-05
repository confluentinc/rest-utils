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

package io.confluent.rest.auth.jetty;

import io.confluent.rest.auth.AuthenticationResult;
import io.confluent.rest.auth.UserIdentity;
import io.confluent.rest.auth.Authenticator;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.security.DefaultIdentityService;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.UserAuthentication;
import org.eclipse.jetty.server.Authentication;
import org.eclipse.jetty.server.Authentication.User;
import org.eclipse.jetty.util.component.AbstractLifeCycle;

/**
 * Converts a KSQL authenticator into a Jetty authenticator.
 */
public class PluggableAuthenticator
    extends AbstractLifeCycle implements org.eclipse.jetty.security.Authenticator {

  private interface Handler<T extends AuthenticationResult> {

    Authentication handle(PluggableAuthenticator authenticator, T result);
  }

  private static final Map<
      Class<? extends AuthenticationResult>, Handler<AuthenticationResult>> HANDLERS;

  static {
    final HashMap<
        Class<? extends AuthenticationResult>,
        Handler<AuthenticationResult>> handlers = new HashMap<>();

    handlers.put(AuthenticationResult.User.class,
        castHandler(PluggableAuthenticator::handleUser, AuthenticationResult.User.class));
    handlers.put(AuthenticationResult.Failure.class,
        castHandler(PluggableAuthenticator::handleFailure, AuthenticationResult.Failure.class));

    HANDLERS = Collections.unmodifiableMap(handlers);
  }

  private final LoginService loginService;
  private final IdentityService identityService;  // Todo(ac): can be injected
  private final Authenticator delegate;

  public PluggableAuthenticator(final Authenticator delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.loginService = new NoOpLoginService(getAuthMethod());
    this.identityService = new DefaultIdentityService();
  }

  @Override
  public String getAuthMethod() {
    return delegate.getAuthMethod();
  }

  public LoginService getLoginService() {
    return loginService;
  }

  public IdentityService getIdentifyService() {
    return identityService;
  }

  @Override
  public void setConfiguration(final AuthConfiguration configuration) {
    if (configuration.getIdentityService() != identityService) {
      throw new IllegalStateException("Different identify service");
    }

    if (configuration.getLoginService() != loginService) {
      throw new IllegalStateException("Different login service");
    }
  }

  @Override
  public void prepareRequest(final ServletRequest request) {
  }

  @Override
  public Authentication validateRequest(
      final ServletRequest request,
      final ServletResponse response,
      final boolean mandatory) throws org.eclipse.jetty.security.ServerAuthException {
    try {
      final HttpServletRequest httpRequest = (HttpServletRequest) request;
      final HttpServletResponse httpResponse = (HttpServletResponse) response;

      final AuthenticationResult authenticate = delegate.authenticate(httpRequest, httpResponse);
      return handleResult(authenticate);
    } catch (final Exception e) {
      throw new org.eclipse.jetty.security.ServerAuthException(e);
    }
  }

  @Override
  public boolean secureResponse(
      final ServletRequest request,
      final ServletResponse response,
      final boolean mandatory,
      final User validatedUser) {
    return true;
  }

  protected void doStop() {
    delegate.shutdown();
  }

  public Authentication handleResult(final AuthenticationResult result) {
    return HANDLERS.entrySet()
        .stream()
        .filter(e -> e.getKey().isAssignableFrom(result.getClass()))
        .findAny()
        .orElseThrow(() ->
            new UnsupportedOperationException("Unknown input type" + result.getClass()))
        .getValue()
        .handle(this, result);
  }

  private Authentication handleUser(final AuthenticationResult.User result) {
    final UserIdentity user = result.getUserIdentity();
    final String[] userRoles = user.getUserRoles().toArray(new String[0]);
    final org.eclipse.jetty.server.UserIdentity output = identityService
        .newUserIdentity(user.getSubject(), user.getUserPrincipal(), userRoles);
    return new UserAuthentication(getAuthMethod(), output);
  }

  private Authentication handleFailure(final AuthenticationResult.Failure result) {
    return new Authentication.Failure() {
      @Override
      public String toString() {
        return result.toString();
      }
    };
  }

  @SuppressWarnings("unchecked")
  private static <T extends AuthenticationResult> Handler<AuthenticationResult> castHandler(
      final Handler<T> mapper,
      final Class<T> type) {
    return (authenticator, result) -> mapper.handle(authenticator, type.cast(result));
  }
}
