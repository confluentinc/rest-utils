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

import java.util.Objects;
import javax.servlet.ServletRequest;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.server.UserIdentity;

/**
 * A dummy LoginService impl to keep Jetty happy, but which isn't actually used by the
 * authentication plugins.
 */
class NoOpLoginService implements LoginService {

  private final String name;

  NoOpLoginService(final String name) {
    this.name = Objects.requireNonNull(name, "name");
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public UserIdentity login(
      final String username,
      final Object credentials,
      final ServletRequest request) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean validate(final UserIdentity user) {
    return true;
  }

  @Override
  public IdentityService getIdentityService() {
    return null;
  }

  @Override
  public void setIdentityService(final IdentityService service) {
  }

  @Override
  public void logout(final UserIdentity user) {
  }
}
