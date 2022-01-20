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

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.rest.entities.ErrorMessage;
import io.confluent.rest.exceptions.JsonMappingExceptionMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.ws.rs.core.Response;

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
      // try to parse a json where the User name is expecting a string but input an Object
      mapper.reader().forType(User.class).readValue(json);
    } catch (JsonMappingException e) {
      Response response = mapper.toResponse(e);
      assertEquals(400, response.getStatus());
      ErrorMessage out = (ErrorMessage)response.getEntity();
      assertEquals(400, out.getErrorCode());
    } catch (Exception e) {
      fail("A JsonMappingException is expected.");
    }
  }

  class User {
    public String name;

    User(String name) {
      this.name = name;
    }
  }
}
