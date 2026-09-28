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

package io.confluent.rest.extension;


import io.confluent.rest.Application;
import io.confluent.rest.RestConfig;
import java.io.Closeable;
import java.io.IOException;
import jakarta.ws.rs.core.Configurable;

/**
 * A extension to the rest server.
 */
public interface ResourceExtension<AppT extends Application<? extends RestConfig>>
    extends Closeable {

  /**
   * Called to register the extension's resources when the rest server starts up.
   *
   * @param config the configuration used to register resources
   * @param app the application.
   */
  void register(Configurable<?> config, AppT app);

  @SuppressWarnings("RedundantThrows")
  default void close() throws IOException {
  }
}
