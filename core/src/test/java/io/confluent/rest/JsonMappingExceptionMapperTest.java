/*
 * Copyright 2021 - 2022 Confluent Inc.
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

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.rest.entities.ErrorMessage;
import io.confluent.rest.exceptions.JsonMappingExceptionMapper;

import javax.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class JsonMappingExceptionMapperTest {

  private JsonMappingExceptionMapper mapper;

  @BeforeEach
  public void setUp() {
    mapper = new JsonMappingExceptionMapper();
  }

  @Test
  public void testJsonMappingException() {
    try {
      String json = "{\"name\":{}}";
      ObjectMapper mapper = new ObjectMapper();
      // try to parse a json where the User name is expecting a string but input an
      // Object
      mapper.reader().forType(User.class).readValue(json);
    } catch (JsonMappingException e) {
      Response response = mapper.toResponse(e);
      assertEquals(400, response.getStatus());
      ErrorMessage out = (ErrorMessage) response.getEntity();
      assertEquals(400, out.getErrorCode());
    } catch (Exception e) {
      fail("A JsonMappingException is expected.");
    }
  }

  @Test
  public void testJsonMappingExceptionRemoveDetailsFromMessage() {
    JsonMappingException jsonMappingException = new JsonMappingException("Json mapping error (for Object starting at:",
        JsonLocation.NA);
    jsonMappingException.prependPath(new JsonMappingException.Reference("path"), 0);

    Response response = mapper.toResponse(jsonMappingException);
    assertEquals(400, response.getStatus());
    ErrorMessage out = (ErrorMessage) response.getEntity();
    assertEquals(400, out.getErrorCode());
    assertEquals("Json mapping error", out.getMessage());
  }

  @Test
  public void testJsonMappingExceptionRemoveDetailsCannotConstruct() {
    JsonMappingException jsonMappingException = new JsonMappingException(
        "Cannot construct instance of `io.confluent.kafkarest.entities.v3.CreateAclRequest`, problem: Null resourceType",
        JsonLocation.NA);
    jsonMappingException.prependPath(new JsonMappingException.Reference("path"), 0);

    Response response = mapper.toResponse(jsonMappingException);
    assertEquals(400, response.getStatus());
    ErrorMessage out = (ErrorMessage) response.getEntity();
    assertEquals(400, out.getErrorCode());
    assertEquals("Cannot construct instance of `CreateAclRequest`, problem: Null resourceType", out.getMessage());
  }

  @Test
  public void testJsonMappingExceptionRemoveDetailsCannotDeserializeEnum() {
    JsonMappingException jsonMappingException = new JsonMappingException(
        "Cannot deserialize value of type `io.confluent.kafkarest.entities.Acl$ResourceType` from String \"TEAPOT\": not one of the values accepted for Enum class: [TRANSACTIONAL_ID, DELEGATION_TOKEN, UNKNOWN, ANY, GROUP, CLUSTER, TOPIC]",
        JsonLocation.NA);
    jsonMappingException.prependPath(new JsonMappingException.Reference("path"), 0);

    Response response = mapper.toResponse(jsonMappingException);
    assertEquals(400, response.getStatus());
    ErrorMessage out = (ErrorMessage) response.getEntity();
    assertEquals(400, out.getErrorCode());
    assertEquals(
        "Cannot deserialize value of type `ResourceType` from String \"TEAPOT\": not one of the " +
        "values accepted for Enum class: [TRANSACTIONAL_ID, DELEGATION_TOKEN, UNKNOWN, ANY, " +
        "GROUP, CLUSTER, TOPIC]",
        out.getMessage());
  }

  @Test
  public void testSanitizeExceptionMessage() {
    assertEquals(null, mapper.sanitizeExceptionMessage(null));
    assertEquals("a.b.c$D", mapper.sanitizeExceptionMessage("a.b.c$D"));
    assertEquals("`D`", mapper.sanitizeExceptionMessage("`a.b.c$D`"));
    assertEquals("x`c$`y", mapper.sanitizeExceptionMessage("x`a.b.c$`y"));
    assertEquals("`D`", mapper.sanitizeExceptionMessage("`a.b.c$D`"));
    assertEquals("x`Def`y", mapper.sanitizeExceptionMessage("x`a.b.c$Def`y"));
    assertEquals("`D`", mapper.sanitizeExceptionMessage("`D`"));
    assertEquals("x`D`y", mapper.sanitizeExceptionMessage("x`a.b.c.D`y"));
    assertEquals("This `Name` is more `complicated`.",
        mapper.sanitizeExceptionMessage("This `class.Name` is more `complicated`."));
    assertEquals("This one`s just obtuse.",
        mapper.sanitizeExceptionMessage("This one`s just obtuse."));
    assertEquals("", mapper.sanitizeExceptionMessage(""));
    assertEquals("`", mapper.sanitizeExceptionMessage("`"));
    assertEquals("``", mapper.sanitizeExceptionMessage("``"));
    assertEquals("```", mapper.sanitizeExceptionMessage("```"));
    assertEquals("````", mapper.sanitizeExceptionMessage("````"));
  }

  class User {
    public String name;

    User(String name) {
      this.name = name;
    }
  }
}
