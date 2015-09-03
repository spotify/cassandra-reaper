/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.spotify.reaper.core;

import org.apache.cassandra.repair.RepairParallelism;
import org.joda.time.DateTime;
import org.joda.time.DateTimeComparator;

public class RepairRun implements Comparable<RepairRun> {

  private final long id;

  // IDEA: maybe we want to have start and stop token for parallel runners on same repair run?
  //private final long startToken;
  //private final long endToken;

  private final String cause;
  private final String owner;
  private final String clusterName;
  private final long repairUnitId;
  private final RunState runState;
  private final DateTime creationTime;
  private final DateTime startTime;
  private final DateTime endTime;
  private final DateTime pauseTime;
  private final double intensity;
  private final String lastEvent;
  private final int segmentCount;
  private final RepairParallelism repairParallelism;

  private RepairRun(Builder builder, long id) {
    this.id = id;
    this.clusterName = builder.clusterName;
    this.repairUnitId = builder.repairUnitId;
    this.cause = builder.cause;
    this.owner = builder.owner;
    this.runState = builder.runState;
    this.creationTime = builder.creationTime;
    this.startTime = builder.startTime;
    this.endTime = builder.endTime;
    this.pauseTime = builder.pauseTime;
    this.intensity = builder.intensity;
    this.lastEvent = builder.lastEvent;
    this.segmentCount = builder.segmentCount;
    this.repairParallelism = builder.repairParallelism;
  }

  public long getId() {
    return id;
  }

  public long getRepairUnitId() {
    return repairUnitId;
  }

  public String getClusterName() {
    return clusterName;
  }

  public String getCause() {
    return cause;
  }

  public String getOwner() {
    return owner;
  }

  public RunState getRunState() {
    return runState;
  }

  public DateTime getCreationTime() {
    return creationTime;
  }

  public DateTime getStartTime() {
    return startTime;
  }

  public DateTime getEndTime() {
    return endTime;
  }

  public DateTime getPauseTime() {
    return pauseTime;
  }

  public double getIntensity() {
    return intensity;
  }
  
  public String getLastEvent() {
    return lastEvent;
  }

  public int getSegmentCount() {
    return segmentCount;
  }

  public RepairParallelism getRepairParallelism() {
    return repairParallelism;
  }

  public Builder with() {
    return new Builder(this);
  }

  /**
   * Order RepairRun instances by time. Primarily endTime, secondarily startTime. Descending, i.e.
   * latest first.
   * @param other the RepairRun compared to
   * @return negative if this RepairRun is later than the specified RepairRun. Positive if earlier.
   *         0 if equal.
   */
  @Override
  public int compareTo(RepairRun other) {
    DateTimeComparator comparator = DateTimeComparator.getInstance();
    int endTimeComparison = comparator.compare(endTime, other.endTime);
    if (endTimeComparison != 0) {
      return -endTimeComparison;
    } else {
      return -comparator.compare(startTime, other.startTime);
    }
  }

  public enum RunState {
    NOT_STARTED,
    RUNNING,
    ERROR,
    DONE,
    PAUSED,
    ABORTED,
    DELETED;

    public boolean isActive() {
      return this == RUNNING || this == PAUSED;
    }

    public boolean isTerminated() {
      return this == DONE || this == ERROR || this == ABORTED || this == DELETED;
    }
  }

  public static class Builder {

    public final String clusterName;
    public final long repairUnitId;
    private RunState runState;
    private DateTime creationTime;
    private double intensity;
    private boolean incrementalRepair;
    private String cause;
    private String owner;
    private DateTime startTime;
    private DateTime endTime;
    private DateTime pauseTime;
    private String lastEvent = "no events";
    private int segmentCount;
    private RepairParallelism repairParallelism;

    public Builder(String clusterName, long repairUnitId, DateTime creationTime,
                   double intensity, int segmentCount, RepairParallelism repairParallelism) {
      this.clusterName = clusterName;
      this.repairUnitId = repairUnitId;
      this.runState = RunState.NOT_STARTED;
      this.creationTime = creationTime;
      this.intensity = intensity;
      this.segmentCount = segmentCount;
      this.repairParallelism = repairParallelism;
    }

    private Builder(RepairRun original) {
      clusterName = original.clusterName;
      repairUnitId = original.repairUnitId;
      runState = original.runState;
      creationTime = original.creationTime;
      intensity = original.intensity;
      cause = original.cause;
      owner = original.owner;
      startTime = original.startTime;
      endTime = original.endTime;
      pauseTime = original.pauseTime;
      lastEvent = original.lastEvent;
      segmentCount = original.segmentCount;
      repairParallelism = original.repairParallelism;
    }

    public Builder runState(RunState runState) {
      this.runState = runState;
      return this;
    }

    public Builder creationTime(DateTime creationTime) {
      this.creationTime = creationTime;
      return this;
    }

    public Builder intensity(double intensity) {
      this.intensity = intensity;
      return this;
    }
    
    public Builder cause(String cause) {
      this.cause = cause;
      return this;
    }

    public Builder owner(String owner) {
      this.owner = owner;
      return this;
    }

    public Builder startTime(DateTime startTime) {
      this.startTime = startTime;
      return this;
    }

    public Builder endTime(DateTime endTime) {
      this.endTime = endTime;
      return this;
    }

    public Builder pauseTime(DateTime pauseTime) {
      this.pauseTime = pauseTime;
      return this;
    }

    public Builder lastEvent(String event) {
      this.lastEvent = event;
      return this;
    }

    public Builder segmentCount(int segmentCount) {
      this.segmentCount = segmentCount;
      return this;
    }

    public Builder repairParallelism(RepairParallelism repairParallelism) {
      this.repairParallelism = repairParallelism;
      return this;
    }

    public RepairRun build(long id) {
      return new RepairRun(this, id);
    }
  }
}
