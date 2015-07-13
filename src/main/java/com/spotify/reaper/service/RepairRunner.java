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
package com.spotify.reaper.service;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.spotify.reaper.AppContext;
import com.spotify.reaper.ReaperException;
import com.spotify.reaper.cassandra.JmxProxy;
import com.spotify.reaper.core.Cluster;
import com.spotify.reaper.core.RepairRun;
import com.spotify.reaper.core.RepairSegment;
import com.spotify.reaper.core.RepairUnit;

import org.apache.cassandra.repair.RepairParallelism;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongArray;

import javax.annotation.Nullable;

public class RepairRunner implements Runnable {

  private static final Logger LOG = LoggerFactory.getLogger(RepairRunner.class);

  private final AppContext context;
  private final long repairRunId;
  private final String clusterName;
  private JmxProxy jmxConnection;
  private final AtomicLongArray currentlyRunningSegments;
  private final List<RingRange> parallelRanges;
  private final RepairUnit repairUnit;

  public RepairRunner(AppContext context, long repairRunId)
      throws ReaperException {
    this.context = context;
    this.repairRunId = repairRunId;
    Optional<RepairRun> repairRun = context.storage.getRepairRun(repairRunId);
    assert repairRun.isPresent() : "No RepairRun with ID " + repairRunId + " found from storage";
    Optional<Cluster> cluster = context.storage.getCluster(repairRun.get().getClusterName());
    assert cluster.isPresent() : "No Cluster with name " + repairRun.get().getClusterName()
                                 + " found from storage";
    Optional<RepairUnit> repairUnit = context.storage.getRepairUnit(repairRun.get().getRepairUnitId());
    assert repairUnit.isPresent() : "No RepairUnit with id " + repairRun.get().getRepairUnitId()
                                 + " found in storage";
    this.clusterName = cluster.get().getName();
    JmxProxy jmx = this.context.jmxConnectionFactory.connectAny(cluster.get());

    this.repairUnit = repairUnit.get();
    String keyspace = this.repairUnit.getKeyspaceName();
    int parallelRepairs = getPossibleParallelRepairsCount(jmx.getRangeToEndpointMap(keyspace));
    currentlyRunningSegments = new AtomicLongArray(parallelRepairs);
    for(int i=0;i<parallelRepairs;i++) {
      currentlyRunningSegments.set(i, -1);
    }

    parallelRanges = getParallelRanges(
        parallelRepairs,
        Lists.newArrayList(Collections2.transform(
            context.storage.getRepairSegmentsForRun(repairRunId),
            new Function<RepairSegment, RingRange>() {
              @Override
              public RingRange apply(RepairSegment input) {
                return input.getTokenRange();
              }
            })));
  }

  public long getRepairRunId() {
    return repairRunId;
  }

  @VisibleForTesting
  public static int getPossibleParallelRepairsCount(Map<List<String>, List<String>> ranges)
      throws ReaperException {
    if (ranges.size() == 0) {
      String msg = "Repairing 0-sized cluster.";
      LOG.error(msg);
      throw new ReaperException(msg);
    }
    return ranges.size() / ranges.values().iterator().next().size();
  }

  @VisibleForTesting
  public static List<RingRange> getParallelRanges(int parallelRepairs, List<RingRange> segments)
      throws ReaperException {
    if (parallelRepairs == 0) {
      String msg = "Can't repair anything with 0 threads";
      LOG.error(msg);
      throw new ReaperException(msg);
    }

    Collections.sort(segments, RingRange.startComparator);

    List<RingRange> parallelRanges = Lists.newArrayList();
    for (int i = 0; i < parallelRepairs - 1; i++) {
      parallelRanges.add(new RingRange(
          segments.get(i * segments.size() / parallelRepairs).getStart(),
          segments.get((i + 1) * segments.size() / parallelRepairs).getStart()
      ));
    }
    parallelRanges.add(new RingRange(
        segments.get((parallelRepairs - 1) * segments.size() / parallelRepairs).getStart(),
        segments.get(0).getStart()
    ));

    return parallelRanges;
  }

