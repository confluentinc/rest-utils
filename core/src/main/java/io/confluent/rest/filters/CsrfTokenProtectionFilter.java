/*
 * Copyright 2014 Confluent Inc.
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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import io.confluent.rest.RestConfig;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.BadRequestException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple server-side request filter that implements CSRF protection as per the Guidelines for
 * Implementation of REST by NSA (section IV.F), section 4.3 of this
 * paper[http://seclab.stanford.edu/websec/csrf/csrf.pdf] and
 * https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#synchronizer-token-pattern.
 * If you add it to the request filters of your application, it will check for X-Requested-With &
 * X-Requested-By header in each request except for those that don't change state (GET, OPTIONS,
 * HEAD). If the header is not found, it returns Response.Status.BAD_REQUEST response back to the
 * client.
 */
public class CsrfTokenProtectionFilter implements Filter {

  private static final Logger log = LoggerFactory.getLogger(CsrfTokenProtectionFilter.class);

  public static class Headers {
    // CSRF token header from client
    public static final String REQUESTED_WITH = "X-Requested-With";

    // User session identifier header from client
    public static final String REQUESTED_BY = "X-Requested-By";

    // Csrf token header for the initial call.
    // CSRF_TOKEN is generated by the server and is subsequently passed by client in REQUESTED_WITH
    // header
    public static final String CSRF_TOKEN = "X-CONFLUENT-CSRF-TOKEN";
  }

  public static final String INVALID_TOKEN_MESSAGE =
      "Invalid CSRF token in request header X-Requested-With";
  public static final String MISSING_TOKEN_MESSAGE =
      "Missing CSRF token in request header X-Requested-With";
  public static final String MISSING_REQUESTER_MESSAGE =
      "Missing user session identifier in request header X-Requested-By";
  private static final Set<String> METHODS_TO_IGNORE;

  private String csrfTokenEndpoint = RestConfig.CSRF_PREVENTION_TOKEN_FETCH_ENDPOINT_DEFAULT;
  private int csrfTokenExpiration = RestConfig.CSRF_PREVENTION_TOKEN_EXPIRATION_MINUTES_DEFAULT;
  private int csrfTokenMaxEntries = RestConfig.CSRF_PREVENTION_TOKEN_MAX_ENTRIES_DEFAULT;
  private LoadingCache<String, String> tokenSupplier;

  static {
    HashSet<String> mti = new HashSet<>();
    mti.add("GET");
    mti.add("OPTIONS");
    mti.add("HEAD");
    METHODS_TO_IGNORE = Collections.unmodifiableSet(mti);
  }

  @Override
  public void init(FilterConfig filterConfig) throws ServletException {
    if (filterConfig.getInitParameter(RestConfig.CSRF_PREVENTION_TOKEN_FETCH_ENDPOINT) != null) {
      this.csrfTokenEndpoint =
          filterConfig.getInitParameter(RestConfig.CSRF_PREVENTION_TOKEN_FETCH_ENDPOINT);
    }

    if (filterConfig.getInitParameter(RestConfig.CSRF_PREVENTION_TOKEN_EXPIRATION_MINUTES)
        != null) {
      this.csrfTokenExpiration =
          Integer.parseInt(
              filterConfig.getInitParameter(RestConfig.CSRF_PREVENTION_TOKEN_EXPIRATION_MINUTES));
    }

    if (filterConfig.getInitParameter(RestConfig.CSRF_PREVENTION_TOKEN_MAX_ENTRIES) != null) {
      this.csrfTokenMaxEntries =
          Integer.parseInt(
              filterConfig.getInitParameter(RestConfig.CSRF_PREVENTION_TOKEN_MAX_ENTRIES));
    }

    this.tokenSupplier =
        CacheBuilder.newBuilder()
            .expireAfterWrite(csrfTokenExpiration, TimeUnit.MINUTES)
            .maximumSize(csrfTokenMaxEntries)
            .build(
                new CacheLoader<String, String>() {
                  @Override
                  public String load(String key) throws Exception {
                    return UUID.randomUUID().toString();
                  }
                });
  }

  @Override
  public void doFilter(
      ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
      throws IOException, ServletException {

    HttpServletRequest httpServletRequest = (HttpServletRequest) servletRequest;
    HttpServletResponse httpServletResponse = (HttpServletResponse) servletResponse;
    String requestedBy = httpServletRequest.getHeader(Headers.REQUESTED_BY);
    String requestedWith = httpServletRequest.getHeader(Headers.REQUESTED_WITH);

    // Always check for request's METHOD before checking for CSRF_ENDPOINT
    if (!METHODS_TO_IGNORE.contains(httpServletRequest.getMethod())) {
      // 'requestedBy' is required
      if (Strings.isNullOrEmpty(requestedBy)) {
        log.error("(Cross site request forgery): {}", MISSING_REQUESTER_MESSAGE);
        throw new BadRequestException(MISSING_REQUESTER_MESSAGE);
      }

      // requestedWith' is required
      if (Strings.isNullOrEmpty(requestedWith)) {
        log.error("(Cross site request forgery): {}", MISSING_TOKEN_MESSAGE);
        throw new BadRequestException(MISSING_TOKEN_MESSAGE);
      }

      // Check if the token in request header indeed belongs to requested user
      String cachedToken = tokenSupplier.getIfPresent(requestedBy);
      if (Strings.isNullOrEmpty(cachedToken) || !cachedToken.equals(requestedWith)) {
        log.error("(Cross site request forgery): {}", INVALID_TOKEN_MESSAGE);
        throw new BadRequestException(INVALID_TOKEN_MESSAGE);
      }
    }

    // Handle the request for CSRF token endpoint
    if (csrfTokenEndpoint.equals(httpServletRequest.getRequestURI())) {
      log.debug(
          "(Cross site request forgery): Handling request for CSRF token {}", csrfTokenEndpoint);
      if (!Strings.isNullOrEmpty(requestedBy)) {
        // Set the token in response header for future calls
        log.debug("(Cross site request forgery): Setting CSRF token on {}", csrfTokenEndpoint);
        httpServletResponse.setHeader(Headers.CSRF_TOKEN, tokenSupplier.getUnchecked(requestedBy));
      } else {
        log.error("(Cross site request forgery): {}", MISSING_REQUESTER_MESSAGE);
        throw new BadRequestException(MISSING_REQUESTER_MESSAGE);
      }

      return;
    }

    filterChain.doFilter(servletRequest, servletResponse);
  }

  @Override
  public void destroy() {

  }

  @VisibleForTesting
  String getCsrfTokenEndpoint() {
    return csrfTokenEndpoint;
  }

  @VisibleForTesting
  int getCsrfTokenExpiration() {
    return csrfTokenExpiration;
  }

  @VisibleForTesting
  int getCsrfTokenMaxEntries() {
    return csrfTokenMaxEntries;
  }
}
