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

package io.confluent.kafkarest.exceptions;

/**
 * Test double for the real InvalidConfigurationWithSchemaException in kafka-rest.
 * Used to test KafkaExceptionMapper's reflection-based handling of Odyssey schema errors.
 *
 * <p>This class must have the exact same fully qualified name and method signature as the
 * real exception for the reflection-based detection to work in tests.
 */
public class InvalidConfigurationWithSchemaException extends RuntimeException {

  private final int schemaErrorCode;

  public InvalidConfigurationWithSchemaException(String message, int schemaErrorCode) {
    super(message);
    this.schemaErrorCode = schemaErrorCode;
  }

  public int getSchemaErrorCode() {
    return schemaErrorCode;
  }
}
