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

import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.glassfish.jersey.server.ResourceConfig;

import javax.ws.rs.core.Configurable;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;


public abstract class ApplicationContext<T extends RestConfig> extends ServletContextHandler {
  // CHECKSTYLE_RULES.ON: ClassDataAbstractionCoupling
  protected T config;
  ResourceConfig contextConfig;

  private Map<String, String> metricsTags;
  private static final AtomicInteger instance = new AtomicInteger();


  public ApplicationContext(T config) {
    this(config, "/");
  }

  public ApplicationContext(T config, String path) {
    super(ServletContextHandler.SESSIONS);
    super.setContextPath(path);
    contextConfig = new ResourceConfig();
    this.config = config;

    this.metricsTags = Application.parseListToMap(
            getConfiguration().getList(RestConfig.METRICS_TAGS_CONFIG)
    );
    this.metricsTags.put(path.replace("/", "-"), String.valueOf(instance.incrementAndGet()));
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
   * config in @link{{@link #setupResources(Configurable, RestConfig)}},
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

  /**
   * add any servlet filters that should be called before resource handling
   */
  protected void configurePreResourceHandling() {}

  /**
   * add any servlet filters that should be called after resource
   * handling but before falling back to the default servlet
   */
  protected void configurePostResourceHandling() {}

}
