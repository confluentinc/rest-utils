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

package io.confluent.rest.customizer;

import org.apache.kafka.common.config.ConfigException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CidrRangeTest {

  @Test
  public void testContainsWithinRange() throws UnknownHostException {
    CidrRange range = CidrRange.parse("10.240.0.0/16");
    assertTrue(range.contains(InetAddress.getByName("10.240.0.1")));
    assertTrue(range.contains(InetAddress.getByName("10.240.255.255")));
    assertTrue(range.contains(InetAddress.getByName("10.240.0.0")));
    assertTrue(range.contains(InetAddress.getByName("10.240.128.42")));
  }

  @Test
  public void testContainsOutsideRange() throws UnknownHostException {
    CidrRange range = CidrRange.parse("10.240.0.0/16");
    assertFalse(range.contains(InetAddress.getByName("10.241.0.1")));
    assertFalse(range.contains(InetAddress.getByName("192.168.1.1")));
    assertFalse(range.contains(InetAddress.getByName("10.239.255.255")));
    assertFalse(range.contains(InetAddress.getByName("11.240.0.1")));
  }

  @Test
  public void testContainsBoundary24() throws UnknownHostException {
    CidrRange range = CidrRange.parse("192.168.1.0/24");
    assertTrue(range.contains(InetAddress.getByName("192.168.1.0")));
    assertTrue(range.contains(InetAddress.getByName("192.168.1.255")));
    assertFalse(range.contains(InetAddress.getByName("192.168.2.0")));
    assertFalse(range.contains(InetAddress.getByName("192.168.0.255")));
  }

  @Test
  public void testContainsNonByteBoundary() throws UnknownHostException {
    CidrRange range = CidrRange.parse("10.0.0.0/12");
    assertTrue(range.contains(InetAddress.getByName("10.0.0.1")));
    assertTrue(range.contains(InetAddress.getByName("10.15.255.255")));
    assertFalse(range.contains(InetAddress.getByName("10.16.0.0")));
    assertFalse(range.contains(InetAddress.getByName("10.32.0.0")));
  }

  @Test
  public void testContainsSlash32() throws UnknownHostException {
    CidrRange range = CidrRange.parse("10.240.1.5/32");
    assertTrue(range.contains(InetAddress.getByName("10.240.1.5")));
    assertFalse(range.contains(InetAddress.getByName("10.240.1.6")));
  }

  @Test
  public void testContainsSlash0() throws UnknownHostException {
    CidrRange range = CidrRange.parse("0.0.0.0/0");
    assertTrue(range.contains(InetAddress.getByName("10.240.1.5")));
    assertTrue(range.contains(InetAddress.getByName("192.168.1.1")));
    assertTrue(range.contains(InetAddress.getByName("255.255.255.255")));
  }

  @Test
  public void testIpv6Mismatch() throws UnknownHostException {
    CidrRange range = CidrRange.parse("10.240.0.0/16");
    assertFalse(range.contains(InetAddress.getByName("::1")));
  }

  @Test
  public void testInvalidCidrNoPrefixLength() {
    assertThrows(ConfigException.class, () -> CidrRange.parse("10.240.0.0"));
  }

  @Test
  public void testInvalidCidrBadAddress() {
    assertThrows(ConfigException.class, () -> CidrRange.parse("not.an.ip/16"));
  }

  @Test
  public void testInvalidCidrBadPrefixLength() {
    assertThrows(ConfigException.class, () -> CidrRange.parse("10.240.0.0/abc"));
  }

  @Test
  public void testInvalidCidrPrefixTooLarge() {
    assertThrows(ConfigException.class, () -> CidrRange.parse("10.240.0.0/33"));
  }

  @Test
  public void testInvalidCidrNegativePrefix() {
    assertThrows(ConfigException.class, () -> CidrRange.parse("10.240.0.0/-1"));
  }
}
