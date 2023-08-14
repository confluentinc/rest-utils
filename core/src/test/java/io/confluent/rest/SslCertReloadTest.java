/*
 * Copyright 2023 Confluent Inc.
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import javax.net.ssl.SSLContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.Context;

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
import org.apache.kafka.test.TestSslUtils.CertificateBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;

// This test tries to mimic k8s secrets used for the SSL certification auto reload.
// They are updated using symbolic links to directories and look like this.
//
// ROOT
//   +----- truststore.jks      ---> ..data/truststore.jks         (symlink)
//   +----- client-keystore.jks ---> ..data/client-keystore.jks    (symlink)
//   +----- server-keystore.jks ---> ..data/server-keystore.jks    (symlink)
//   +----- ..data/             ---> active_dir                    (symlink)
//   +----- active_dir
//            +----- truststore.jks                           (regular file)
//            +----- client-keystore.jks                      (regular file)
//            +----- server-keystore.jks                      (regular file)
//
// In the diagram above, a complete set of truststore and keystores is contained
// in a directory called "active_dir". For each of these, there is a symlink of the
// same name from the root directory targetting a path in another directory called
// "..data". In turn, "..data" is then symlinked to "active_dir".
//
// For example, if the ssl.keystore.location points to "ROOT/server-keystore.jks",
// that actually symlinks to "ROOT/..data/server-keystore.jks", and that in turn
// symlinks to "ROOT/active_dir/server-keystore.jks".
//
// When the certificates are rolled, a new directory is created as a peer of
// "active_dir" with a new set of truststore and keystores. Then the symlink from
// "..data" is replaced with one that targets the new directory. In this way,
// the files have been superseded without needing to change the configuration.
//
// By configuring ssl.keystore.watch.location to point to the "..data" directory,
// the modification to the symlink is noticed and Jetty can reload using the new
// keystore.
public class SslCertReloadTest {

  private static final Logger log = LoggerFactory.getLogger(SslCertReloadTest.class);

  private File watchDir;
  private java.nio.file.Path dataDir;
  private File trustStore;
  private File clientKeystore;
  private File serverKeystore;
  private File serverKeystoreErr;
  private java.nio.file.Path serverKeystorePath;

  public static final String SSL_PASSWORD = "test1234";
  public static final String EXPECTED_200_MSG = "Response status must be 200.";
  public static final int CERT_RELOAD_WAIT_TIME = 30000;

  @BeforeEach
  public void setUp() throws Exception {
    try {
      // Make a temporary directory for all the files
      watchDir = Files.createTempDirectory("SslCertReloadTest").toFile();
      watchDir.deleteOnExit();

      // Then create a directory called "..data" which will symlink to the actual directory containing the files
      dataDir = Paths.get(watchDir.getAbsolutePath(), "..data");

      // Create a directory for the old files, which are the original ones at the start of the test
      java.nio.file.Path oldDir = Files.createDirectory(Paths.get(watchDir.getAbsolutePath(), "old"));
      trustStore = Files.createFile(oldDir.resolve("truststore.jks")).toFile();
      clientKeystore = Files.createFile(oldDir.resolve("client-keystore.jks")).toFile();
      serverKeystore = Files.createFile(oldDir.resolve("server-keystore.jks")).toFile();

      Map<String, X509Certificate> certs = new HashMap<>();
      createKeystoreWithCert(clientKeystore, "client", certs);
      createKeystoreWithCert(serverKeystore, "server", certs);
      TestSslUtils.createTrustStore(trustStore.getAbsolutePath(), new Password(SSL_PASSWORD), certs);

      // Create a directory for the bad files, which contain a bad keystore
      java.nio.file.Path errDir = Files.createDirectory(Paths.get(watchDir.getAbsolutePath(), "err"));
      Files.copy(oldDir.resolve("truststore.jks"), errDir.resolve("truststore.jks"));
      Files.copy(oldDir.resolve("client-keystore.jks"), errDir.resolve("client-keystore.jks"));
      serverKeystoreErr = Files.createFile(errDir.resolve("server-keystore.jks")).toFile();

      certs = new HashMap<>();
      createWrongKeystoreWithCert(serverKeystoreErr, "server", certs);

      // Create a directory for the new files, which are the original ones restored in a different place
      java.nio.file.Path newDir = Files.createDirectory(Paths.get(watchDir.getAbsolutePath(), "new"));
      Files.copy(oldDir.resolve("truststore.jks"), newDir.resolve("truststore.jks"));
      Files.copy(oldDir.resolve("client-keystore.jks"), newDir.resolve("client-keystore.jks"));
      Files.copy(oldDir.resolve("server-keystore.jks"), newDir.resolve("server-keystore.jks"));

      // Create symlinks in the watch directory for each of the files targeting the "..data" directory
      Files.createSymbolicLink(Paths.get(watchDir.getAbsolutePath(), "truststore.jks"),
          Paths.get("..data", "truststore.jks"));
      Files.createSymbolicLink(Paths.get(watchDir.getAbsolutePath(), "client-keystore.jks"),
          Paths.get("..data", "client-keystore.jks"));
      serverKeystorePath = Files.createSymbolicLink(Paths.get(watchDir.getAbsolutePath(), "server-keystore.jks"),
          Paths.get("..data", "server-keystore.jks"));

      // And finally create a symlink from "..data" to "old"
      Files.createSymbolicLink(dataDir, Paths.get("old"));
    } catch (IOException ioe) {
      throw new RuntimeException(
        "Unable to create temporary files for truststores and keystores.", ioe);
    }
  }

  private void createKeystoreWithCert(File file, String alias, Map<String, X509Certificate> certs)
      throws Exception {
    KeyPair keypair = TestSslUtils.generateKeyPair("RSA");
    CertificateBuilder certificateBuilder = new CertificateBuilder(30, "SHA1withRSA");
    X509Certificate cCert = certificateBuilder.sanDnsNames("localhost")
        .generate("CN=mymachine.local, O=A client", keypair);
    TestSslUtils.createKeyStore(file.getPath(), new Password(SSL_PASSWORD),
        new Password(SSL_PASSWORD), alias, keypair.getPrivate(), cCert);
    certs.put(alias, cCert);
  }

  private void createWrongKeystoreWithCert(File file, String alias,
      Map<String, X509Certificate> certs) throws Exception {
    KeyPair keypair = TestSslUtils.generateKeyPair("RSA");
    CertificateBuilder certificateBuilder = new CertificateBuilder(30, "SHA1withRSA");
    X509Certificate cCert = certificateBuilder.sanDnsNames("fail")
        .generate("CN=mymachine.local, O=A client", keypair);
    TestSslUtils.createKeyStore(file.getPath(), new Password(SSL_PASSWORD),
        new Password(SSL_PASSWORD), alias, keypair.getPrivate(), cCert);
    certs.put(alias, cCert);
  }

  @Test
  public void testHttpsWithAutoReload() throws Exception {
    TestMetricsReporter.reset();
    Properties props = new Properties();
    String httpsUri = "https://localhost:8082";
    props.put(RestConfig.LISTENERS_CONFIG, httpsUri);
    props.put(RestConfig.METRICS_REPORTER_CLASSES_CONFIG, "io.confluent.rest.TestMetricsReporter");
    props.put(RestConfig.SSL_KEYSTORE_LOCATION_CONFIG, serverKeystorePath.toString());
    props.put(RestConfig.SSL_KEYSTORE_PASSWORD_CONFIG, SSL_PASSWORD);
    props.put(RestConfig.SSL_KEY_PASSWORD_CONFIG, SSL_PASSWORD);
    props.put(RestConfig.SSL_KEYSTORE_WATCH_LOCATION_CONFIG, dataDir.toString());
    props.put(RestConfig.SSL_KEYSTORE_RELOAD_CONFIG, "true");
    TestRestConfig config = new TestRestConfig(props);
    SslTestApplication app = new SslTestApplication(config);
    try {
      app.start();
      int statusCode = makeGetRequest(httpsUri + "/test",
          clientKeystore.getAbsolutePath(), SSL_PASSWORD, SSL_PASSWORD);
      assertEquals(200, statusCode, EXPECTED_200_MSG);

      // verify reload -- override the server keystore with a broken one
      serverKeystore.delete();
      Files.delete(dataDir);
      Files.createSymbolicLink(dataDir, Paths.get("err"));
      Thread.sleep(CERT_RELOAD_WAIT_TIME);
      boolean hitError = false;
      try {
        makeGetRequest(httpsUri + "/test",
            clientKeystore.getAbsolutePath(), SSL_PASSWORD, SSL_PASSWORD);
      } catch (Exception e) {
        System.out.println(e);
        hitError = true;
      }

      // verify reload -- override the server keystore with a correct one
      serverKeystoreErr.delete();
      Files.delete(dataDir);
      Files.createSymbolicLink(dataDir, Paths.get("new"));
      Thread.sleep(CERT_RELOAD_WAIT_TIME);
      statusCode = makeGetRequest(httpsUri + "/test",
          clientKeystore.getAbsolutePath(), SSL_PASSWORD, SSL_PASSWORD);
      assertEquals(200, statusCode, EXPECTED_200_MSG);
      assertTrue(hitError, "expect hit error with new server cert");
    } catch (Exception e) {
      log.info(e.toString());
    } finally {
      app.stop();
    }
  }

  // returns the http response status code.
  private int makeGetRequest(String url, String clientKeystoreLocation,
      String clientKeystorePassword,
      String clientKeyPassword)
      throws Exception {
    log.debug("Making GET " + url);
    HttpGet httpget = new HttpGet(url);
    CloseableHttpClient httpclient;
    if (url.startsWith("http://")) {
      httpclient = HttpClients.createDefault();
    } else {
      // trust all self-signed certs.
      SSLContextBuilder sslContextBuilder = SSLContexts.custom()
          .loadTrustMaterial(new TrustSelfSignedStrategy());

      // add the client keystore if it's configured.
      if (clientKeystoreLocation != null) {
        sslContextBuilder.loadKeyMaterial(new File(clientKeystoreLocation),
            clientKeystorePassword.toCharArray(),
            clientKeyPassword.toCharArray());
      }
      SSLContext sslContext = sslContextBuilder.build();

      SSLConnectionSocketFactory sslSf = new SSLConnectionSocketFactory(sslContext,
          new String[]{"TLSv1.2"},
          null, SSLConnectionSocketFactory.getDefaultHostnameVerifier());

      httpclient = HttpClients.custom()
          .setSSLSocketFactory(sslSf)
          .build();
    }

    int statusCode = -1;
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

  private static class SslTestApplication extends Application<TestRestConfig> {

    public SslTestApplication(TestRestConfig props) {
      super(props);
    }

    @Override
    public void setupResources(Configurable<?> config, TestRestConfig appConfig) {
      config.register(new SslTestResource());
    }
  }

  @Path("/test")
  @Produces("application/test.v1+json")
  public static class SslTestResource {

    @Context
    HttpServletRequest request;

    @GET
    public TestResponse hello() {
      return new TestResponse();
    }

    public static class TestResponse {

      @JsonProperty
      public String getMessage() {
        return "foo";
      }
    }
  }
}
