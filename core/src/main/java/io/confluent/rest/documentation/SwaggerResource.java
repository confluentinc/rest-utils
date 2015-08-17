package io.confluent.rest.documentation;

import java.net.URI;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.UriBuilder;

@Path("/doc")
public class SwaggerResource {

  private final String swaggerUiVersion;
  private final String swaggerUiIndexHtmlPath;
  private final String apiExamplesPath;

  public SwaggerResource(String swaggerUiVersion, String swaggerUiIndexHtmlPath, String apiExamplesPath) {
    this.swaggerUiVersion = swaggerUiVersion;
    this.swaggerUiIndexHtmlPath = swaggerUiIndexHtmlPath;
    this.apiExamplesPath = apiExamplesPath;
  }

  @GET
  @Path("{path:.*}")
  public Response swaggerUiResource(@PathParam("path") String path) {
    String fullPath;
    if (path.equals("index.html")) {
      fullPath = this.swaggerUiIndexHtmlPath;
    } else if (path.startsWith("examples")) {
      fullPath = path.replaceFirst("^examples/", this.apiExamplesPath + "/");
    } else {
      fullPath = "/META-INF/resources/webjars/swagger-ui/" + this.swaggerUiVersion + "/" + path;
    }
    ResponseBuilder responseBuilder = Response.ok();
    responseBuilder.entity(this.getClass().getResourceAsStream(fullPath));
    return responseBuilder.build();
  }

  @GET
  public Response redirectDoc(@Context HttpServletRequest request) {
    URI uri = UriBuilder.fromUri(request.getRequestURI()).path("/index.html").build();
    return Response.seeOther(uri).build();
  }

}
