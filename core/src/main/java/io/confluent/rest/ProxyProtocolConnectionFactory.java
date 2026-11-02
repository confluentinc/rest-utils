/*
 * Copyright 2026 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.rest;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.DetectorConnectionFactory;
import org.eclipse.jetty.server.ProxyConnectionFactory;

/**
 * PROXY protocol connection factory that accepts V2 headers up to a configured size.
 *
 * <p>Delegates all parsing to Jetty's {@link ProxyConnectionFactory}, working around a Jetty bug:
 * the V2 connection acquires its read buffer in its constructor, using the connection default
 * size (2 KiB) rather than the configured maximum header size. A header larger than that buffer
 * never finishes parsing and the connection hangs until the idle timeout. To avoid that, while
 * Jetty creates a PROXY connection, buffers acquired from a pool wrapped by
 * {@link #wrapPool(ByteBufferPool)} are enlarged to fit the largest allowed header.
 * The connector using this factory must use such a pool.</p>
 *
 * <p>Remove this class once Jetty sizes the buffer from the maximum header size.</p>
 */
public final class ProxyProtocolConnectionFactory extends DetectorConnectionFactory {

  // Fixed part of a V2 header: signature, version/command, family/transport and length.
  private static final int V2_FIXED_HEADER_BYTES = 16;

  private static final ThreadLocal<Integer> MIN_BUFFER_SIZE = new ThreadLocal<>();

  private final int maxProxyHeader;

  private ProxyProtocolConnectionFactory(int maxProxyHeader, Detecting... detecting) {
    super(detecting);
    this.maxProxyHeader = maxProxyHeader;
  }

  /**
   * Creates a factory accepting PROXY V1 and V2 headers, with V2 headers (address block plus
   * TLVs) up to {@code maxProxyHeader} bytes.
   */
  public static ProxyProtocolConnectionFactory create(String nextProtocol, int maxProxyHeader) {
    ProxyConnectionFactory jettyFactory = new ProxyConnectionFactory(nextProtocol);
    jettyFactory.setMaxProxyHeader(maxProxyHeader);
    // Jetty's V1 and V2 factories are private, but registered as beans in detection order.
    Collection<Detecting> jettyDetecting = jettyFactory.getBeans(Detecting.class);
    int minBufferSize = V2_FIXED_HEADER_BYTES + maxProxyHeader;
    Detecting[] detecting = jettyDetecting.stream()
        .map(factory -> new BufferSizingDetecting(factory, minBufferSize))
        .toArray(Detecting[]::new);
    return new ProxyProtocolConnectionFactory(maxProxyHeader, detecting);
  }

  /**
   * Wraps a connector's buffer pool so that buffers acquired while a PROXY connection is created
   * are large enough for the maximum V2 header.
   */
  public static ByteBufferPool wrapPool(ByteBufferPool pool) {
    return new ByteBufferPool.Wrapper(pool) {
      @Override
      public RetainableByteBuffer acquire(int size, boolean direct) {
        Integer minSize = MIN_BUFFER_SIZE.get();
        return super.acquire(minSize == null ? size : Math.max(size, minSize), direct);
      }
    };
  }

  public int getMaxProxyHeader() {
    return maxProxyHeader;
  }

  private static final class BufferSizingDetecting implements Detecting {

    private final Detecting delegate;
    private final int minBufferSize;

    private BufferSizingDetecting(Detecting delegate, int minBufferSize) {
      this.delegate = delegate;
      this.minBufferSize = minBufferSize;
    }

    @Override
    public Detection detect(ByteBuffer buffer) {
      return delegate.detect(buffer);
    }

    @Override
    public String getProtocol() {
      return delegate.getProtocol();
    }

    @Override
    public List<String> getProtocols() {
      return delegate.getProtocols();
    }

    @Override
    public Connection newConnection(Connector connector, EndPoint endPoint) {
      // Jetty acquires the PROXY connection's read buffer synchronously in its constructor.
      MIN_BUFFER_SIZE.set(minBufferSize);
      try {
        return delegate.newConnection(connector, endPoint);
      } finally {
        MIN_BUFFER_SIZE.remove();
      }
    }

    @Override
    public String toString() {
      return delegate.toString();
    }
  }
}
