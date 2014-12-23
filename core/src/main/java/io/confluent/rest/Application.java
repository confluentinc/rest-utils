/**
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
package io.confluent.rest;

import com.fasterxml.jackson.jaxrs.base.JsonParseExceptionMapper;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.ServerProperties;
import org.glassfish.jersey.server.validation.ValidationFeature;
import org.glassfish.jersey.servlet.ServletContainer;

import javax.ws.rs.core.Configurable;

import io.confluent.rest.exceptions.ConstraintViolationExceptionMapper;
import io.confluent.rest.exceptions.GenericExceptionMapper;
import io.confluent.rest.exceptions.WebApplicationExceptionMapper;
import io.confluent.rest.validation.JacksonMessageBodyProvider;

/**
 * A REST application. Extend this class and implement the configure() method to generate your
 * application-specific configuration class and setupResources() to register REST resources with the
 * JAX-RS server. Use createServer() to get a fully-configured, ready to run Jetty server.
 */
public abstract class Application<T extends RestConfig> {
  protected T config;

  public Application() {}

  public Application(T config) {
    this.config = config;
  }

  /**
   * Parse, load, or generate the Configuration for this application.
   */
  public T configure() throws RestConfigException {
    // Allow this implementation as a nop if they provide
    if (this.config == null)
      throw new RestConfigException("Application.configure() was not overridden for " + getClass().getName() +
                                       " but the configuration was not passed to the Application class's constructor.");
    return this.config;
  }

  /**
   * Register resources or additional Providers, ExceptionMappers, and other JAX-RS components with
   * the Jersey application. This, combined with your Configuration class, is where you can customize
   * the behavior of the application.
   */
  public abstract void setupResources(Configurable<?> config, T appConfig);

  /**
   * Configure and create the server.
   */
  public Server createServer() throws RestConfigException {
    if (config == null) {
      configure();
    }

    // The configuration for the JAX-RS REST service
    ResourceConfig resourceConfig = new ResourceConfig();

    configureBaseApplication(resourceConfig);
    setupResources(resourceConfig, getConfiguration());

    // Configure the servlet container
    ServletContainer servletContainer = new ServletContainer(resourceConfig);
    ServletHolder servletHolder = new ServletHolder(servletContainer);
    Server server = new Server(getConfiguration().getInt(RestConfig.PORT_CONFIG));
    ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
    context.setContextPath("/");
    context.addServlet(servletHolder, "/*");
    server.setHandler(context);
    return server;
  }

  /**
   * Register standard components for a JSON REST application on the given JAX-RS configurable,
   * which can be either an ResourceConfig for a server or a ClientConfig for a Jersey-based REST
   * client.
   */
  public void configureBaseApplication(Configurable<?> config) {
    RestConfig restRestConfig = getConfiguration();

    config.register(JacksonMessageBodyProvider.class);
    config.register(JsonParseExceptionMapper.class);

    config.register(ValidationFeature.class);
    config.register(ConstraintViolationExceptionMapper.class);
    config.register(new WebApplicationExceptionMapper(restRestConfig));
    config.register(new GenericExceptionMapper(restRestConfig));

    config.property(ServerProperties.BV_SEND_ERROR_IN_RESPONSE, true);
  }

  public T getConfiguration() {
    return this.config;
  }
}

