package com.spotify.reaper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.spotify.reaper.resources.view.RepairRunStatus;
import com.spotify.reaper.resources.view.RepairScheduleStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * This is a simple client for testing usage, that calls the Reaper REST API
 * and turns the resulting JSON into Reaper core entity instances.
 */
public class SimpleReaperClient {

  private static final Logger LOG = LoggerFactory.getLogger(SimpleReaperClient.class);

  public static Response doHttpCall(String httpMethod, String host, int port, String urlPath,
                                    Optional<Map<String, String>> params) {
    String reaperBase = "http://" + host.toLowerCase() + ":" + port + "/";
    URI uri;
    try {
      uri = new URL(new URL(reaperBase), urlPath).toURI();
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
    WebTarget target = ClientBuilder.newClient().target(uri);
    LOG.info("calling (" + httpMethod + ") Reaper in target: " + target.getUri());
    if (params.isPresent()) {
      for (Map.Entry<String, String> entry : params.get().entrySet()) {
        target = target.queryParam(entry.getKey(), entry.getValue());
      }
    }

    return target.request().build(httpMethod, contentFor(httpMethod).orNull()).invoke();
  }

  private static Optional<Entity<String>> contentFor(String httpMethod) {
    if ("PUT".equalsIgnoreCase(httpMethod) || "POST".equalsIgnoreCase(httpMethod)) {
      return Optional.of(noContent());
    }
    return Optional.absent();
  }

  private static Entity<String> noContent() {
    return Entity.text("");
  }

  private static <T> T parseJSON(String json, TypeReference<T> ref) {
    T parsed;
    ObjectMapper mapper = new ObjectMapper();
    try {
      parsed = mapper.readValue(json, ref);
    } catch (Exception e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }
    return parsed;
  }

  public static List<RepairScheduleStatus> parseRepairScheduleStatusListJSON(String json) {
    return parseJSON(json, new TypeReference<List<RepairScheduleStatus>>() {
    });
  }

  public static RepairScheduleStatus parseRepairScheduleStatusJSON(String json) {
    return parseJSON(json, new TypeReference<RepairScheduleStatus>() {
    });
  }

  public static List<RepairRunStatus> parseRepairRunStatusListJSON(String json) {
    return parseJSON(json, new TypeReference<List<RepairRunStatus>>() {
    });
  }

  public static RepairRunStatus parseRepairRunStatusJSON(String json) {
    return parseJSON(json, new TypeReference<RepairRunStatus>() {
    });
  }

  private String reaperHost;
  private int reaperPort;

  public SimpleReaperClient(String reaperHost, int reaperPort) {
    this.reaperHost = reaperHost;
    this.reaperPort = reaperPort;
  }

  public List<RepairScheduleStatus> getRepairSchedulesForCluster(String clusterName, Optional<Map<String, String>> params) {
    Response response = doHttpCall("GET", reaperHost, reaperPort,
            "/repair_schedule/cluster/" + clusterName, params);
    assertEquals(200, response.getStatus());
    String responseData = response.readEntity(String.class);
    return parseRepairScheduleStatusListJSON(responseData);
  }

}
