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

import java.security.Principal;
import java.util.List;
import javax.security.auth.Subject;

/**
 * The result of an authentication request.
 *
 * <p>The result can be one of several sub-types that reflect the result of the authentication
 * request.
 */
public interface AuthenticationResult {

  /**
   * A successful Authentication with User information.
   */
  interface User extends AuthenticationResult {

    UserIdentity getUserIdentity();
  }

  /**
   * An Authentication Failure has been sent.
   */
  interface Failure extends AuthenticationResult {

  }

  static AuthenticationResult.User user(
      final Subject subject,
      final Principal userPrincipal,
      final List<String> roles) {

    // todo(ac): move to concrete type
    final UserIdentity identity = new UserIdentity() {
      @Override
      public Subject getSubject() {
        return subject;
      }

      @Override
      public Principal getUserPrincipal() {
        return userPrincipal;
      }

      @Override
      public List<String> getUserRoles() {
        return roles;
      }
    };

    return () -> identity;
  }

  AuthenticationResult.Failure SENT_FAILURE = new Failure() {
    @Override
    public String toString() {
      return "FAILURE";
    }
  };
}
