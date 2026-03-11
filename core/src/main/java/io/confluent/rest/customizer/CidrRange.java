/*
 * Copyright 2025 Confluent Inc.
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

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Represents a CIDR range (e.g., "10.240.0.0/16") and provides a method to check
 * whether a given IP address falls within the range.
 */
public class CidrRange {

  private final byte[] networkBytes;
  private final int prefixLength;

  private CidrRange(byte[] networkBytes, int prefixLength) {
    this.networkBytes = networkBytes;
    this.prefixLength = prefixLength;
  }

  /**
   * Parses a CIDR notation string (e.g., "10.240.0.0/16") into a {@link CidrRange}.
   *
   * @param cidr the CIDR string to parse
   * @return a {@link CidrRange} instance
   * @throws ConfigException if the CIDR string is malformed
   */
  public static CidrRange parse(String cidr) {
    String[] parts = cidr.split("/");
    if (parts.length != 2) {
      throw new ConfigException("Invalid CIDR notation: " + cidr);
    }

    InetAddress network;
    try {
      network = InetAddress.getByName(parts[0]);
    } catch (UnknownHostException e) {
      throw new ConfigException("Invalid network address in CIDR: " + parts[0]);
    }

    int prefixLength;
    try {
      prefixLength = Integer.parseInt(parts[1]);
    } catch (NumberFormatException e) {
      throw new ConfigException("Invalid prefix length in CIDR: " + parts[1]);
    }

    byte[] networkBytes = network.getAddress();
    int maxPrefix = networkBytes.length * 8;
    if (prefixLength < 0 || prefixLength > maxPrefix) {
      throw new ConfigException(
          "Prefix length " + prefixLength + " is out of range for "
              + (networkBytes.length == 4 ? "IPv4" : "IPv6")
              + " address (0-" + maxPrefix + ")");
    }

    return new CidrRange(networkBytes, prefixLength);
  }

  /**
   * Checks whether the given address is within this CIDR range.
   *
   * @param address the address to check
   * @return true if the address is within this range, false otherwise
   */
  public boolean contains(InetAddress address) {
    byte[] addrBytes = address.getAddress();

    // IPv4 vs IPv6 mismatch
    if (addrBytes.length != networkBytes.length) {
      return false;
    }

    // Compare full bytes
    int fullBytes = prefixLength / 8;
    for (int i = 0; i < fullBytes; i++) {
      if (addrBytes[i] != networkBytes[i]) {
        return false;
      }
    }

    // Compare remaining bits
    int remainingBits = prefixLength % 8;
    if (remainingBits > 0) {
      int mask = 0xFF << (8 - remainingBits);
      if ((addrBytes[fullBytes] & mask) != (networkBytes[fullBytes] & mask)) {
        return false;
      }
    }

    return true;
  }
}