  /**
   * Starts/resumes a repair run that is supposed to run.
   */
  @Override
  public void run() {

    Thread.currentThread().setName(clusterName + ":" + repairRunId);

    try {
      Optional<RepairRun> repairRun = context.storage.getRepairRun(repairRunId);
      if ((!repairRun.isPresent() || repairRun.get().getRunState().isTerminated()) &&
          context.repairManager.repairRunners.containsKey(repairRunId)) {
        // this might happen if a run is deleted while paused etc.
        LOG.warn("RepairRun \"" + repairRunId + "\" does not exist. Killing "
                 + "RepairRunner for this run instance.");
        context.repairManager.removeRunner(this);
        return;
      }
      RepairRun.RunState state = repairRun.get().getRunState();
      LOG.debug("run() called for repair run #{} with run state {}", repairRunId, state);
      switch (state) {
        case NOT_STARTED:
          start();
          break;
        case RUNNING:
          startNextSegment();
          break;
        case PAUSED:
          context.repairManager.scheduleRetry(this);
          break;
      }
    } catch (ReaperException | RuntimeException e) {
      LOG.error("RepairRun FAILURE");
      LOG.error(e.toString());
      LOG.error(Arrays.toString(e.getStackTrace()));
      e.printStackTrace();
      synchronized (this) {
        context.storage.modifyRepairRun(repairRunId, new Function<RepairRun.Builder, RepairRun.Builder>() {
              @Override
              public RepairRun.Builder apply(RepairRun.Builder original) {
                return original
                    .runState(RepairRun.RunState.ERROR)
                    .lastEvent(String.format("Exception: %s", e.getMessage()))
                    .endTime(DateTime.now());
              }
            });
        context.repairManager.removeRunner(this);
      }
    }
  }

  /**
   * Starts the repair run.
   */
  private void start() throws ReaperException {
    LOG.info("Repairs for repair run #{} starting", repairRunId);
    context.storage.modifyRepairRun(repairRunId, new Function<RepairRun.Builder, RepairRun.Builder>() {
          @Override
          public RepairRun.Builder apply(RepairRun.Builder original) {
            return original
                .runState(RepairRun.RunState.RUNNING)
                .startTime(DateTime.now());
          }
        });
    startNextSegment();
  }

  /**
   * Concludes the repair run.
   */
  private void end() {
    LOG.info("Repairs for repair run #{} done", repairRunId);
    synchronized (this) {
      context.storage.modifyRepairRun(repairRunId,
          new Function<RepairRun.Builder, RepairRun.Builder>() {
            @Override
            public RepairRun.Builder apply(RepairRun.Builder original) {
              return original
                  .runState(RepairRun.RunState.DONE)
                  .endTime(DateTime.now())
                  .lastEvent("All done");
            }
          });
      context.repairManager.removeRunner(this);
    }
  }

  /**
   * Get the next segment and repair it. If there is none, we're done.
   */
  private void startNextSegment() throws ReaperException {
    boolean noMoreSegments = true;
    for (int rangeIndex = 0; rangeIndex < currentlyRunningSegments.length(); rangeIndex++) {
      Optional<RepairSegment> nextRepairSegment =
          context.storage.getNextFreeSegmentInRange(repairRunId, parallelRanges.get(rangeIndex));
      if (!nextRepairSegment.isPresent()) {
        LOG.debug("No repair segment available for range {}", parallelRanges.get(rangeIndex));
      } else {
        noMoreSegments = false;
        long segmentId = nextRepairSegment.get().getId();
        boolean wasSet = currentlyRunningSegments.compareAndSet(rangeIndex, -1, segmentId);
        if (!wasSet) {
          LOG.debug("Didn't set segment id `{}` to slot {} because it was busy", segmentId, rangeIndex);
        } else {
          LOG.debug("Did set segment id `{}` to slot {}", segmentId, rangeIndex);
          repairSegment(rangeIndex, nextRepairSegment.get().getId(), nextRepairSegment.get().getTokenRange());
        }
      }
    }
    if (noMoreSegments) {
      boolean allRangesDone = true;
      for(int i=0;i<currentlyRunningSegments.length();i++) {
        if (currentlyRunningSegments.get(i) != -1) {
          allRangesDone = false;
        }
      }
      if (allRangesDone) {
        if (context.storage.getSegmentAmountForRepairRunWithState(repairRunId, RepairSegment.State.DONE) ==
            context.storage.getSegmentAmountForRepairRun(repairRunId)) {
          end();
        } else {
          LOG.debug("No more segments to repair, but some still running");
          context.repairManager.scheduleRetry(this);
        }
      }
    }
  }

