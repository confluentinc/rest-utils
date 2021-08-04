/*
 * Copyright 2015 Confluent Inc.
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

import org.junit.Before;
import org.junit.Test;

import java.util.Properties;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

import io.confluent.rest.entities.ErrorMessage;
import io.confluent.rest.exceptions.RestException;
import io.confluent.rest.exceptions.WebApplicationExceptionMapper;

import static org.junit.Assert.*;

public class WebApplicationExceptionMapperTest {

  private WebApplicationExceptionMapper mapper;

  @Before
  public void setUp() {
    Properties props = new Properties();
    props.setProperty("debug", "false");
    RestConfig config = new TestRestConfig(props);
    mapper = new WebApplicationExceptionMapper(config);
  }

  @Test
  public void testRestException() {
    Response response = mapper.toResponse(new RestException("msg", 400, 1000));
    assertEquals(400, response.getStatus());
    ErrorMessage out = (ErrorMessage)response.getEntity();
    assertEquals("msg", out.getMessage());
    assertEquals(1000, out.getErrorCode());
  }

  @Test
  public void testNonRestWebApplicationException() {
    Response response = mapper.toResponse(new WebApplicationException("msg", 400));
    assertEquals(400, response.getStatus());
    ErrorMessage out = (ErrorMessage)response.getEntity();
    assertEquals("msg", out.getMessage());
    assertEquals(400, out.getErrorCode());
  }

  @Test
  public void testRestException4xx() {
    Response response = mapper.toResponse(new RestException("msg", 422, 1000));
    assertEquals(422, response.getStatus());
    ErrorMessage out = (ErrorMessage)response.getEntity();
    assertEquals("msg", out.getMessage());
    assertEquals(1000, out.getErrorCode());

    response = mapper.toResponse(new RestException("msg", 417, 1000));
    assertEquals(417, response.getStatus());
    out = (ErrorMessage)response.getEntity();
    assertEquals("msg", out.getMessage());
    assertEquals(1000, out.getErrorCode());

    try {
      response = mapper.toResponse(new RestException("msg", 1000, 1000));
      fail("Illegal http status code should have failed");
    } catch(IllegalArgumentException e) {

    }
  }
}
