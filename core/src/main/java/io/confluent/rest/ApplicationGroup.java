/*
 * Copyright 2019 Confluent Inc.
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

final class ApplicationGroup {
  private final ApplicationServer<?> server;

  private final List<Application<?>> applications = new ArrayList<>();

  ApplicationGroup(ApplicationServer<?> server) {
    this.server = Objects.requireNonNull(server);
  }

  void addApplication(Application<?> application) {
    application.setServer(server);
    applications.add(application);
  }

  List<Application<?>> getApplications() {
    return Collections.unmodifiableList(applications);
  }

  void doStop() {
    for (Application<?> application: applications) {
      application.metrics.close();
      application.doShutdown();
    }
  }
}
