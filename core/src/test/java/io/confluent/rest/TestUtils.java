/*
 * Copyright 2023 Confluent Inc.
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

import java.io.IOException;
import java.net.ServerSocket;

class TestUtils {
  private TestUtils() {
    // prevent instantiation of this class
  }

  static int getFreePort() {
    for (int attempt = 0; attempt < 10; attempt++) {
      try (ServerSocket socket = new ServerSocket(0)) {
        int port = socket.getLocalPort();
        assert port > 0;
        return port;
      } catch (IOException e) {
        // skip for next retry
      }
    }
    return 0;
  }
}
