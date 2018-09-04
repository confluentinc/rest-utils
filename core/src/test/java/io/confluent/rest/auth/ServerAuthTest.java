/*
 * Copyright 2018 Confluent Inc.
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

package io.confluent.rest.auth;

import static org.apache.kafka.common.config.ConfigDef.NO_DEFAULT_VALUE;

import io.confluent.common.Configurable;
import io.confluent.common.utils.IntegrationTest;
import io.confluent.rest.RestConfig;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.Principal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.security.auth.Subject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import junit.textui.TestRunner;
import org.apache.http.auth.BasicUserPrincipal;
import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigDef.Importance;
import org.apache.kafka.common.config.ConfigDef.Type;
import org.apache.kafka.common.security.JaasUtils;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.jaas.spi.PropertyFileLoginModule;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;

@Category({IntegrationTest.class})
public class ServerAuthTest{

//  private static final String PROPS_JAAS_REALM = "KsqlServer-Props";
//
//  @ClassRule
//  public static final TemporaryFolder TMP_FOLDER = new TemporaryFolder();
//
//  private TestTerminal terminal;
//  private Cli localCli;
//  private TestKsqlRestApp server;
//
//  @After
//  public void tearDown() throws Exception {
//    if (localCli != null) {
//      localCli.close();
//      localCli = null;
//    }
//
//    if (terminal != null) {
//      terminal.close();
//      terminal = null;
//    }
//
//    if (server != null) {
//      server.stop();
//      server = null;
//    }
//  }
//
//  @Test
//  public void shouldWorkWithNoAuth() {
//    givenServerWith(ImmutableMap.<String, Object>builder()
//        .put(RestConfig.AUTHENTICATION_METHOD_CONFIG, "NONE")
//        .build());
//
//    givenClientWith(client -> {
//    });
//
//    assertCanConnect();
//  }
//
//  @Test
//  public void shouldWorkWithBasicAuth() {
//    givenServerWith(ImmutableMap.<String, Object>builder()
//        .put(RestConfig.AUTHENTICATION_METHOD_CONFIG, "BASIC")
//        .put(RestConfig.AUTHENTICATION_REALM_CONFIG, PROPS_JAAS_REALM)
//        .put(RestConfig.AUTHENTICATION_ROLES_CONFIG, "ksql-cluster-1")
//        .put(JaasUtils.JAVA_LOGIN_CONFIG_PARAM, EmbeddedSingleNodeKafkaCluster.getJaasConfigPath())
//        .build());
//
//    givenClientWith(client -> client.setupAuthenticationCredentials("harry", "changeme"));
//
//    assertCanConnect();
//  }
//
//  @Test
//  public void shouldWorkWithCustomWithNoRoles() {
//    givenServerWith(ImmutableMap.<String, Object>builder()
//        .put(RestConfig.AUTHENTICATION_METHOD_CONFIG, "CUSTOM")
//        .put(KsqlRestConfig.KSQL_CUSTOM_AUTHENTICATION_METHOD_TYPE_CONFIG,
//            TestSecurityHandlerFactory.class)
//        .put(TestSecurityHandlerFactory.TestConfig.SHARED_SECRET, "DoNotTellAnyOneThis")
//        .build());
//
//    givenClientWith(client -> client.setUpCustomAuthentication("MyAuth DoNotTellAnyOneThis"));
//
//    assertCanConnect();
//  }
//
//  @Test
//  public void shouldWorkWithCustomWithRoles() {
//    givenServerWith(ImmutableMap.<String, Object>builder()
//        .put(RestConfig.AUTHENTICATION_METHOD_CONFIG, "CUSTOM")
//        .put(RestConfig.AUTHENTICATION_ROLES_CONFIG, "role1")
//        .put(KsqlRestConfig.KSQL_CUSTOM_AUTHENTICATION_METHOD_TYPE_CONFIG,
//            TestSecurityHandlerFactory.class)
//        .put(TestSecurityHandlerFactory.TestConfig.SHARED_SECRET, "DoNotTellAnyOneThis")
//        .put(TestSecurityHandlerFactory.TestConfig.INJECT_ROLE, "role1")
//        .build());
//
//    givenClientWith(client -> client.setUpCustomAuthentication("MyAuth DoNotTellAnyOneThis"));
//
//    assertCanConnect();
//  }
//
//  private void assertCanConnect() {
//    try {
//      final TestRunner testRunner = new TestRunner(localCli, terminal);
//
//      final OrderedResult expectedResult = OrderedResult.build(
//          ImmutableList.of(ImmutableList.of("TIMESTAMPTOSTRING", "SCALAR")));
//
//      testRunner.test("show functions", expectedResult);
//    } catch (final AssertionError e) {
//      throw new AssertionError("Failed to connect and run test command", e);
//    }
//  }
//
//  private void givenServerWith(final Map<String, ?> props) {
//    server = TestKsqlRestApp
//        .builder(CLUSTER::bootstrapServers)
//        .withProperties(props)
//        .build();
//    server.start();
//  }
//
//  private void givenClientWith(final Consumer<KsqlRestClient> handler) {
//    final Map<String, Object> props = new HashMap<>();
//    final KsqlRestClient restClient =
//        new KsqlRestClient(server.getListeners().get(0).toString(), props);
//
//    handler.accept(restClient);
//
//    terminal = new TestTerminal(OutputFormat.TABULAR, restClient);
//    localCli = new Cli(10000L, 10000L, restClient, terminal);
//  }
//
//  private static String createJaasConfigContent() {
//    try {
//      TMP_FOLDER.create();
//    } catch (final IOException e) {
//      throw new RuntimeException(e);
//    }
//    final File credFile = copyResourceFile("/test-credentials.props", "/test-credentials.props");
//    return PROPS_JAAS_REALM + " {\n  "
//        + PropertyFileLoginModule.class.getName() + " required\n"
//        + "  file=\"" + credFile + "\"\n"
//        + "  debug=\"true\";\n"
//        + "};\n";
//  }
//
//  private static File copyResourceFile(final String source, final String destination) {
//    try {
//      final File pem = TMP_FOLDER.newFile(destination);
//      Files.copy(
//          SecurityIntegrationTest.class.getResourceAsStream(source),
//          pem.toPath(),
//          StandardCopyOption.REPLACE_EXISTING
//      );
//      return pem;
//    } catch (final Exception e) {
//      throw new RuntimeException("Failed to create JWT public key file", e);
//    }
//  }
//
//  public static final class TestSecurityHandlerFactory implements KsqlAuthenticator, Configurable {
//
//    private String sharedSecret = "NotConfiguredYet";
//    private String roleToInject = "";
//
//    @Override
//    public void configure(final Map<String, ?> props) {
//      final TestConfig config = new TestConfig(props);
//      sharedSecret = config.getString(TestConfig.SHARED_SECRET);
//      roleToInject = config.getString(TestConfig.INJECT_ROLE);
//    }
//
//    @Override
//    public String getAuthMethod() {
//      return "MyAuth";
//    }
//
//    @Override
//    public AuthenticationResult authenticate(
//        final HttpServletRequest request,
//        final HttpServletResponse response) {
//
//      String token = request.getHeader(HttpHeader.AUTHORIZATION.asString());
//      if (token != null) {
//        int space = token.indexOf(' ');
//        if (space > 0) {
//          String method = token.substring(0, space);
//          if (getAuthMethod().equalsIgnoreCase(method)) {
//            token = token.substring(space + 1);
//            if (sharedSecret.equals(token)) {
//              final Subject subject = new Subject();
//              final Principal principal = new BasicUserPrincipal("fred");
//              subject.getPrincipals().add(principal);
//              final List<String> roles = roleToInject.isEmpty()
//                  ? Collections.emptyList() : Collections.singletonList(roleToInject);
//              return AuthenticationResult.user(subject, principal, roles);
//            }
//          }
//        }
//      }
//
//      try {
//        response.sendError(401);
//        return AuthenticationResult.SENT_FAILURE;
//      } catch (final IOException e) {
//        throw new ServerAuthException(e);
//      }
//    }
//
//    private static class TestConfig extends AbstractConfig {
//
//      private static final String SHARED_SECRET = "my.shared.secret";
//      private static final String INJECT_ROLE = "role.to.inject";
//
//      private static final ConfigDef CONFIG_DEF = new ConfigDef()
//          .define(
//              SHARED_SECRET,
//              Type.STRING,
//              NO_DEFAULT_VALUE,
//              new ConfigDef.NonEmptyString(),
//              Importance.HIGH,
//              "Some really insecure example of authenticating a user"
//          ).define(
//              INJECT_ROLE,
//              Type.STRING,
//              "",
//              new ConfigDef.NonNullValidator(),
//              Importance.HIGH,
//              "A hacky way to inject a role each authenticated user will be in"
//          );
//
//      private TestConfig(final Map<?, ?> originals) {
//        super(CONFIG_DEF, originals);
//      }
//    }
//  }
}