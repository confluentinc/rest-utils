package io.confluent.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import javax.ws.rs.core.Configurable;


public class IdleTimeoutConfigTest {

  @Test
  public void testIdleTimeoutConfigIsApplied() throws Exception {

    // given
    long expectedIdleTimeout = 1000;

    // when
    Map<String, Object> props = new HashMap<>();
    props.put(RestConfig.IDLE_TIMEOUT_MS_CONFIG, String.valueOf(expectedIdleTimeout));
    RestConfig config = new RestConfig(RestConfig.baseConfigDef(), props);

    Server server = new TestApp(config).createServer();
    server.start();
    // then
    assertEquals(expectedIdleTimeout, server.getConnectors()[0].getIdleTimeout());
    server.stop();
  }


  public static class TestApp extends io.confluent.rest.Application<RestConfig> {

    public TestApp(final RestConfig config) {
      super(config);
    }


    @Override
    public void setupResources(final Configurable<?> config, final RestConfig appConfig) {
      // nothing - not needed
    }
  }
}
