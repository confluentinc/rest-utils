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
import javax.net.ssl.SSLEngine;
import org.eclipse.jetty.alpn.java.server.JDK9ServerALPNProcessor;
import org.eclipse.jetty.io.ssl.ALPNProcessor;

/**
 * A server ALPN processor that works with BouncyCastle's JSSE provider.
 */
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@AutoService(ALPNProcessor.Server.class)
public class BouncyCastleServerALPNProcessor extends JDK9ServerALPNProcessor {

  @Override
  public boolean appliesTo(SSLEngine sslEngine) {
    return sslEngine.getClass().getName().startsWith("org.bouncycastle.jsse.provider");
  }
}