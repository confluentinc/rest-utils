/*
 * Copyright 2022 Confluent Inc.
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
import com.fasterxml.jackson.core.JsonParseException;
import io.confluent.rest.entities.ErrorMessage;
import io.confluent.rest.exceptions.JsonParseExceptionMapper;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class JsonParseExceptionMapperTest {

  private JsonParseExceptionMapper mapper;

  @BeforeEach
  public void setUp() {
    mapper = new JsonParseExceptionMapper();
  }

  @Test
  public void testJsonParseExceptionRemoveDetailsFromMessage() {
      JsonParseException jsonParseException = new JsonParseException("Json parse error", JsonLocation.NA);

      Response response = mapper.toResponse(jsonParseException);
      assertEquals(400, response.getStatus());
      ErrorMessage out = (ErrorMessage)response.getEntity();
      assertEquals(400, out.getErrorCode());
      assertEquals("Json parse error", out.getMessage());
      assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());
  }
}
