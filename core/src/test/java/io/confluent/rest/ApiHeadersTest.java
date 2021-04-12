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
 **/

package io.confluent.rest;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import java.io.File;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import javax.net.ssl.SSLContext;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.MediaType;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContexts;
import org.apache.kafka.common.config.types.Password;
import org.apache.kafka.test.TestSslUtils;
import org.apache.kafka.test.TestSslUtils.CertificateBuilder;
import org.eclipse.jetty.server.Server;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class ApiHeadersTest {

  private static final String SSL_PASSWORD = "test1234";

  private static String clientKeystoreLocation;
  private static Server server;

  @BeforeClass
  public static void setUp() throws Exception {
    final File trustStore = File.createTempFile("ApiHeadersTest-truststore", ".jks");
    final File clientKeystore = File.createTempFile("ApiHeadersTest-client-keystore", ".jks");
    final File serverKeystore = File.createTempFile("ApiHeadersTest-server-keystore", ".jks");

    clientKeystoreLocation = clientKeystore.getAbsolutePath();

    final Map<String, X509Certificate> certs = new HashMap<>();
    createKeystoreWithCert(clientKeystore, "client", certs);
    createKeystoreWithCert(serverKeystore, "server", certs);
    TestSslUtils.createTrustStore(trustStore.getAbsolutePath(), new Password(SSL_PASSWORD), certs);

    final Properties props = new Properties();
    props.put(RestConfig.LISTENERS_CONFIG, "http://localhost:0,https://localhost:0");
    props.put(RestConfig.SSL_KEYSTORE_LOCATION_CONFIG, serverKeystore.getAbsolutePath());
    props.put(RestConfig.SSL_KEYSTORE_PASSWORD_CONFIG, SSL_PASSWORD);
    props.put(RestConfig.SSL_KEY_PASSWORD_CONFIG, SSL_PASSWORD);

    TestApplication app = new TestApplication(new TestRestConfig(props));
    server = app.createServer();
    server.start();
  }

  @AfterClass
  public static void teardown() throws Exception {
    if (server != null) {
      server.stop();
    }
  }

  @Test
  public void testHttpDoesNotReturnJettyServerVersionHeader() throws Exception {

    final HttpGet httpget = new HttpGet(server.getURI() + "/test/endpoint");

    try ( CloseableHttpClient httpclient = HttpClients.createDefault();
        CloseableHttpResponse response = httpclient.execute(httpget) ) {

      assertThat(response.getStatusLine().getStatusCode(), is(200));
      assertThat(response.getFirstHeader( "Server" ), is(nullValue()));
    }
  }

  @Test
  public void testHttpsDoesNotReturnJettyServerVersionHeader() throws Exception {

    final HttpGet httpget = new HttpGet(server.getURI() + "/test/endpoint");

    // trust all self-signed certs and add the client keystore if it's configured.
    final SSLContext sslContext = SSLContexts.custom()
        .loadTrustMaterial(new TrustSelfSignedStrategy())
        .loadKeyMaterial(new File(clientKeystoreLocation), SSL_PASSWORD.toCharArray(),
            SSL_PASSWORD.toCharArray())
        .build();

    final SSLConnectionSocketFactory sslSf =
        new SSLConnectionSocketFactory(
            sslContext,
            new String[]{"TLSv1.2"},
            null,
            SSLConnectionSocketFactory.getDefaultHostnameVerifier());

    try (
        CloseableHttpClient httpclient = HttpClients.custom().setSSLSocketFactory(sslSf).build();
        CloseableHttpResponse response = httpclient.execute(httpget)) {

      assertThat(response.getStatusLine().getStatusCode(), is(200));
      assertThat(response.getFirstHeader("Server"), is(nullValue()));
    }
  }


  private static void createKeystoreWithCert(
      File file, String alias, Map<String, X509Certificate> certs) throws Exception {

    final KeyPair keypair = TestSslUtils.generateKeyPair("RSA");

    final X509Certificate cert = new CertificateBuilder(30, "SHA1withRSA")
        .sanDnsName("localhost").generate("CN=mymachine.local, O=A client", keypair);

    TestSslUtils.createKeyStore(file.getPath(), new Password(SSL_PASSWORD), alias,
        keypair.getPrivate(), cert);
    certs.put(alias, cert);
  }

  private static class TestApplication extends Application<TestRestConfig> {
    public TestApplication(TestRestConfig props) {
      super(props);
    }

    @Override
    public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
      config.register(new TestResource());
    }
  }

  @Path("/test/")
  @Produces(MediaType.APPLICATION_JSON)
  public static class TestResource {

    @GET
    @Path("/endpoint")
    public boolean test() {
      return true;
    }
  }
}
