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

package io.confluent.rest;

import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.jetty.JettyTestContainerFactory;
import org.junit.After;
import org.junit.Before;

import javax.ws.rs.client.Invocation;
import javax.ws.rs.core.Application;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Vector;

public abstract class
    EmbeddedServerTestHarness<C extends RestConfig, T extends io.confluent.rest.Application<C>> {
  private List<Object> resources = new Vector<Object>();
  private List<Class<?>> resourceClasses = new Vector<Class<?>>();

  protected C config;
  protected T app;
  private JerseyTest test;

  public EmbeddedServerTestHarness() throws RestConfigException {
    this.config = createConfiguration();
  }

  public EmbeddedServerTestHarness(C config) {
    this.config = config;
  }

  private C createConfiguration() throws RestConfigException {
    Class<C> configClass = Generics.getTypeParameter(getClass(), RestConfig.class);
    try {
      return configClass.getConstructor().newInstance();
    } catch (NoSuchMethodException e) {
      throw new RestConfigException(
          "Couldn't find default constructor for " + configClass.getName(), e
      );
    } catch (IllegalAccessException e) {
      throw new RestConfigException(
          "Error invoking default constructor " + configClass.getName(), e
      );
    } catch (InvocationTargetException e) {
      throw new RestConfigException(
          "Error invoking default constructor for " + configClass.getName(), e
      );
    } catch (InstantiationException e) {
      throw new RestConfigException(
          "Error invoking default constructor for " + configClass.getName(), e
      );
    }
  }

  /**
   * Creates and configures an application. This may be overridden by subclasses if they need
   * to customize the Application's Configuration or invoke a constructor other than
   * Application(Configuration c).
   */
  protected T createApplication() throws RestConfigException {
    Class<T> appClass = Generics.getTypeParameter(getClass(), io.confluent.rest.Application.class);

    try {
      return appClass.getConstructor(this.config.getClass()).newInstance(this.config);
    } catch (NoSuchMethodException e) {
      throw new RestConfigException(
          "Couldn't find default constructor for " + appClass.getName(), e
      );
    } catch (IllegalAccessException e) {
      throw new RestConfigException(
          "Error invoking default constructor " + appClass.getName(), e
      );
    } catch (InvocationTargetException e) {
      throw new RestConfigException(
          "Error invoking default constructor for " + appClass.getName(), e
      );
    } catch (InstantiationException e) {
      throw new RestConfigException(
          "Error invoking default constructor for " + appClass.getName(), e
      );
    }
  }

  @Before
  public void setUp() throws Exception {
    try {
      app = createApplication();
    } catch (RestConfigException ce) {
      throw new RuntimeException(
          "Unexpected configuration error when configuring EmbeddedServerTestHarnesss.", ce
      );
    }

    getJerseyTest().setUp();
  }

  @After
  public void tearDown() throws Exception {
    test.tearDown();
    test = null;
  }

  protected void addResource(Object resource) {
    resources.add(resource);
  }

  protected void addResource(Class<?> resource) {
    resourceClasses.add(resource);
  }

  protected T getApp() {
    return app;
  }

  protected JerseyTest getJerseyTest() {
    // This is instantiated on demand since we need subclasses to register the resources they need
    // passed along, but JerseyTest calls configure() from its constructor.
    if (test == null) {
      test = new JettyJerseyTest();
    }
    return test;
  }

  protected Invocation.Builder request(String target, String mediatype) {
    Invocation.Builder builder = getJerseyTest().target(target).request();
    if (mediatype != null) {
      builder.accept(mediatype);
    }
    return builder;
  }


  private class JettyJerseyTest extends JerseyTest {
    public JettyJerseyTest() {
      super(new JettyTestContainerFactory());
    }

    @Override
    protected Application configure() {
      ResourceConfig config = new ResourceConfig();
      // Only configure the base application, resources are added manually with addResource
      app.configureBaseApplication(config, null);
      for (Object resource : resources) {
        config.register(resource);
      }
      for (Class<?> resource : resourceClasses) {
        config.register(resource);
      }
      return config;
    }

    @Override
    protected void configureClient(ClientConfig config) {
      app.configureBaseApplication(config, null);
    }
  }
}
