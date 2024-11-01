package io.confluent.rest.handlers;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

import static org.eclipse.jetty.http.HttpStatus.Code.MISDIRECTED_REQUEST;

public class ExpectedSniHandler extends HandlerWrapper {
    private static final Logger log = LoggerFactory.getLogger(SniHandler.class);
    private final List<String> expectedSniHeaders;

    public ExpectedSniHandler(List<String> expectedSniHeaders) {
        this.expectedSniHeaders = expectedSniHeaders;
    }

    @Override
    public void handle(String target, Request baseRequest,
                       HttpServletRequest request,
                       HttpServletResponse response) throws IOException, ServletException {
        String sniServerName = SniUtils.getSniServerName(baseRequest);
        if (sniServerName == null) {
            log.warn("No SNI header present on request");
        }
        else if (!expectedSniHeaders.contains(sniServerName)) {
            log.warn("SNI header {} is not in the configured list of expected headers", sniServerName);
        }

        super.handle(target, baseRequest, request, response);
    }
}
