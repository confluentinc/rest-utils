/*
 * Copyright 2024 Confluent Inc.
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

import io.confluent.rest.entities.ErrorMessage;
import io.confluent.rest.exceptions.RestException;
import io.confluent.rest.exceptions.WebApplicationExceptionMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DebuggableExceptionMapperTest {

  @Test
  public void testClientErrorNotLoggedWhenDebugDisabled() {
    Properties props = new Properties();
    props.setProperty("debug", "false");
    RestConfig config = new TestRestConfig(props);
    WebApplicationExceptionMapper mapper = new WebApplicationExceptionMapper(config);

    Response response = mapper.toResponse(new RestException("subject not found", 404, 40401));

    assertEquals(404, response.getStatus());
    ErrorMessage errorMessage = (ErrorMessage) response.getEntity();
    assertEquals("subject not found", errorMessage.getMessage());
  }

  @Test
  public void testClientErrorMessageIncludesStackTraceWhenDebugEnabled() {
    Properties props = new Properties();
    props.setProperty("debug", "true");
    RestConfig config = new TestRestConfig(props);
    WebApplicationExceptionMapper mapper = new WebApplicationExceptionMapper(config);

    Response response = mapper.toResponse(new RestException("subject not found", 404, 40401));

    assertEquals(404, response.getStatus());
    ErrorMessage errorMessage = (ErrorMessage) response.getEntity();
    assertTrue(errorMessage.getMessage().contains("RestException"),
        "Expected stack trace in message when debug=true");
  }

  @Test
  public void testServerErrorMessageNotExpandedWhenDebugDisabled() {
    Properties props = new Properties();
    props.setProperty("debug", "false");
    RestConfig config = new TestRestConfig(props);
    WebApplicationExceptionMapper mapper = new WebApplicationExceptionMapper(config);

    Response response = mapper.toResponse(new RestException("internal error", 500, 50001));

    assertEquals(500, response.getStatus());
    ErrorMessage errorMessage = (ErrorMessage) response.getEntity();
    assertFalse(errorMessage.getMessage().contains("RestException"),
        "Expected no stack trace in message when debug=false");
  }
}
