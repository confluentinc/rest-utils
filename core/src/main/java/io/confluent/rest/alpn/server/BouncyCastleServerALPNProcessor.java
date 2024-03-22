/*
 * Copyright 2024 Confluent Inc.
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

package io.confluent.rest.alpn.server;

import com.google.auto.service.AutoService;
import java.util.List;
import java.util.function.BiFunction;
import javax.net.ssl.SSLEngine;
import org.eclipse.jetty.alpn.server.ALPNServerConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.ssl.ALPNProcessor;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.io.ssl.SslHandshakeListener;
import org.eclipse.jetty.util.JavaVersion;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * This class implements Server ALPN processor and is available as a service so that Jetty http/2
 * can work with BouncyCastle's JSSE provider FIPS driver. Support for ALPN in BouncyCastle's JSSE
 * provider is available since bc-fips-1.0.2, but there isn't an implementation of
 * ALPNProcessor.Server available in Jetty server. The implementation of this class is similar to
 * JDK9ServerALPNProcessor in Jetty server to make a service that implements ALPNProcessor.Server
 * and is compatible with BouncyCastle's JSSE provider FIPS driver.
 * Note that we don't extend JDK9ServerALPNProcessor because it changes the runtime dependency
 * jetty-alpn-java-server and could break downstream projects.
 */
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@AutoService(ALPNProcessor.Server.class)
public class BouncyCastleServerALPNProcessor implements ALPNProcessor.Server, SslHandshakeListener {

  private static final Logger LOG = Log.getLogger(BouncyCastleServerALPNProcessor.class);

  @Override
  public void init() {
    if (JavaVersion.VERSION.getPlatform() < 9) {
      throw new IllegalStateException(this + " not applicable for java " + JavaVersion.VERSION);
    }
  }

  @Override
  public boolean appliesTo(SSLEngine sslEngine) {
    return sslEngine.getClass().getName().startsWith("org.bouncycastle.jsse.provider");
  }

  @Override
  public void configure(SSLEngine sslEngine, Connection connection) {
    sslEngine.setHandshakeApplicationProtocolSelector(
        new ALPNCallback((ALPNServerConnection) connection));
  }

  private static final class ALPNCallback implements BiFunction<SSLEngine, List<String>, String>,
      SslHandshakeListener {

    private final ALPNServerConnection alpnConnection;

    private ALPNCallback(ALPNServerConnection connection) {
      alpnConnection = connection;
      ((SslConnection.DecryptedEndPoint) alpnConnection.getEndPoint()).getSslConnection()
          .addHandshakeListener(this);
    }

    @Override
    public String apply(SSLEngine engine, List<String> protocols) {
      try {
        if (LOG.isDebugEnabled()) {
          LOG.debug("apply {} {}", alpnConnection, protocols);
        }
        alpnConnection.select(protocols);
        return alpnConnection.getProtocol();
      } catch (Throwable x) {
        // Cannot negotiate the protocol, return null to have
        // JSSE send Alert.NO_APPLICATION_PROTOCOL to the client.
        return null;
      }
    }

    @Override
    public void handshakeSucceeded(Event event) {
      String protocol = alpnConnection.getProtocol();
      if (LOG.isDebugEnabled()) {
        LOG.debug("TLS handshake succeeded, protocol={} for {}", protocol, alpnConnection);
      }
      if (protocol == null) {
        alpnConnection.unsupported();
      }
    }

    @Override
    public void handshakeFailed(Event event, Throwable failure) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("TLS handshake failed " + alpnConnection, failure);
      }
    }
  }
}
