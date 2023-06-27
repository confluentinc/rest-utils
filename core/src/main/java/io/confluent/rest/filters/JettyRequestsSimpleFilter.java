package io.confluent.rest.filters;

import io.confluent.rest.metrics.JettyAllRequestsListener;
import java.io.IOException;
import java.util.Objects;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.kafka.common.metrics.Sensor;
import org.eclipse.jetty.servlets.DoSFilter.Listener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JettyRequestsSimpleFilter implements Filter {
  private static final Logger log = LoggerFactory.getLogger(JettyRequestsSimpleFilter.class);
  private JettyAllRequestsListener _listener;
  @Override
  public void init(final FilterConfig filterConfig) throws ServletException {
  }
  public void setListener(JettyAllRequestsListener listener) {
    this._listener = (JettyAllRequestsListener) Objects.requireNonNull(listener, "Listener may not be null");
  }
  @Override
  public void doFilter(final ServletRequest servletRequest, final ServletResponse servletResponse,
      final FilterChain filterChain) throws IOException, ServletException {

    HttpServletRequest httpServletRequest = (HttpServletRequest) servletRequest;
    HttpServletResponse httpServletResponse = (HttpServletResponse) servletResponse;

    log.debug("Getting a request of {}", httpServletRequest);
    this._listener.onRequest(httpServletRequest);
    filterChain.doFilter(httpServletRequest, httpServletResponse);
  }

  @Override
  public void destroy() {
  }
}
