/**
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
 **/

package io.confluent.rest.contexts;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.SecurityContext;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class RestSecurityContextFactoryTests {
  private static final String AUTHORIZATION_HEADER_NAME = "Authorization";

  @Mock
  private HttpHeaders httpHeaders;

  @Mock
  private SecurityContext securityContext;

  @Before
  public void setUp() {

  }

  @Test
  public void shouldGetBearerTokenWhenFound() {
    // Given:
    givenAuthorizationHeader(Arrays.asList(
        "Basic user:pass",
        "Bearer 123"
    ));

    // When:
    RestSecurityContext securityContext = createRestSecurityContext();

    // Then:
    assertThat(securityContext.getBearerToken(), is("123"));
  }

  @Test
  public void shouldGetEmptyBearerTokenWhenNotFound() {
    // Given:
    givenAuthorizationHeader(Arrays.asList(
        "Basic user:pass",
        "Bearer123",
        "BEARER x"
    ));

    // When:
    RestSecurityContext securityContext = createRestSecurityContext();

    // Then:
    assertThat(securityContext.getBearerToken(), is(""));
  }

  @Test
  public void shouldGetEmptyBearerTokenWhenAuthorizationNotFound() {
    // Given:
    givenAuthorizationHeader( null);

    // When:
    RestSecurityContext securityContext = createRestSecurityContext();

    // Then:
    assertThat(securityContext.getBearerToken(), is(""));
  }

  private void givenAuthorizationHeader(List<String> values) {
    when(httpHeaders.getRequestHeader(AUTHORIZATION_HEADER_NAME))
        .thenReturn(values);
  }

  private void givenBearerToken(String token) {
    when(httpHeaders.getRequestHeader("Authorization"))
        .thenReturn(Collections.singletonList(token));
  }

  private RestSecurityContext createRestSecurityContext() {
    return new RestSecurityContextFactory(securityContext, httpHeaders).provide();
  }
}
