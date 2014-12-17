package io.confluent.rest;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.server.ApplicationHandler;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.glassfish.jersey.test.spi.TestContainer;
import org.glassfish.jersey.test.spi.TestContainerException;

import java.net.URI;

import javax.ws.rs.core.UriBuilder;

// Newer versions of Jersey include a connector package for Jetty and support for using it in tests.
// However, those use Jetty 9, which requires Java 1.7. To get 1.6 support and still get to use the
// JerseyTest class and all it's convenient functionality, we include this minimal Jetty
// TestContainer implementation.
public class JettyTestContainer implements TestContainer {
  URI baseUri;
  ApplicationHandler appHandler;
  Server server;

  public JettyTestContainer(URI uri, ApplicationHandler applicationHandler) {
    baseUri = uri;
    appHandler = applicationHandler;
  }

  @Override
  public ClientConfig getClientConfig() {
    return null;
  }

  @Override
  public URI getBaseUri() {
    return baseUri;
  }

  @Override
  public void start() {
    try {
      ResourceConfig resourceConfig = new ResourceConfig(appHandler.getConfiguration());
      // Configure the servlet container
      ServletContainer servletContainer = new ServletContainer(resourceConfig);
      ServletHolder servletHolder = new ServletHolder(servletContainer);
      server = new Server(0);
      ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
      context.setContextPath("/");
      context.addServlet(servletHolder, "/*");
      server.setHandler(context);

      server.start();
      for(Connector connector : server.getConnectors()) {
        if (connector instanceof SelectChannelConnector) {
          baseUri = UriBuilder.fromUri(baseUri).port(connector.getLocalPort()).build();
          return;
        }
      }
      throw new TestContainerException("Started server, but couldn't find port");
    } catch (Exception e) {
      throw new TestContainerException(e);
    }
  }

  @Override
  public void stop() {
    try {
      server.stop();
    } catch (Exception e) {
      throw new TestContainerException(e);
    }
  }
}
