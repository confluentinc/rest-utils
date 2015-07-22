package io.confluent.rest.documentation;

import java.net.URI;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;

@Path("/doc")
public class RedirectDocResource {

  @GET
  public Response redirectDoc(@Context HttpServletRequest request, @Context HttpServletResponse response) {
    URI uri = UriBuilder.fromUri(request.getRequestURI()).replacePath("/webjars/swagger-ui/2.1.0/index.html").build();
    return Response.seeOther(uri).build();
  }

}
