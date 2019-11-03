/*
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
 */

package io.confluent.rest;

import org.apache.kafka.common.config.AbstractConfig;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.glassfish.jersey.server.ResourceConfig;

import javax.servlet.DispatcherType;
import javax.ws.rs.core.Configurable;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;


public abstract class ApplicationContext<T extends AbstractConfig> extends ServletContextHandler {
  // CHECKSTYLE_RULES.ON: ClassDataAbstractionCoupling
  T config;
  ResourceConfig ctxConfig;

  public ApplicationContext(T config) {
    this(config, "/");
  }

  public ApplicationContext(T config, String path) {
    super(ServletContextHandler.SESSIONS);
    super.setContextPath(path);
    ctxConfig = new ResourceConfig();
    this.config = config;
  }

  public T getConfiguration() {
    return this.config;
  }

  /**
   * Register resources or additional Providers, ExceptionMappers, and other JAX-RS components with
   * the Jersey application. This, combined with your Configuration class, is where you can
   * customize the behavior of the application.
   */
  public abstract void setupResources(Configurable<?> config, T appConfig);

  /**
   * Returns a list of static resources to serve using the default servlet.
   *
   * <p>For example, static files can be served from class loader resources by returning
   * {@code
   * new ResourceCollection(Resource.newClassPathResource("static"));
   * }
   *
   * <p>For those resources to get served, it is necessary to add a static resources property to the
   * config in @link{{@link #setupResources(Configurable, AbstractConfig)}},
   * e.g. using something like{@code
   * config.property(ServletProperties.FILTER_STATIC_CONTENT_REGEX, "/(static/.*|.*\\.html|)");
   * }
   *
   * @return static resource collection
   */
  protected ResourceCollection getStaticResources() {
    return null;
  }

  /*
   * add a security handler to the context to enable authentication
   */
  protected void configureSecurityHandler() {}

  /**
   * Returns a map of tag names to tag values to apply to metrics for this application.
   *
   * @return a Map of tags and values
   */
  public Map<String,String> getMetricsTags() {
    return new LinkedHashMap<String, String>();
  }

  /**
   * add any servlet filters that should be called before resource handling
   */
  protected void configurePreResourceHandling() {}

  /**
   * add any servlet filters that should be called after resource
   * handling but before falling back to the default servlet
   */
  protected void configurePostResourceHandling() {}

  public void enableCors() {
    if (config == null) {
      return;
    }

    String allowedOrigins = config.getString(RestConfig.ACCESS_CONTROL_ALLOW_ORIGIN_CONFIG);
    if (allowedOrigins.isEmpty()) {
      return;
    }

    FilterHolder filterHolder = new FilterHolder(CrossOriginFilter.class);
    filterHolder.setName("cross-origin");
    filterHolder.setInitParameter(
            CrossOriginFilter.ALLOWED_ORIGINS_PARAM, allowedOrigins
    );

    String allowedMethods = config.getString(RestConfig.ACCESS_CONTROL_ALLOW_METHODS);
    if (allowedMethods != null && !allowedMethods.trim().isEmpty()) {
      filterHolder.setInitParameter(CrossOriginFilter.ALLOWED_METHODS_PARAM, allowedMethods);
    }

    String allowedHeaders = config.getString(RestConfig.ACCESS_CONTROL_ALLOW_HEADERS);
    if (allowedHeaders != null && !allowedHeaders.trim().isEmpty()) {
      filterHolder.setInitParameter(CrossOriginFilter.ALLOWED_HEADERS_PARAM, allowedHeaders);
    }
    // handle preflight cors requests at the filter level, do not forward down the filter chain
    filterHolder.setInitParameter(CrossOriginFilter.CHAIN_PREFLIGHT_PARAM, "false");
    this.addFilter(filterHolder, "/*", EnumSet.of(DispatcherType.REQUEST));
  }
}
