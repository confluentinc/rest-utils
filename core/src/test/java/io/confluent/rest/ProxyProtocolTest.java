/*
 * Copyright 2021 Confluent Inc.
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

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.SSLContexts;
import org.apache.kafka.common.config.types.Password;
import org.apache.kafka.test.TestSslUtils;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.ProxyConnectionFactory;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.MatcherAssert.assertThat;

public class ProxyProtocolTest {
  private static TestRestConfig testConfig;

  private Properties props;
  private ApplicationServer<TestRestConfig> server;
  private File clientKeystore;

  public static final String SSL_PASSWORD = "test1234";

  @BeforeEach
  public void setup() throws Exception {
    props = new Properties();
    props.setProperty(RestConfig.PROXY_PROTOCOL_ENABLED_CONFIG, "true");
  }

  private void createKeystoreWithCert(File file, String alias, Map<String, X509Certificate> certs) throws Exception {
    KeyPair keypair = TestSslUtils.generateKeyPair("RSA");
    TestSslUtils.CertificateBuilder certificateBuilder = new TestSslUtils.CertificateBuilder(30, "SHA1withRSA");
    X509Certificate cCert = certificateBuilder.sanDnsNames("localhost")
        .generate("CN=mymachine.local, O=A client", keypair);
    TestSslUtils.createKeyStore(file.getPath(), new Password(SSL_PASSWORD), new Password(SSL_PASSWORD), alias, keypair.getPrivate(), cCert);
    certs.put(alias, cCert);
  }

  private void configServerKeystore(Properties props, File serverKeystore) {
    props.put(RestConfig.SSL_KEYSTORE_LOCATION_CONFIG, serverKeystore.getAbsolutePath());
    props.put(RestConfig.SSL_KEYSTORE_PASSWORD_CONFIG, SSL_PASSWORD);
    props.put(RestConfig.SSL_KEY_PASSWORD_CONFIG, SSL_PASSWORD);
  }

  private void configServerTruststore(Properties props, File trustStore) {
    props.put(RestConfig.SSL_TRUSTSTORE_LOCATION_CONFIG, trustStore.getAbsolutePath());
    props.put(RestConfig.SSL_TRUSTSTORE_PASSWORD_CONFIG, SSL_PASSWORD);
  }

  @AfterEach
  public void tearDown() throws Exception {
    server.stop();
  }

  @Test
  public void testConnectionFactoriesHttp() throws Exception {
    testConnectionFactories("http", false, false);
  }

  @Test
  public void testConnectionFactoriesHttpWithHttp2() throws Exception {
    testConnectionFactories("http", true, false);
  }

  @Test
  public void testConnectionFactoriesHttps() throws Exception {
    testConnectionFactories("https", false, false);
  }

  @Test
  public void testConnectionFactoriesHttpsWithHttp2() throws Exception {
    testConnectionFactories("https", true, false);
  }


  @Test
  public void testConnectionFactoriesHttpWithPpv2() throws Exception {
    testConnectionFactories("http", false, true);
  }

  @Test
  public void testConnectionFactoriesHttpWithHttp2WithPpv2() throws Exception {
    testConnectionFactories("http", true, true);
  }

  @Test
  public void testConnectionFactoriesHttpsWithPpv2() throws Exception {
    testConnectionFactories("https", false, true);
  }

  @Test
  public void testConnectionFactoriesHttpsWithHttp2WithPpv2() throws Exception {
    testConnectionFactories("https", true, true);
  }


  private void testConnectionFactories(String scheme, boolean http2Enabled, boolean isPpv2) throws Exception {
    String url = scheme + "://localhost:9000";
    props.setProperty(RestConfig.LISTENERS_CONFIG, url);
    props.setProperty(RestConfig.HTTP2_ENABLED_CONFIG, Boolean.toString(http2Enabled));

    if (scheme.equals("https")) {
      File trustStore;
      File serverKeystore;

      try {
        trustStore = File.createTempFile("SslTest-truststore", ".jks");
        clientKeystore = File.createTempFile("SslTest-client-keystore", ".jks");
        serverKeystore = File.createTempFile("SslTest-server-keystore", ".jks");
      } catch (IOException ioe) {
        throw new RuntimeException("Unable to create temporary files for trust stores and keystores.");
      }
      Map<String, X509Certificate> certs = new HashMap<>();
      createKeystoreWithCert(clientKeystore, "client", certs);
      createKeystoreWithCert(serverKeystore, "server", certs);
      TestSslUtils.createTrustStore(trustStore.getAbsolutePath(), new Password(SSL_PASSWORD), certs);

      configServerKeystore(props, serverKeystore);
      configServerTruststore(props, trustStore);
    }

    testConfig = new TestRestConfig(props);

    server = new ApplicationServer<>(testConfig);
    ProxyTestApp app = new ProxyTestApp("/app");
    server.registerApplication(app);
    server.start();

    boolean proxyConnectionFactoryFound = false;
    for (ConnectionFactory factory : server.getConnectors()[0].getConnectionFactories()) {
      if (factory instanceof ProxyConnectionFactory) {
        proxyConnectionFactoryFound = true;
        break;
      }
    }

    assertThat("ProxyConnectionFactory was not found in server's connection factories",
        proxyConnectionFactoryFound);
    makeProxyProtocolGetRequest("/app/resource", isPpv2, scheme.equals("https"));
    assertThat(makeGetRequest(url + "/app/resource"), is(HttpStatus.Code.OK.getCode()));
  }

  // returns the http response status code.
  private int makeGetRequest(String url) throws Exception {
    HttpGet httpget = new HttpGet(url);
    CloseableHttpClient httpclient;
    if (url.startsWith("http://")) {
      httpclient = HttpClients.createDefault();
    } else {
      // trust all self-signed certs.
      SSLContextBuilder sslContextBuilder = SSLContexts.custom()
          .loadTrustMaterial(new TrustSelfSignedStrategy());

      // add the client keystore if it's configured.
      sslContextBuilder.loadKeyMaterial(new File(clientKeystore.getAbsolutePath()),
          SSL_PASSWORD.toCharArray(),
          SSL_PASSWORD.toCharArray());
      SSLContext sslContext = sslContextBuilder.build();

      SSLConnectionSocketFactory sslSf = new SSLConnectionSocketFactory(sslContext, new String[]{"TLSv1.2"},
          null, SSLConnectionSocketFactory.getDefaultHostnameVerifier());

      httpclient = HttpClients.custom()
          .setSSLSocketFactory(sslSf)
          .build();
    }

    int statusCode;
    CloseableHttpResponse response = null;
    try {
      response = httpclient.execute(httpget);
      statusCode = response.getStatusLine().getStatusCode();
    } finally {
      if (response != null) {
        response.close();
      }
      httpclient.close();
    }
    return statusCode;
  }

  public static byte[] fromHexString(String s)
  {
    if (s.length() % 2 != 0)
      throw new IllegalArgumentException(s);
    byte[] array = new byte[s.length() / 2];
    for (int i = 0; i < array.length; i++)
    {
      int b = Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
      array[i] = (byte)(0xff & b);
    }
    return array;
  }


  private byte[] getProxyProtocolHeader(boolean isPpv2, String remoteAddr, String remotePort) {
    if (!isPpv2) {
      String header = "PROXY TCP4 " + remoteAddr + " 127.0.0.0 " + remotePort + " 8080\r\n";
      return header.getBytes();
    }
    String proxy =
      // Preamble
      "0D0A0D0A000D0A515549540A" +
      // V2, PROXY
      "21" +
      // 0x1 : AF_INET    0x1 : STREAM.  Address length is 2*4 + 2*2 = 12 bytes.
      "11" +
      // length of remaining header (4+4+2+2+3+6+5+6 = 32)
      "0020" +
      // uint32_t src_addr; uint32_t dst_addr; uint16_t src_port; uint16_t dst_port;
      // remoteAddr = 192.168.0.1 remotePort = 12345
      "C0A80001" +
      "7f000001" +
      "3039" +
      "1F90" +
      // NOOP value 0
      "040000" +
      // NOOP value ABCDEF
      "040003ABCDEF" +
      // Custom 0xEO {0x01,0x02}
      "E000020102" +
      // Custom 0xE1 {0xFF,0xFF,0xFF}
      "E10003FFFFFF";
    return fromHexString(proxy);
  }
    // returns the http response status code.
  private void makeProxyProtocolGetRequest(String path, boolean isPpv2) throws Exception {
    final String remoteAddr = "192.168.0.1";
    final String remotePort = "12345";

    ServerConnector connector = (ServerConnector) server.getConnectors()[0];

    try (Socket socket = new Socket("localhost", connector.getLocalPort())) {

      byte[] header = getProxyProtocolHeader(isPpv2, remoteAddr, remotePort);
      String httpString = "HTTP/1.1";
      String request1 =
              String.format("GET %s %s\r\n" +
                      "Host: localhost\r\n" +
                      "Connection: close\r\n" +
                      "\r\n", path, httpString);
      OutputStream output = socket.getOutputStream();
      output.write(header);
      output.write(request1.getBytes(StandardCharsets.UTF_8));
      output.flush();

      InputStream input = socket.getInputStream();
      BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
      String response1 = reader.readLine();
      assertThat(response1, startsWith(httpString + " 200 "));
      String lastLine = response1;
      while (true) {
        String l = reader.readLine();
        if (l == null)
          break;
        lastLine = l;
      }
      String[] tokens = lastLine.split(":", 2);
      assertThat(tokens.length, is(2));
      assertThat(tokens[0], is(remoteAddr));
      assertThat(tokens[1], is(remotePort));
    }
  }

  private void makeProxyProtocolGetRequest(String path, boolean isPpv2, boolean sslEnabled) throws Exception {
    final String remoteAddr = "192.168.0.1";
    final String remotePort = "12345";

    ServerConnector connector = (ServerConnector) server.getConnectors()[0];

    // write the proxy protocol headers
    try (Socket proxySocket = new Socket("localhost", connector.getLocalPort())) {
      byte[] header = getProxyProtocolHeader(isPpv2, remoteAddr, remotePort);
      OutputStream proxyWriter = proxySocket.getOutputStream();
      proxyWriter.write(header);
      proxyWriter.flush();

      Socket requestSocket = proxySocket;

      // if ssl enabled, the rest of the message needs to be done in ssl and sent over the sslSocket
      if (sslEnabled) {
        // trust all self-signed certs.
        SSLContextBuilder sslContextBuilder = SSLContexts.custom()
                .loadTrustMaterial(new TrustSelfSignedStrategy())
                .setProtocol("TLSv1.2");

        // add the client keystore if it's configured.
        sslContextBuilder.loadKeyMaterial(new File(clientKeystore.getAbsolutePath()),
                SSL_PASSWORD.toCharArray(),
                SSL_PASSWORD.toCharArray());
        SSLContext sslContext = sslContextBuilder.build();

        SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

        requestSocket =sslSocketFactory.createSocket(proxySocket, "localhost", connector.getLocalPort(), true);
      }

      String httpString = "HTTP/1.1";
      String request1 =
              String.format("GET %s %s\r\n" +
                      "Host: localhost\r\n" +
                      "Connection: close\r\n" +
                      "\r\n", path, httpString);
      OutputStream output = requestSocket.getOutputStream();
      output.write(request1.getBytes(StandardCharsets.UTF_8));
      output.flush();

      InputStream input = requestSocket.getInputStream();
      BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
      String response1 = reader.readLine();
      assertThat(response1, startsWith(httpString + " 200 "));
      String lastLine = response1;
      while (true) {
        String l = reader.readLine();
        if (l == null)
          break;
        lastLine = l;
      }
      String[] tokens = lastLine.split(":", 2);
      assertThat(tokens.length, is(2));
      assertThat(tokens[0], is(remoteAddr));
      assertThat(tokens[1], is(remotePort));
    }
  }


    private static class ProxyTestApp extends Application<TestRestConfig> implements AutoCloseable {
      private static final AtomicBoolean SHUTDOWN_CALLED = new AtomicBoolean(true);

    ProxyTestApp(String path) {
      this(testConfig, path);
    }

    ProxyTestApp(TestRestConfig config, String path) {
      super(config, path);
    }

    @Override
    public void setupResources(final Configurable<?> config, final TestRestConfig appConfig) {
      config.register(RestResource.class);
    }

    @Override
    public void close() throws Exception {
      stop();
    }

    @Override
    public void onShutdown() {
      SHUTDOWN_CALLED.set(true);
    }
  }

  @Path("/")
  @Produces(MediaType.TEXT_PLAIN)
  public static class RestResource {
    @Context
    HttpServletRequest httpServletRequest;

    @GET
    @Path("/resource")
    public String get(@Context HttpServletRequest request) {
      return request.getRemoteAddr() + ":" + request.getRemotePort()  + "\r\n";
    }
  }
}
