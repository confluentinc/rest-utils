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

package io.confluent.rest.entities;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ErrorMessageTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  public void testConstructorWithoutSchemaErrorCode() {
    ErrorMessage errorMessage = new ErrorMessage(40002, "Some error message");

    assertEquals(40002, errorMessage.getErrorCode());
    assertEquals("Some error message", errorMessage.getMessage());
    assertNull(errorMessage.getSchemaErrorCode());
  }

  @Test
  public void testConstructorWithSchemaErrorCode() {
    ErrorMessage errorMessage = new ErrorMessage(40002, "Schema validation failed", 40901);

    assertEquals(40002, errorMessage.getErrorCode());
    assertEquals("Schema validation failed", errorMessage.getMessage());
    assertEquals(40901, errorMessage.getSchemaErrorCode());
  }

  @Test
  public void testSerializationWithSchemaErrorCode() throws Exception {
    ErrorMessage errorMessage = new ErrorMessage(40002, "Schema validation failed", 40901);

    String json = mapper.writeValueAsString(errorMessage);

    assertTrue(json.contains("\"error_code\":40002"));
    assertTrue(json.contains("\"message\":\"Schema validation failed\""));
    assertTrue(json.contains("\"schema_error_code\":40901"));
  }

  @Test
  @DisplayName("schema_error_code is omitted from JSON when null (due to @JsonInclude(NON_NULL))")
  public void testSerializationWithoutSchemaErrorCode() throws Exception {
    ErrorMessage errorMessage = new ErrorMessage(40002, "Some error");

    String json = mapper.writeValueAsString(errorMessage);

    assertTrue(json.contains("\"error_code\":40002"));
    assertTrue(json.contains("\"message\":\"Some error\""));
    assertFalse(json.contains("schema_error_code"));
  }

  @Test
  public void testDeserializationWithSchemaErrorCode() throws Exception {
    String json = "{\"error_code\":40002,\"message\":\"Schema error\",\"schema_error_code\":40901}";

    ErrorMessage errorMessage = mapper.readValue(json, ErrorMessage.class);

    assertEquals(40002, errorMessage.getErrorCode());
    assertEquals("Schema error", errorMessage.getMessage());
    assertEquals(40901, errorMessage.getSchemaErrorCode());
  }

  @Test
  public void testDeserializationWithoutSchemaErrorCode() throws Exception {
    String json = "{\"error_code\":40002,\"message\":\"Some error\"}";

    ErrorMessage errorMessage = mapper.readValue(json, ErrorMessage.class);

    assertEquals(40002, errorMessage.getErrorCode());
    assertEquals("Some error", errorMessage.getMessage());
    assertNull(errorMessage.getSchemaErrorCode());
  }

  @Test
  public void testEqualsWithSchemaErrorCode() {
    ErrorMessage msg1 = new ErrorMessage(40002, "error", 40901);
    ErrorMessage msg2 = new ErrorMessage(40002, "error", 40901);
    ErrorMessage msg3 = new ErrorMessage(40002, "error", 40902);
    ErrorMessage msg4 = new ErrorMessage(40002, "error");

    assertEquals(msg1, msg2);
    assertNotEquals(msg1, msg3);
    assertNotEquals(msg1, msg4);
  }

  @Test
  public void testHashCodeWithSchemaErrorCode() {
    ErrorMessage msg1 = new ErrorMessage(40002, "error", 40901);
    ErrorMessage msg2 = new ErrorMessage(40002, "error", 40901);
    ErrorMessage msg3 = new ErrorMessage(40002, "error", 40902);

    assertEquals(msg1.hashCode(), msg2.hashCode());
    assertNotEquals(msg1.hashCode(), msg3.hashCode());
  }

  @Test
  public void testSetSchemaErrorCode() {
    ErrorMessage errorMessage = new ErrorMessage(40002, "error");
    assertNull(errorMessage.getSchemaErrorCode());

    errorMessage.setSchemaErrorCode(40901);
    assertEquals(40901, errorMessage.getSchemaErrorCode());

    errorMessage.setSchemaErrorCode(null);
    assertNull(errorMessage.getSchemaErrorCode());
  }
}
