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

import io.spiffe.spiffeid.SpiffeId;
import java.security.Principal;
import java.util.Objects;

/**
 * The caller authenticated by {@link SpiffeJwtAuthenticator}. {@link #getName()} returns the
 * SPIFFE ID string, e.g. {@code spiffe://example.org/ns/foo/sa/bar}.
 */
public final class SpiffeIdPrincipal implements Principal {

  private final SpiffeId spiffeId;

  public SpiffeIdPrincipal(SpiffeId spiffeId) {
    this.spiffeId = Objects.requireNonNull(spiffeId);
  }

  public SpiffeId getSpiffeId() {
    return spiffeId;
  }

  @Override
  public String getName() {
    return spiffeId.toString();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof SpiffeIdPrincipal && spiffeId.equals(((SpiffeIdPrincipal) o).spiffeId);
  }

  @Override
  public int hashCode() {
    return spiffeId.hashCode();
  }

  @Override
  public String toString() {
    return getName();
  }
}
