/**
 * Copyright 2014 Confluent Inc.
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

public interface Configuration {

  /**
   * Returns true if the server should run in debug mode.
   */
  public boolean getDebug();

  /**
   * Get an ordered list of the preferred response media types, from most preferred to least
   * preferred.
   */
  public Iterable<String> getPreferredResponseMediaTypes();

  /**
   * Get the default response media type, i.e. the value that should be used if no specific types
   * are requested
   */
  public String getDefaultResponseMediaType();
}
