/*
 * Copyright 2022 Confluent Inc.
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

package io.confluent.rest;

import javax.annotation.Nullable;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;

public final class NamedURI {
  private final URI uri;
  @Nullable
  private final String name;

  public NamedURI(URI uri, @Nullable String name) {
    this.uri = uri;
    this.name = name != null ? name.toLowerCase(Locale.ENGLISH) : null;
  }

  public URI getUri() {
    return uri;
  }

  @Nullable
  public String getName() {
    return name;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    NamedURI namedURI = (NamedURI) o;
    return uri.equals(namedURI.uri) && Objects.equals(name, namedURI.name);
  }

  @Override
  public int hashCode() {
    return Objects.hash(uri, name);
  }

  @Override
  public String toString() {
    return "NamedURI{uri=" + uri + ", name='" + name + "'}";
  }
}
