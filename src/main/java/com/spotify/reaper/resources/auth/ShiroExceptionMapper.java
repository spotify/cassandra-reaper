package com.spotify.reaper.resources.auth;

import org.apache.shiro.ShiroException;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authz.AuthorizationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

import static java.lang.String.format;

@Provider
public class ShiroExceptionMapper implements ExceptionMapper<ShiroException> {

  private static final Logger LOG = LoggerFactory.getLogger(ShiroExceptionMapper.class);

  @Override
  public Response toResponse(ShiroException exception) {
    if (AuthorizationException.class.isAssignableFrom(exception.getClass())
        || AuthenticationException.class.isAssignableFrom(exception.getClass())) {
      LOG.info("Authentication failed", exception);
      return Response.status(Response.Status.FORBIDDEN).entity(exception.getMessage()).build();
    }

    LOG.error("Unexpected ShiroException", exception);
    return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
  }
}
