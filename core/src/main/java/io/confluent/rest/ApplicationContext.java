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

import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;

import javax.servlet.DispatcherType;
import javax.ws.rs.core.Configurable;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * Application Servlet Context.
 * This extension to the ServletContextHandler allows for encapsulation of a REST application
 * into an ServletContexts with it's own session, security handlers, object mappers,
 * exception handler, et. This context must be registered with a greater {@link Application}
 * where it and other servlet contexts will be served by a shared http server.
 * <pre>
 *   new ApplicationContext(config, "/myApp");
 * </pre>
 */
public abstract class ApplicationContext<T extends RestConfig> extends ServletContextHandler {
  // CHECKSTYLE_RULES.ON: ClassDataAbstractionCoupling
  private final T config;

  private Map<String, String> metricsTags;
  private final ResourceConfig contextConfig = new ResourceConfig();
  private static final AtomicInteger instance = new AtomicInteger();


  public ApplicationContext(T config) {
    this(config, "/");
  }

  public ApplicationContext(T config, String path) {
    super(ServletContextHandler.SESSIONS);
    super.setContextPath(path);
    this.config = Objects.requireNonNull(config);

    this.metricsTags = Application.parseListToMap(
            getConfiguration().getList(RestConfig.METRICS_TAGS_CONFIG)
    );
    this.metricsTags.put(path.replace("/", "-"), String.valueOf(instance.incrementAndGet()));
  }

  public T getConfiguration() {
    return this.config;
  }

  public ResourceConfig getResourceConfig() {
    return this.contextConfig;
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
   * <pre>{@code
   * new ResourceCollection(Resource.newClassPathResource("static"));
   * }</pre>
   *
   * <p>For those resources to get served, it is necessary to add a static resources property to the
   * config in @link{{@link #setupResources(Configurable, RestConfig)}},
   * e.g. using something like <pre>{@code
   * config.property(ServletProperties.FILTER_STATIC_CONTENT_REGEX, "/(static/.*|.*\\.html|)");
   * }</pre>
   *
   * @return static resource collection
   */
  protected ResourceCollection getStaticResources() {
    return null;
  }

  /*
   * Add a security handler to the context to enable authentication
   */
  protected void configureSecurityHandler() {
    Application.configureSecurityHandler(this, this.config);
  }

  /**
   * Returns a map of tag names to tag values to apply to metrics for this application.
   *
   * @return a Map of tags and values
   */
  public Map<String,String> getMetricsTags() {
    return metricsTags;
  }

  public void registerServerMetricTags(Map<String, String> tags) {
    getMetricsTags().forEach((key, value) -> {
      if (tags.put(key, value) != null) {
        throw new IllegalArgumentException("A metric named %s=%s already exists, "
                + "can't register another one.".format(key, value));
      }
    });
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

  ServletContextHandler configure(FilterHolder corsFilter) {
    setBaseResource(getStaticResources());

    setupResources(getResourceConfig(), getConfiguration());
    configureSecurityHandler();
    if (corsFilter != null) {
      super.addFilter(corsFilter, "/*", EnumSet.of(DispatcherType.REQUEST));
    }

    configurePreResourceHandling();
    ServletContainer servletContainer = new ServletContainer(getResourceConfig());
    FilterHolder servletHolder = new FilterHolder(servletContainer);
    super.addFilter(servletHolder, "/*", null);

    configurePostResourceHandling();
    ServletHolder defaultHolder = new ServletHolder("default", DefaultServlet.class);
    defaultHolder.setInitParameter("dirAllowed", "false");
    addServlet(defaultHolder, "/*");

    return this;
  }
}
