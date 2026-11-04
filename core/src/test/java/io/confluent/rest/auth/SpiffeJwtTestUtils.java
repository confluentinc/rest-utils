/*
 * Copyright 2026 Confluent Inc.
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

import io.spiffe.bundle.jwtbundle.JwtBundle;
import io.spiffe.spiffeid.TrustDomain;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Mints RS256 JWT-SVIDs with plain JDK crypto, and the JWT bundle that trusts them.
 */
public final class SpiffeJwtTestUtils {

  public static final String TRUST_DOMAIN = "example.org";
  public static final String SERVER_AUDIENCE = "spiffe://example.org/ns/test/sa/server";
  public static final String CALLER_ID = "spiffe://example.org/ns/test/sa/caller";

  private final String keyId;
  private final KeyPair keyPair;

  public SpiffeJwtTestUtils(String keyId) {
    this.keyId = keyId;
    this.keyPair = generateRsaKeyPair();
  }

  /** A bundle for {@link #TRUST_DOMAIN} that trusts this signer's key. */
  public JwtBundle bundle() {
    JwtBundle bundle = new JwtBundle(TrustDomain.parse(TRUST_DOMAIN));
    bundle.putJwtAuthority(keyId, keyPair.getPublic());
    return bundle;
  }

  /** A token for {@link #CALLER_ID}, audience {@link #SERVER_AUDIENCE}, valid for 5 minutes. */
  public String token() {
    return token(CALLER_ID, SERVER_AUDIENCE, Instant.now().plus(Duration.ofMinutes(5)));
  }

  public String token(String subject, String audience, Instant expiry) {
    String header = "{\"alg\":\"RS256\",\"kid\":\"" + keyId + "\",\"typ\":\"JWT\"}";
    String claims = "{\"sub\":\"" + subject + "\",\"aud\":[\"" + audience + "\"],\"exp\":"
        + expiry.getEpochSecond() + "}";
    String signingInput = base64Url(header.getBytes(StandardCharsets.UTF_8)) + "."
        + base64Url(claims.getBytes(StandardCharsets.UTF_8));
    try {
      Signature signature = Signature.getInstance("SHA256withRSA");
      signature.initSign(keyPair.getPrivate());
      signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
      return signingInput + "." + base64Url(signature.sign());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String base64Url(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static KeyPair generateRsaKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
