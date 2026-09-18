/*
 * Copyright 2026 - Present Confluent Inc.
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

package io.confluent.rest.handlers;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIMatcher;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.StandardConstants;

/**
 * An {@link SNIMatcher} that records the SNI host name presented during the TLS handshake
 * so it can be read later by HTTP-level handlers. {@link #matches(SNIServerName)} always
 * returns {@code true}; policy decisions (host header comparison, allowlist, prefix match)
 * are deferred to the {@code SniHandler}, {@code PrefixSniHandler}, and
 * {@code ExpectedSniHandler} request handlers.
 *
 * <p>A new instance must be installed per {@link javax.net.ssl.SSLEngine} since the captured
 * value is per-connection state.
 */
public class CapturingSniMatcher extends SNIMatcher {

  private volatile String capturedServerName;

  public CapturingSniMatcher() {
    super(StandardConstants.SNI_HOST_NAME);
  }

  @Override
  public boolean matches(SNIServerName serverName) {
    if (serverName instanceof SNIHostName) {
      capturedServerName = ((SNIHostName) serverName).getAsciiName();
    }
    return true;
  }

  public String getCapturedServerName() {
    return capturedServerName;
  }
}
