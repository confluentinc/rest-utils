package io.confluent.rest.customiser;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.ProxyConnectionFactory;
import org.eclipse.jetty.server.Request;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HashSet;
import java.util.Set;

/**
 * Similar to {@link org.eclipse.jetty.server.ProxyCustomizer} but allowing access to tlvs as well
 */
public class ProxyCustomizer implements HttpConfiguration.Customizer {

  // The remote address attribute name.
  public static final String REMOTE_ADDRESS_ATTRIBUTE_NAME = "io.confluent.rest.proxy.remote.address";
  // The remote port attribute name.
  public static final String REMOTE_PORT_ATTRIBUTE_NAME = "io.confluent.rest.proxy.remote.port";
  // The local address attribute name.
  public static final String LOCAL_ADDRESS_ATTRIBUTE_NAME = "io.confluent.rest.proxy.local.address";
  // The local port attribute name.
  public static final String LOCAL_PORT_ATTRIBUTE_NAME = "io.confluent.rest.proxy.local.port";
  // The tlvs attribute name.
  public static final String TLV_PROVIDER_ATTRIBUTE_NAME = "io.confluent.rest.proxy.tlv.provider";

  @Override
  public Request customize(Request request, HttpFields.Mutable mutable) {
    EndPoint endPoint = request.getConnectionMetaData().getConnection().getEndPoint();

    if (endPoint instanceof SslConnection.SslEndPoint) {
      endPoint = ((SslConnection.SslEndPoint) endPoint).unwrap();
    }

    if (endPoint instanceof ProxyConnectionFactory.ProxyEndPoint proxyEndPoint) {
      EndPoint underlyingEndpoint = proxyEndPoint.unwrap();
      request = new ProxyRequest(request,
          underlyingEndpoint.getLocalSocketAddress(),
          underlyingEndpoint.getRemoteSocketAddress(),
          proxyEndPoint::getTLV);
    }
    return request;
  }

  private static class ProxyRequest extends Request.Wrapper {
    private final String remoteAddress;
    private final String localAddress;
    private final int remotePort;
    private final int localPort;
    private final TLVProvider tlvProvider;

    private ProxyRequest(Request request, SocketAddress local, SocketAddress remote, TLVProvider tlvProvider)
    {
      super(request);
      InetSocketAddress inetLocal = local instanceof InetSocketAddress ? (InetSocketAddress)local : null;
      InetSocketAddress inetRemote = remote instanceof InetSocketAddress ? (InetSocketAddress)remote : null;
      this.localAddress = inetLocal == null ? null : inetLocal.getAddress().getHostAddress();
      this.remoteAddress = inetRemote == null ? null : inetRemote.getAddress().getHostAddress();
      this.localPort = inetLocal == null ? 0 : inetLocal.getPort();
      this.remotePort = inetRemote == null ? 0 : inetRemote.getPort();
      this.tlvProvider = tlvProvider;
    }

    @Override
    public Object getAttribute(String name) {
      return switch (name) {
        case REMOTE_ADDRESS_ATTRIBUTE_NAME -> remoteAddress;
        case REMOTE_PORT_ATTRIBUTE_NAME -> remotePort;
        case LOCAL_ADDRESS_ATTRIBUTE_NAME -> localAddress;
        case LOCAL_PORT_ATTRIBUTE_NAME -> localPort;
        case TLV_PROVIDER_ATTRIBUTE_NAME -> tlvProvider;
        default -> super.getAttribute(name);
      };
    }

    @Override
    public Set<String> getAttributeNameSet() {
      Set<String> names = new HashSet<>(super.getAttributeNameSet());
      names.remove(REMOTE_ADDRESS_ATTRIBUTE_NAME);
      names.remove(LOCAL_ADDRESS_ATTRIBUTE_NAME);

      if (remoteAddress != null) {
        names.add(REMOTE_ADDRESS_ATTRIBUTE_NAME);
      }
      if (localAddress != null) {
        names.add(LOCAL_ADDRESS_ATTRIBUTE_NAME);
      }

      names.add(REMOTE_PORT_ATTRIBUTE_NAME);
      names.add(LOCAL_PORT_ATTRIBUTE_NAME);
      names.add(TLV_PROVIDER_ATTRIBUTE_NAME);

      return names;
    }
  }
}
