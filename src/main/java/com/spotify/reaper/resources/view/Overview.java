package com.spotify.reaper.resources.view;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Overview {

  @JsonProperty
  private String cluster;

  @JsonProperty
  private long runId;

  @JsonProperty
  private String status;

  @JsonProperty
  private int doneSegments;

  @JsonProperty
  private int totalSegments;

  public Overview(String name, long id, String status, int allSegments, int doneSegments) {
    this.cluster = name;
    this.runId = id;
    this.totalSegments = allSegments;
    this.doneSegments = doneSegments;
    this.status = status;
  }
}
