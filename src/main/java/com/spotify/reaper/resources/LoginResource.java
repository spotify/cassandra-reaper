package com.spotify.reaper.resources;

import org.apache.commons.lang3.StringUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.IncorrectCredentialsException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.subject.Subject;
import org.secnod.shiro.jaxrs.Auth;

import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import java.io.IOException;

@Path("/")
public class LoginResource {

  @Path("/login")
  @POST
  public void login(@FormParam("username") String username, @FormParam("password") String password, @Auth Subject subject) throws IOException {
    ensurePresent(username, "Invalid credentials: missing username.");
    ensurePresent(password, "Invalid credentials: missing password.");

    try {
      subject.login(new UsernamePasswordToken(username, password));
    } catch (AuthenticationException e) {
      throw new IncorrectCredentialsException("Invalid credentials combination for user: " + username);
    }
  }

  @Path("/logout")
  @POST
  public void logout(@Auth Subject subject) throws IOException {
    subject.logout();
  }

  private void ensurePresent(String value, String message) {
    if (StringUtils.isBlank(value)) {
      throw new IncorrectCredentialsException(message);
    }
  }
}
