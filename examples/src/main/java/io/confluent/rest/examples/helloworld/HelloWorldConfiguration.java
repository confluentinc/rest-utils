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
package io.confluent.rest.examples.helloworld;

import io.confluent.rest.Configuration;

/**
 * Configuration classes are a convenient place to pass global configuration settings for your
 * application around. rest-utils requires a few methods to be implemented (e.g. getPort() to
 * indicate what port the server should listen on), but all of which have reasonable default
 * implementations.
 */
public class HelloWorldConfiguration extends Configuration {
  private final String greeting;

  public HelloWorldConfiguration() {
    this.greeting = "Hello, %s!";
  }

  public HelloWorldConfiguration(String greeting) {
    this.greeting = greeting;
  }

  public String getGreeting() {
    return greeting;
  }
}
