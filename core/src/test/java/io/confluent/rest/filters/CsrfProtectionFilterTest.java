/*
 * Copyright 2018 Confluent Inc.
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

package io.confluent.rest.filters;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.common.collect.ImmutableMap;
import io.confluent.rest.RestConfig;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class CsrfProtectionFilterTest {

  private CsrfTokenProtectionFilter filter;

  @BeforeEach
  public void setUp() {
    filter = new CsrfTokenProtectionFilter();
  }

  @Test
  public void testHasDefaultValues() throws ServletException {
    filter.init(new TestFilterConfig(new HashMap<>()));

    assertEquals(filter.getCsrfTokenEndpoint(), RestConfig.CSRF_PREVENTION_TOKEN_FETCH_ENDPOINT_DEFAULT);
    assertEquals(
        filter.getCsrfTokenExpiration(),
        RestConfig.CSRF_PREVENTION_TOKEN_EXPIRATION_MINUTES_DEFAULT);
    assertEquals(
        filter.getCsrfTokenMaxEntries(), RestConfig.CSRF_PREVENTION_TOKEN_MAX_ENTRIES_DEFAULT);
  }

  @Test
  public void testOverrideDefaultValues() throws ServletException {
    filter.init(
        new TestFilterConfig(
            ImmutableMap.of(
                RestConfig.CSRF_PREVENTION_TOKEN_FETCH_ENDPOINT,
                "/test-ep",
                RestConfig.CSRF_PREVENTION_TOKEN_EXPIRATION_MINUTES,
                "10",
                RestConfig.CSRF_PREVENTION_TOKEN_MAX_ENTRIES,
                "70")));

    assertEquals("/test-ep", filter.getCsrfTokenEndpoint());
    assertEquals(10, filter.getCsrfTokenExpiration());
    assertEquals(70, filter.getCsrfTokenMaxEntries());
  }

  class TestFilterConfig implements FilterConfig {
    private Map<String, String> map = new HashMap<>();

    public TestFilterConfig(Map<String, String> map) {
      this.map = map;
    }

    @Override
    public String getFilterName() {
      return "test-filter";
    }

    @Override
    public ServletContext getServletContext() {
      return null;
    }

    @Override
    public String getInitParameter(String s) {
      return map.get(s);
    }

    @Override
    public Enumeration<String> getInitParameterNames() {
      return Collections.enumeration(map.keySet());
    }
  }
}
