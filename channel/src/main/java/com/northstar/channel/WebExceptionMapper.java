package com.northstar.channel;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.*;

@Provider
public class WebExceptionMapper implements ExceptionMapper<WebApplicationException> {
  @Override
  public Response toResponse(WebApplicationException error) {
    int status = error.getResponse() == null ? 500 : error.getResponse().getStatus();
    String message =
        switch (status) {
          case 400 -> "The submitted information could not be processed.";
          case 401 -> "Your session has expired. Sign in again.";
          case 403 -> "You do not have permission to perform this action.";
          case 404 -> "The requested credit file was not found.";
          case 409 -> "The record changed before this action completed.";
          default -> "The request could not be completed.";
        };
    return Response.status(status)
        .type(MediaType.TEXT_HTML_TYPE)
        .entity(
            "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\""
                + " content=\"width=device-width\"><title>Request not completed</title><link"
                + " rel=\"stylesheet\" href=\"/assets/app.css\"></head><body><main"
                + " class=\"main\"><section class=\"panel form-panel\""
                + " style=\"max-width:680px;margin:10vh auto\"><p class=\"eyebrow\">ERROR "
                + status
                + "</p><h1>Request not completed</h1><p>"
                + message
                + "</p><a class=\"btn btn-primary\" href=\"/dashboard\">Return to"
                + " overview</a></section></main></body></html>")
        .build();
  }
}