  /**
   * Start the repair of a segment.
   *
   * @param segmentId  id of the segment to repair.
   * @param tokenRange token range of the segment to repair.
   */
  private void repairSegment(final int rangeIndex, final long segmentId, final RingRange tokenRange)
      throws ReaperException {
    final long unitId;
    final double intensity;
    final RepairParallelism validationParallelism;
    {
      RepairRun repairRun = context.storage.getRepairRun(repairRunId).get();
      unitId = repairRun.getRepairUnitId();
      intensity = repairRun.getIntensity();
      validationParallelism = repairRun.getRepairParallelism();
    }

    RepairUnit repairUnit = context.storage.getRepairUnit(unitId).get();
    String keyspace = repairUnit.getKeyspaceName();
    LOG.debug("preparing to repair segment {} on run with id {}", segmentId, repairRunId);

    if (jmxConnection == null || !jmxConnection.isConnectionAlive()) {
      try {
        LOG.debug("connecting JMX proxy for repair runner on run id: {}", repairRunId);
        Cluster cluster = context.storage.getCluster(repairUnit.getClusterName()).get();
        jmxConnection = context.jmxConnectionFactory.connectAny(cluster);
      } catch (ReaperException e) {
        e.printStackTrace();
        LOG.warn("Failed to reestablish JMX connection in runner #{}, retrying", repairRunId);
        currentlyRunningSegments.set(rangeIndex, -1);
        context.repairManager.scheduleRetry(this);
        return;
      }
      LOG.debug("successfully reestablished JMX proxy for repair runner on run id: {}", repairRunId);
    }

    List<String> potentialCoordinators = jmxConnection.tokenRangeToEndpoint(keyspace, tokenRange);
    if (potentialCoordinators.isEmpty()) {
      // This segment has a faulty token range. Abort the entire repair run.
      synchronized (this) {
        context.storage.modifyRepairRun(repairRunId, new Function<RepairRun.Builder, RepairRun.Builder>() {
              @Override
              public RepairRun.Builder apply(RepairRun.Builder original) {
                return original
                    .runState(RepairRun.RunState.ERROR)
                    .lastEvent(String.format("No coordinators for range %s", tokenRange.toString()))
                    .endTime(DateTime.now());
              }
            });
        context.repairManager.removeRunner(this);
      }
      return;
    }

    SegmentRunner segmentRunner = new SegmentRunner(context, segmentId, potentialCoordinators,
        context.repairManager.getRepairTimeoutMillis(), intensity, validationParallelism,
        clusterName, repairUnit, this);

    ListenableFuture<?> segmentResult = context.repairManager.submitSegment(segmentRunner);
    Futures.addCallback(segmentResult, new FutureCallback<Object>() {
      @Override
      public void onSuccess(Object ignored) {
        handleResult(segmentId);
        currentlyRunningSegments.set(rangeIndex, -1);
      }
      @Override
      public void onFailure(Throwable t) {
        currentlyRunningSegments.set(rangeIndex, -1);
        LOG.error("Executing SegmentRunner failed: " + t.getMessage());
      }
    });
  }

  private void handleResult(long segmentId) {
    RepairSegment segment = context.storage.getRepairSegment(segmentId).get();
    RepairSegment.State state = segment.getState();
    LOG.debug("In repair run #{}, triggerRepair on segment {} ended with state {}",
        repairRunId, segmentId, state);
    switch (state) {
      case NOT_STARTED:
        // Unsuccessful repair
        context.repairManager.scheduleRetry(this);
        break;
      case DONE:
        // Successful repair
        context.repairManager.submitNextRun(this);
        break;
      default:
        // Another thread has started a new repair on this segment already
        // Or maybe the same repair segment id should never be re-run in which case this is an error
        String msg = "handleResult called with a segment state (" + state + ") that it should not"
                     + " have after segmentRunner has tried a repair";
        LOG.error(msg);
        throw new RuntimeException(msg);
    }
  }

  public void updateLastEvent(final String newEvent) {
    synchronized (this) {
      RepairRun repairRun = context.storage.getRepairRun(repairRunId).get();
      if (repairRun.getRunState().isTerminated()) {
        LOG.warn("Will not update lastEvent of run that has already terminated. The message was: "
            + "\"{}\"", newEvent);
      } else {
        context.storage.modifyRepairRun(repairRunId, new Function<RepairRun.Builder, RepairRun.Builder>() {
          @Override
          public RepairRun.Builder apply(RepairRun.Builder original) {
            return original
                .lastEvent(newEvent);
          }
        });
      }
    }
  }
}
