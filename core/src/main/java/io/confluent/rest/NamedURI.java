/*
 * Copyright 2021 Confluent Inc.
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

import java.net.URI;


public final class NamedURI {
  private final URI uri;
  private final String name;

  public NamedURI(URI uri, String name) {
    this.uri = uri;
    this.name = name;
  }

  public URI getURI() {
    return this.uri;
  }

  public String getName() {
    return this.name;
  }
}
