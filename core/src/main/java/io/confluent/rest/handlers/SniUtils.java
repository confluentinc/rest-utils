package io.confluent.rest.handlers;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.server.Request;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLSession;
import java.util.List;

public class SniUtils {
    public static String getSniServerName(Request baseRequest) {
        EndPoint endpoint = baseRequest.getHttpChannel().getEndPoint();
        if (endpoint instanceof SslConnection.DecryptedEndPoint) {
            SSLSession session = ((SslConnection.DecryptedEndPoint) endpoint)
                .getSslConnection()
                .getSSLEngine()
                .getSession();
            if (session instanceof ExtendedSSLSession) {
                List<SNIServerName> servers = ((ExtendedSSLSession) session).getRequestedServerNames();
                if (servers != null) {
                    return servers.stream()
                        .findAny()
                        .filter(SNIHostName.class::isInstance)
                        .map(SNIHostName.class::cast)
                        .map(SNIHostName::getAsciiName)
                        .orElse(null);
                }
            }
        }
        return null;
    }
}
