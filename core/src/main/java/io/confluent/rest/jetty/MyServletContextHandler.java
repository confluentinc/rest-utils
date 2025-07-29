/*
 * Copyright 2014 - 2025 Confluent Inc.
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

package io.confluent.rest.jetty;

import org.eclipse.jetty.ee10.servlet.ServletChannel;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletContextRequest;
import org.eclipse.jetty.ee10.servlet.ServletHandler;
import org.eclipse.jetty.ee10.servlet.ServletHandler.MappedServlet;
import org.eclipse.jetty.http.pathmap.MatchedResource;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ContextRequest;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.annotation.ManagedObject;

@ManagedObject("Servlet Context Handler")
public class MyServletContextHandler extends ServletContextHandler {

  public MyServletContextHandler() {
    super();
  }

  public MyServletContextHandler(final int options) {
    super(options);
  }

  @Override
  protected ContextRequest wrapRequest(Request request, Response response) {
    String decodedPathInContext;
    MatchedResource<MappedServlet> matchedResource;

    // Need to ask directly to the Context for the pathInContext, rather than using
    // Request.getPathInContext(), as the request is not yet wrapped in this Context.
    decodedPathInContext = URIUtil.decodePath(
        getContext().getPathInContext(request.getHttpURI().getCanonicalPath()));
    matchedResource = _servletHandler.getMatchedServlet(decodedPathInContext);

    if (matchedResource == null) {
      return wrapNoServlet(request, response);
    }
    ServletHandler.MappedServlet mappedServlet = matchedResource.getResource();
    if (mappedServlet == null) {
      return wrapNoServlet(request, response);
    }
    // create a ServletChannel for each request
    ServletChannel servletChannel = new ServletChannel(this, request);
    ServletContextRequest servletContextRequest = newServletContextRequest(
        servletChannel, request, response, decodedPathInContext, matchedResource);
    servletChannel.associate(servletContextRequest);
    return servletContextRequest;
  }

  private ContextRequest wrapNoServlet(Request request, Response response) {
    Handler next = getServletHandler().getHandler();
    if (next == null) {
      return null;
    }
    return super.wrapRequest(request, response);
  }
}