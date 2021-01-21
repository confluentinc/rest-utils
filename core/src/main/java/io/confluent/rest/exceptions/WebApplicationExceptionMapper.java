/**
 * Copyright 2014 Confluent Inc.
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

package io.confluent.rest.exceptions;

import io.confluent.rest.RestConfig;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import java.util.List;

public class WebApplicationExceptionMapper
    extends DebuggableExceptionMapper<WebApplicationException> {

  @Context
  HttpHeaders headers;

  public static enum Status implements Response.StatusType {
    UNPROCESSABLE_ENTITY(422, "Unprocessable Entity");

    private final int code;
    private final String reason;
    private final Response.Status.Family family;

    private Status(int statusCode, String reasonPhrase) {
      this.code = statusCode;
      this.reason = reasonPhrase;
      this.family = Response.Status.Family.familyOf(statusCode);
    }

    public Response.Status.Family getFamily() {
      return this.family;
    }

    public int getStatusCode() {
      return this.code;
    }

    public String getReasonPhrase() {
      return this.toString();
    }

    public String toString() {
      return this.reason;
    }

    public static Status fromStatusCode(int statusCode) {
      for (Status s: values()) {
        if (s.code == statusCode) {
          return s;
        }
      }
      return null;
    }
  }

  public WebApplicationExceptionMapper(RestConfig restConfig) {
    super(restConfig);
  }

  @Override
  public Response toResponse(WebApplicationException exc) {
    // WebApplicationException unfortunately doesn't expose the status, or even status code,
    // directly.
    Response.StatusType status = Response.Status.fromStatusCode(exc.getResponse().getStatus());

    // Response.Status does not contain all http status. This results in
    // status to be null above for the cases of some valid 4xx status i.e 422.
    // Below we are trying to replace the null with a valid status listed in
    // WebApplicationExceptionMapper.Status

    if (status == null) {
      status = Status.fromStatusCode(exc.getResponse().getStatus());
    }

    // The human-readable message for these can use the exception message directly. Since
    // WebApplicationExceptions are expected to be passed back to users, it will either contain a
    // situation-specific message or the HTTP status message
    int errorCode = (exc instanceof RestException) ? ((RestException)exc).getErrorCode()
                                                   : status.getStatusCode();
    Response.ResponseBuilder response = createResponse(exc, errorCode, status, exc.getMessage());

    // Apparently, 415 Unsupported Media Type errors disable content negotiation in Jersey, which
    // causes use to return data without a content type. Work around this by detecting that specific
    // type of error and performing the negotiation manually.
    if (status == Response.Status.UNSUPPORTED_MEDIA_TYPE) {
      response.type(negotiateContentType());
    }

    return response.build();
  }

  private String negotiateContentType() {
    List<MediaType> acceptable = headers.getAcceptableMediaTypes();
    for (MediaType mt : acceptable) {
      for (String providable : restConfig.getList(RestConfig.RESPONSE_MEDIATYPE_PREFERRED_CONFIG)) {
        if (mt.toString().equals(providable)) {
          return providable;
        }
      }
    }
    return restConfig.getString(RestConfig.RESPONSE_MEDIATYPE_DEFAULT_CONFIG);
  }
}
