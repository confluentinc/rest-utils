/**
 * Copyright 2014 Confluent Inc.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.confluent.rest.auth;

/**
 * Allows to transform extracted principal name to other form.
 * Useful for transforming DN extracted in RestX509CertificatePrincipalBuilder
 * to preferred principal name.
 */
public interface PrincipalNameConverter {

  String convertPrincipalName(String principalName);
}
