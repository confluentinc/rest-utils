package io.confluent.rest;

import org.glassfish.jersey.server.ApplicationHandler;
import org.glassfish.jersey.test.spi.TestContainer;
import org.glassfish.jersey.test.spi.TestContainerFactory;

import java.net.URI;

class JettyTestContainerFactory implements TestContainerFactory {
  @Override
  public TestContainer create(URI uri, ApplicationHandler applicationHandler)
      throws IllegalArgumentException {
    return new JettyTestContainer(uri, applicationHandler);
  }
}