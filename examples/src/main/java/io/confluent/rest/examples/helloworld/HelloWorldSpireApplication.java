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

package io.confluent.rest.examples.helloworld;

import io.spiffe.workloadapi.X509Source;
import io.spiffe.workloadapi.DefaultX509Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.TreeMap;

import io.confluent.rest.RestConfig;
import io.confluent.rest.RestConfigException;

/**
 * A version of HelloWorldApplication that enables SPIRE SSL configuration.
 * This application demonstrates how to use SPIRE for mTLS authentication.
 */
public class HelloWorldSpireApplication extends HelloWorldApplication {
  private static final Logger log = LoggerFactory.getLogger(HelloWorldSpireApplication.class);
  private static final String DEFAULT_SPIRE_SOCKET_PATH = "tcp://127.0.0.1:31523";

  public HelloWorldSpireApplication(HelloWorldRestConfig config, X509Source x509Source) {
    super(config, x509Source);
  }

  private static X509Source initializeX509Source(String spireSocketPath) throws Exception {
    log.info("Initializing X509Source with SPIRE agent socket at: {}", spireSocketPath);
    
    // There are more options for the X509Source, but we're using the default options for now.
    // For example, you can adjust the timeout for the X509Source to 10 seconds.
    // These kinds of options are available in the X509SourceOptions class based on your needs.
    DefaultX509Source.X509SourceOptions x509SourceOptions = DefaultX509Source.X509SourceOptions
        .builder()
        .spiffeSocketPath(spireSocketPath)
        .svidPicker(list -> list.get(list.size() - 1))  // Use the last SVID in the list
        .build();
    
    return DefaultX509Source.newSource(x509SourceOptions);
  }

  public static void main(String[] args) {
    try {
      // Configure SPIRE SSL settings
      TreeMap<String, String> settings = new TreeMap<>();

      // Enable SPIRE SSL
      settings.put(RestConfig.SSL_SPIRE_ENABLED_CONFIG, "true");

      // Enable mTLS (mutual TLS)
      settings.put(
          RestConfig.SSL_CLIENT_AUTHENTICATION_CONFIG,
          RestConfig.SSL_CLIENT_AUTHENTICATION_REQUIRED);

      // Configure HTTPS listener
      settings.put(RestConfig.LISTENERS_CONFIG, "https://localhost:8080");

      // Disable SNI host check
      // SPIRE handles identity and authentication at the SPIFFE level, not via DNS hostnames.
      // The SNI field is still sent, but it's not used for authentication.
      // Certificate validation is done via SPIFFE IDs, not CN/SAN fields tied to DNS names.
      settings.put(RestConfig.SNI_HOST_CHECK_ENABLED_CONFIG, "false");

      // Add any custom greeting message if provided
      if (args.length > 0) {
        settings.put(HelloWorldRestConfig.GREETING_CONFIG, args[0]);
      }

      HelloWorldRestConfig config = new HelloWorldRestConfig(settings);

      // Initialize X509Source before creating the application
      // We need to initialize the X509Source before creating the application
      // The X509Source is responsible for obtaining the client certificate
      // and verifying it against the SPIRE trust store.
      // If we don't initialize the X509Source, the application will fail to start.
      X509Source x509Source = initializeX509Source(DEFAULT_SPIRE_SOCKET_PATH);

      // Create application with both config and X509Source
      HelloWorldSpireApplication app = new HelloWorldSpireApplication(config, x509Source);
      app.start();
      log.info("Server started with SPIRE SSL enabled, listening for requests...");
      app.join();
    } catch (RestConfigException e) {
      log.error("Server configuration failed: " + e.getMessage());
      System.exit(1);
    } catch (Exception e) {
      log.error("Server died unexpectedly: " + e.toString());
      System.exit(1);
    }
  }
} 