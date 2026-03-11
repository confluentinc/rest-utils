# rest-utils: IP Filtering for v3/v4 Migration

## Overview

This document captures the full context for the rest-utils changes required to support the `proxy.protocol.accepted.ip.range` config. This is the companion change to [ce-kafka PR #28385](https://github.com/confluentinc/ce-kafka/pull/28385), which implemented the same feature at the Kafka broker transport layer.

---

## Background

See `ce-kafka/v4-ip-filtering-context.md` for the full background on the v4 Everywhere migration and the IP filtering + PROXY protocol dilemma.

**In short**: Migrating from v3 (load balancers) to v4 (Envoy proxy) on GCP and Azure creates a chicken-and-egg problem. During the migration window, both v3 (no PROXY protocol) and v4 (with PROXY protocol) connections coexist. If the broker enables PROXY protocol processing before Envoy is deployed, an attacker can send a fake PROXY header through the v3 LB to spoof their IP and bypass IP filtering.

**Solution**: A new per-listener config `proxy.protocol.accepted.ip.range` (CIDR notation, e.g., `10.240.0.0/16`) tells the server to only trust PROXY protocol data from connections whose raw TCP peer IP falls within the range. Envoy runs in a known private CIDR range, so only Envoy connections get PROXY treatment.

---

## Why rest-utils Needs Changes

The Kafka REST proxy (`ce-kafka-rest`) uses rest-utils for its HTTP server (Jetty). When PROXY protocol is enabled, Jetty's `ProxyConnectionFactory` parses the PROXY header and Confluent's `ProxyCustomizer` exposes the parsed data as request attributes. The same IP spoofing vulnerability exists here: if a client sends a fake PROXY header through the v3 LB, the REST proxy would see the spoofed IP.

---

## Key Architectural Difference from ce-kafka

In **ce-kafka**, the broker controls PROXY header parsing directly in `AbstractTransportLayer.maybeReadAndProcessProxyHeaders()`. The guard **skips parsing entirely** when the peer IP is outside the accepted range.

In **rest-utils**, Jetty's `ProxyConnectionFactory` **always parses** the PROXY header at the connection level before our code runs. By the time `ProxyCustomizer.customize()` is called, the `ProxyEndPoint` already has the PROXY-parsed addresses, and `request.getRemoteAddr()` returns the PROXY source address.

This means simply "skipping" our customizer wrapping is **not sufficient** — `getRemoteAddr()` would still return the spoofed address from the PROXY header.

### The Fix: `RawPeerRequest` with `ConnectionMetaData.Wrapper`

When the peer IP is NOT in the accepted range, the `ProxyCustomizer` wraps the request with a `RawPeerRequest` that:
1. Overrides `getConnectionMetaData()` to return a `ConnectionMetaData.Wrapper` that uses the raw TCP peer addresses instead of the PROXY-parsed ones
2. Does NOT set the `TLV_PROVIDER_ATTRIBUTE_NAME` or other PROXY attributes

This effectively "undoes" the `ProxyEndPoint`'s address override at the request level, so `getRemoteAddr()` returns the raw TCP peer IP (which IS the real client IP when the connection comes through the v3 LB).

---

## Files Changed

### New Files

#### `CidrRange.java` (`io.confluent.rest.customizer`)
CIDR parsing and IP containment utility. Same logic as the ce-kafka version, adapted to the rest-utils package (2-space indentation, Confluent license header).

- `parse(String cidr)` — Parses "10.240.0.0/16" into a CidrRange
- `contains(InetAddress)` — Bitwise prefix comparison to check if an IP is in the range

#### `CidrRangeTest.java` (`io.confluent.rest.customizer`)
12 unit tests covering: range containment, boundary conditions (`/24`, `/12`, `/32`, `/0`), IPv6 mismatch, and invalid input (missing prefix, bad address, bad prefix length, negative prefix, prefix too large).

#### `ProxyCustomizerTest.java` (`io.confluent.rest.customizer`)
5 unit tests using Mockito:
- `testNoRangeConfigured_alwaysWraps` — Backward compat: no range → always wraps with ProxyRequest
- `testPeerInRange_wraps` — Peer in range → wraps with ProxyRequest (PROXY data used)
- `testPeerNotInRange_wrapsWithRawPeerAddress` — Peer NOT in range → wraps with RawPeerRequest, verifies TLV_PROVIDER is null and connection metadata returns raw TCP addresses
- `testNonProxyEndpoint_passesThrough` — No ProxyEndPoint → request unchanged
- `testNoRange_nonProxyEndpoint_passesThrough` — No range + no ProxyEndPoint → unchanged

### Modified Files

#### `RestConfig.java` (`io.confluent.rest`)
Added config definition:
- `proxy.protocol.accepted.ip.range` — STRING type, default empty, LOW importance
- When empty (default), behavior is unchanged (PROXY data used unconditionally)
- When set to a CIDR range, only connections from that range get PROXY treatment

#### `ProxyCustomizer.java` (`io.confluent.rest.customizer`)
Core logic change:
- Added `CidrRange acceptedIpRange` field and constructor overload
- In `customize()`: when `acceptedIpRange` is set, checks the raw TCP peer IP against the range
  - Peer IN range → existing `ProxyRequest` wrapping (PROXY data used, TLVs exposed)
  - Peer NOT in range → new `RawPeerRequest` wrapping (overrides `getRemoteAddr()` to raw peer IP, no TLVs)
- Added inner class `RawPeerRequest extends Request.Wrapper` that uses `ConnectionMetaData.Wrapper` to override `getRemoteSocketAddress()` and `getLocalSocketAddress()` to the raw TCP values

#### `ApplicationServer.java` (`io.confluent.rest`)
Wiring:
- Reads `proxy.protocol.accepted.ip.range` from per-listener config
- Parses to `CidrRange` (or null if empty)
- Passes to `new ProxyCustomizer(acceptedIpRange)`
- Extracted `parseAcceptedIpRange()` helper to stay under checkstyle cyclomatic complexity limit

#### `ProxyProtocolTest.java` (`io.confluent.rest`)
2 new E2E integration tests:
- `testAcceptedIpRange_peerNotInRange_proxyDataIgnored` — Range `10.240.0.0/16`, connects from localhost (NOT in range), sends PROXY v1 header with spoofed IP `192.168.0.1`. Verifies `getRemoteAddr()` returns `127.0.0.1` (raw peer IP).
- `testAcceptedIpRange_peerInRange_proxyDataUsed` — Range `127.0.0.0/8`, connects from localhost (IN range), sends PROXY v1 header with client IP `192.168.0.1`. Verifies `getRemoteAddr()` returns `192.168.0.1` (from PROXY header).

---

## How It Works End-to-End

### Connection from Envoy (peer IP in range)

```
Client → Envoy (10.240.x.x) → [PROXY header: client IP] → Kafka REST
                                                              ↓
                                              ProxyConnectionFactory parses PROXY header
                                              ProxyEndPoint: getRemoteAddr() = client IP
                                                              ↓
                                              ProxyCustomizer: peer 10.240.x.x IS in range
                                              → Wraps with ProxyRequest (TLVs, attributes)
                                              → getRemoteAddr() = client IP ✓
```

### Connection from v3 LB (peer IP NOT in range)

```
Client → v3 LB (preserves IP) → Kafka REST
                                      ↓
                      ProxyConnectionFactory: no PROXY header
                      Regular endpoint: getRemoteAddr() = client IP ✓
```

### Attacker spoofing through v3 LB (peer IP NOT in range)

```
Attacker → v3 LB → [fake PROXY header: spoofed IP] → Kafka REST
                                                          ↓
                                          ProxyConnectionFactory parses fake PROXY header
                                          ProxyEndPoint: getRemoteAddr() = spoofed IP ✗
                                                          ↓
                                          ProxyCustomizer: peer (LB IP) NOT in 10.240.0.0/16
                                          → Wraps with RawPeerRequest
                                          → Overrides getRemoteAddr() = attacker's real IP ✓
                                          → IP filtering blocks the attacker ✓
```

---

## Config Interaction

| `proxy.protocol.enabled` | `proxy.protocol.accepted.ip.range` | Behavior |
|--------------------------|-------------------------------------|----------|
| `false`                  | (ignored)                           | No PROXY processing at all |
| `true`                   | `""` (empty, default)               | PROXY data used unconditionally (existing behavior) |
| `true`                   | `"10.240.0.0/16"`                   | PROXY data used only from peers in the range; others see raw TCP peer IP |

---

## Deployment Steps

1. Deploy rest-utils with the new config (no behavior change with default empty value)
2. Set `proxy.protocol.accepted.ip.range=10.240.0.0/16` (Envoy CIDR) on the listener config
3. Enable `proxy.protocol.enabled=true`
4. Migrate traffic from v3 LB to v4 Envoy incrementally
5. Once all traffic is through Envoy, the accepted range config can be removed (or left as defense-in-depth)

---

## Testing Summary

| Test Class | Count | Type | What It Tests |
|------------|-------|------|---------------|
| `CidrRangeTest` | 12 | Unit | CIDR parsing and IP containment |
| `ProxyCustomizerTest` | 5 | Unit (Mockito) | Peer-IP guard logic, RawPeerRequest wrapping |
| `ProxyProtocolTest` (new tests) | 2 | E2E integration | Full Jetty server with raw socket PROXY headers |
| `ProxyProtocolTest` (existing) | 8 | E2E integration | Existing PROXY v1/v2, HTTP/HTTPS, TLV tests (backward compat) |

---

## Discovery: ConnectionMetaData Override

During E2E testing, we discovered that simply skipping the `ProxyRequest` wrapping was insufficient. Jetty's `ProxyConnectionFactory` parses the PROXY header at the connection level, so `request.getRemoteAddr()` still returned the spoofed address.

The fix uses Jetty 12's `ConnectionMetaData.Wrapper` to override `getRemoteSocketAddress()` and `getLocalSocketAddress()` on the wrapped request. This is a clean Jetty 12 pattern — `ConnectionMetaData.Wrapper` delegates all methods to the original and allows selective overrides.

This is architecturally different from the ce-kafka approach (which skips parsing entirely) but achieves the same result: when the peer is not a trusted proxy, the server sees the raw TCP peer IP.
