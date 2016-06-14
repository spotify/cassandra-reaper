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
package com.spotify.reaper.resources;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;

import com.spotify.reaper.AppContext;
import com.spotify.reaper.ReaperException;
import com.spotify.reaper.core.Cluster;
import com.spotify.reaper.core.RepairSchedule;
import com.spotify.reaper.core.RepairUnit;
import com.spotify.reaper.resources.view.RepairScheduleStatus;
import com.spotify.reaper.service.SchedulingManager;

import org.apache.cassandra.repair.RepairParallelism;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import static com.google.common.base.Preconditions.checkNotNull;

@Path("/repair_schedule")
@Produces(MediaType.APPLICATION_JSON)
public class RepairScheduleResource {

  private static final Logger LOG = LoggerFactory.getLogger(RepairScheduleResource.class);

  private final AppContext context;

  public RepairScheduleResource(AppContext context) {
    this.context = context;
  }

  /**
   * Endpoint used to create a repair schedule. Does not allow triggering the run.
   * Repair schedule will create new repair runs based on the schedule.
   *
   * Notice that query parameter "tables" can be a single String, or a
   * comma-separated list of table names. If the "tables" parameter is omitted, and only the
   * keyspace is defined, then created repair runs will target all the tables in the keyspace.
   *
   * @return created repair schedule data as JSON.
   */
  @POST
  public Response addRepairSchedule(
      @Context UriInfo uriInfo,
      @QueryParam("clusterName") Optional<String> clusterName,
      @QueryParam("keyspace") Optional<String> keyspace,
      @QueryParam("tables") Optional<String> tableNamesParam,
      @QueryParam("owner") Optional<String> owner,
      @QueryParam("segmentCount") Optional<Integer> segmentCount,
      @QueryParam("repairParallelism") Optional<String> repairParallelism,
      @QueryParam("intensity") Optional<String> intensityStr,
      @QueryParam("incrementalRepair") Optional<String> incrementalRepairStr,
      @QueryParam("scheduleDaysBetween") Optional<Integer> scheduleDaysBetween,
      @QueryParam("scheduleTriggerTime") Optional<String> scheduleTriggerTime
  ) {
    LOG.info("add repair schedule called with: clusterName = {}, keyspace = {}, tables = {}, "
             + "owner = {}, segmentCount = {}, repairParallelism = {}, "
             + "intensity = {}, incrementalRepair = {}, scheduleDaysBetween = {}, scheduleTriggerTime = {}",
        clusterName, keyspace, tableNamesParam, owner, segmentCount, repairParallelism,
        intensityStr, incrementalRepairStr, scheduleDaysBetween, scheduleTriggerTime);
    try {
      Response possibleFailResponse = RepairRunResource.checkRequestForAddRepair(
          context, clusterName, keyspace, owner, segmentCount, repairParallelism, intensityStr, incrementalRepairStr);
      if (null != possibleFailResponse) {
        return possibleFailResponse;
      }

      DateTime nextActivation;
      if (scheduleTriggerTime.isPresent()) {
        try {
          nextActivation = DateTime.parse(scheduleTriggerTime.get());
        } catch (IllegalArgumentException ex) {
          LOG.info("cannot parse data string: " + scheduleTriggerTime.get());
          return Response.status(Response.Status.BAD_REQUEST).entity(
              "invalid schedule_trigger_time").build();
        }
        LOG.info("first schedule activation will be: "
                 + CommonTools.dateTimeToISO8601(nextActivation));
      } else {
        nextActivation = DateTime.now().plusDays(1).withTimeAtStartOfDay();
        LOG.info("no schedule_trigger_time given, so setting first scheduling next night: "
                 + CommonTools.dateTimeToISO8601(nextActivation));
      }
      if (nextActivation.isBeforeNow()) {
        return Response.status(Response.Status.BAD_REQUEST).entity(
            "given schedule_trigger_time is in the past: "
            + CommonTools.dateTimeToISO8601(nextActivation)).build();
      }

      if(!scheduleDaysBetween.isPresent()) {
    	  return Response.status(Response.Status.BAD_REQUEST).entity(
              "missing required parameter: scheduleDaysBetween").build();
      }

      Double intensity;
      if (intensityStr.isPresent()) {
        intensity = Double.parseDouble(intensityStr.get());
      } else {
        intensity = context.config.getRepairIntensity();
        LOG.debug("no intensity given, so using default value: " + intensity);
      }

      Boolean incrementalRepair;
      if (incrementalRepairStr.isPresent()) {
    	  incrementalRepair = Boolean.parseBoolean(incrementalRepairStr.get());
      } else {
    	  incrementalRepair = context.config.getIncrementalRepair();
        LOG.debug("no incremental repair given, so using default value: " + incrementalRepair);
      }

      int segments = context.config.getSegmentCount();
      if (segmentCount.isPresent()) {
        LOG.debug("using given segment count {} instead of configured value {}",
            segmentCount.get(), context.config.getSegmentCount());
        segments = segmentCount.get();
      }

      int daysBetween = context.config.getScheduleDaysBetween();
      if (scheduleDaysBetween.isPresent()) {
        LOG.debug("using given schedule days between {} instead of configured value {}",
            scheduleDaysBetween.get(), context.config.getScheduleDaysBetween());
        daysBetween = scheduleDaysBetween.get();
      }

      Cluster cluster = context.storage.getCluster(Cluster.toSymbolicName(clusterName.get())).get();
      Set<String> tableNames;
      try {
        tableNames = CommonTools.getTableNamesBasedOnParam(context, cluster, keyspace.get(),
            tableNamesParam);
      } catch (IllegalArgumentException ex) {
        return Response.status(Response.Status.NOT_FOUND).entity(ex.getMessage()).build();
      }

      RepairUnit theRepairUnit =
          CommonTools.getNewOrExistingRepairUnit(context, cluster, keyspace.get(), tableNames, incrementalRepair);

      if(theRepairUnit.getIncrementalRepair() != incrementalRepair) {
    	  return Response.status(Response.Status.BAD_REQUEST).entity(
                  "A repair Schedule already exist for the same cluster/keyspace/table but with a different incremental repair value."
                  + "Requested value: " + incrementalRepair + " | Existing value: " + theRepairUnit.getIncrementalRepair()).build();
      }

      RepairParallelism parallelism = context.config.getRepairParallelism();
      if (repairParallelism.isPresent()) {
        LOG.debug("using given repair parallelism {} instead of configured value {}",
            repairParallelism.get(), context.config.getRepairParallelism());
        parallelism = RepairParallelism.valueOf(repairParallelism.get().toUpperCase());
      }

      if(!parallelism.equals(RepairParallelism.PARALLEL) && incrementalRepair) {
          return Response.status(Response.Status.BAD_REQUEST).entity(
                  "It is not possible to mix sequential repair and incremental repairs. parallelism "
                  + parallelism + " : incrementalRepair " + incrementalRepair).build();
      }

      RepairSchedule newRepairSchedule = CommonTools.storeNewRepairSchedule(
          context, cluster, theRepairUnit, daysBetween, nextActivation, owner.get(),
          segments, parallelism, intensity);

      return Response.created(buildRepairScheduleURI(uriInfo, newRepairSchedule))
          .entity(new RepairScheduleStatus(newRepairSchedule, theRepairUnit)).build();

    } catch (ReaperException e) {
      LOG.error(e.getMessage());
      e.printStackTrace();
      return Response.status(500).entity(e.getMessage()).build();
    }
  }

  /**
   * Modifies a state of the repair schedule. <p/> Currently supports PAUSED -> ACTIVE and
   * ACTIVE -> PAUSED.
   *
   * @return OK if all goes well NOT_MODIFIED if new state is the same as the old one, and 400
   * (BAD_REQUEST) if transition is not supported.
   */
  @PUT
  @Path("/{id}")
  public Response modifyState(
      @Context UriInfo uriInfo,
      @PathParam("id") Long repairScheduleId,
      @QueryParam("state") Optional<String> state) {

    LOG.info("modify repair schedule state called with: id = {}, state = {}",
        repairScheduleId, state);

    if (!state.isPresent()) {
      return Response.status(Response.Status.BAD_REQUEST.getStatusCode())
          .entity("\"state\" argument missing").build();
    }

    Optional<RepairSchedule> repairSchedule = context.storage.getRepairSchedule(repairScheduleId);
    if (!repairSchedule.isPresent()) {
      return Response.status(Response.Status.NOT_FOUND).entity("repair schedule with id "
                                                               + repairScheduleId + " not found")
          .build();
    }

    Optional<RepairUnit> repairUnit =
        context.storage.getRepairUnit(repairSchedule.get().getRepairUnitId());
    if (!repairUnit.isPresent()) {
      String errMsg =
          "repair unit with id " + repairSchedule.get().getRepairUnitId() + " not found";
      LOG.error(errMsg);
      return Response.status(Response.Status.NOT_FOUND).entity(errMsg).build();
    }

    RepairSchedule.State newState;
    try {
      newState = RepairSchedule.State.valueOf(state.get().toUpperCase());
    } catch (IllegalArgumentException ex) {
      return Response.status(Response.Status.BAD_REQUEST.getStatusCode())
          .entity("invalid \"state\" argument: " + state.get()).build();
    }
    RepairSchedule.State oldState = repairSchedule.get().getState();

    if (oldState == newState) {
      return Response.ok("given \"state\" is same as the current state").build();
    }

    if (isPausing(oldState, newState)) {
      return pauseSchedule(repairSchedule.get(), repairUnit.get());
    } else if (isResuming(oldState, newState)) {
      return resumeSchedule(repairSchedule.get(), repairUnit.get());
    } else {
      String errMsg = String.format("Transition %s->%s not supported.", oldState.toString(),
          newState.toString());
      LOG.error(errMsg);
      return Response.status(Response.Status.BAD_REQUEST).entity(errMsg).build();
    }
  }

  private static boolean isPausing(RepairSchedule.State oldState, RepairSchedule.State newState) {
    return oldState == RepairSchedule.State.ACTIVE && newState == RepairSchedule.State.PAUSED;
  }

  private static boolean isResuming(RepairSchedule.State oldState, RepairSchedule.State newState) {
    return oldState == RepairSchedule.State.PAUSED && newState == RepairSchedule.State.ACTIVE;
  }

  private Response pauseSchedule(RepairSchedule repairSchedule, RepairUnit repairUnit) {
    LOG.info("Pausing schedule {}", repairSchedule.getId());
    RepairSchedule newSchedule = SchedulingManager.pauseRepairSchedule(context, repairSchedule);
    return Response.ok().entity(new RepairScheduleStatus(newSchedule, repairUnit)).build();
  }

  private Response resumeSchedule(RepairSchedule repairSchedule, RepairUnit repairUnit) {
    LOG.info("Resuming schedule {}", repairSchedule.getId());
    RepairSchedule newSchedule = SchedulingManager.resumeRepairSchedule(context, repairSchedule);
    return Response.ok().entity(new RepairScheduleStatus(newSchedule, repairUnit)).build();
  }

  /**
   * @return detailed information about a repair schedule.
   */
  @GET
  @Path("/{id}")
  public Response getRepairSchedule(@PathParam("id") Long repairScheduleId) {
    LOG.debug("get repair_schedule called with: id = {}", repairScheduleId);
    Optional<RepairSchedule> repairSchedule = context.storage.getRepairSchedule(repairScheduleId);
    if (repairSchedule.isPresent()) {
      return Response.ok().entity(getRepairScheduleStatus(repairSchedule.get())).build();
    } else {
      return Response.status(404).entity(
          "repair schedule with id " + repairScheduleId + " doesn't exist").build();
    }
  }

  /**
   * @param clusterName The cluster_name for which the repair schedule belongs to.
   * @return all know repair schedules for a cluster.
   */
  @GET
  @Path("/cluster/{cluster_name}")
  public Response getRepairSchedulesForCluster(@PathParam("cluster_name") String clusterName) {
    LOG.debug("get repair schedules for cluster called with: cluster_name = {}", clusterName);
    Collection<RepairSchedule> repairSchedules =
        context.storage.getRepairSchedulesForCluster(clusterName);
    Collection<RepairScheduleStatus> repairScheduleViews = new ArrayList<>();
    for (RepairSchedule repairSchedule : repairSchedules) {
      repairScheduleViews.add(getRepairScheduleStatus(repairSchedule));
    }
    return Response.ok().entity(repairScheduleViews).build();
  }

  /**
   * @return RepairSchedule status for viewing
   */
  private RepairScheduleStatus getRepairScheduleStatus(RepairSchedule repairSchedule) {
    Optional<RepairUnit> repairUnit =
        context.storage.getRepairUnit(repairSchedule.getRepairUnitId());
    assert repairUnit.isPresent() : "no repair unit found with id: "
                                    + repairSchedule.getRepairUnitId();
    return new RepairScheduleStatus(repairSchedule, repairUnit.get());
  }

  /**
   * Crafts an URI used to identify given repair schedule.
   *
   * @return The created resource URI.
   */
  private URI buildRepairScheduleURI(UriInfo uriInfo, RepairSchedule repairSchedule) {
    String newRepairSchedulePathPart = "repair_schedule/" + repairSchedule.getId();
    URI scheduleUri = null;
    try {
      scheduleUri = new URL(uriInfo.getBaseUri().toURL(), newRepairSchedulePathPart).toURI();
    } catch (MalformedURLException | URISyntaxException e) {
      LOG.error(e.getMessage());
      e.printStackTrace();
    }
    checkNotNull(scheduleUri, "failed to build repair schedule uri");
    return scheduleUri;
  }

  /**
   * @param clusterName  The cluster name to list the schedules for. If not given,
   *                     will list all schedules for all clusters.
   * @param keyspaceName The keyspace name to list schedules for. Limits the returned list
   *                     and works whether the cluster name is given or not.
   * @return All schedules in the system.
   */
  @GET
  public Response listSchedules(@QueryParam("clusterName") Optional<String> clusterName,
      @QueryParam("keyspace") Optional<String> keyspaceName) {
    List<RepairScheduleStatus> scheduleStatuses = Lists.newArrayList();
    Collection<RepairSchedule> schedules = getScheduleList(clusterName, keyspaceName);
    for (RepairSchedule schedule : schedules) {
      Optional<RepairUnit> unit = context.storage.getRepairUnit(schedule.getRepairUnitId());
      if (unit.isPresent()) {
        scheduleStatuses.add(new RepairScheduleStatus(schedule, unit.get()));
      } else {
        String errMsg = String.format(
            "Found repair schedule %d with no associated repair unit", schedule.getId());
        LOG.error(errMsg);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
      }
    }
    return Response.status(Response.Status.OK).entity(scheduleStatuses).build();
  }

  private Collection<RepairSchedule> getScheduleList(Optional<String> clusterName,
      Optional<String> keyspaceName) {
    Collection<RepairSchedule> schedules;
    if (clusterName.isPresent() && keyspaceName.isPresent()) {
      schedules = context.storage.getRepairSchedulesForClusterAndKeyspace(clusterName.get(),
          keyspaceName.get());
    } else if (clusterName.isPresent()) {
      schedules = context.storage.getRepairSchedulesForCluster(clusterName.get());
    } else if (keyspaceName.isPresent()) {
      schedules = context.storage.getRepairSchedulesForKeyspace(keyspaceName.get());
    } else {
      schedules = context.storage.getAllRepairSchedules();
    }
    return schedules;
  }

  /**
   * Delete a RepairSchedule object with given id.
   *
   * Repair schedule can only be deleted when it is not active, so you must stop it first.
   *
   * @param repairScheduleId The id for the RepairSchedule instance to delete.
   * @param owner            The assigned owner of the deleted resource. Must match the stored one.
   * @return The deleted RepairSchedule instance, with state overwritten to string "DELETED".
   */
  @DELETE
  @Path("/{id}")
  public Response deleteRepairSchedule(@PathParam("id") Long repairScheduleId,
      @QueryParam("owner") Optional<String> owner) {
    LOG.info("delete repair schedule called with repairScheduleId: {}, and owner: {}",
        repairScheduleId, owner);
    if (!owner.isPresent()) {
      return Response.status(Response.Status.BAD_REQUEST).entity(
          "required query parameter \"owner\" is missing").build();
    }
    Optional<RepairSchedule> scheduleToDelete = context.storage.getRepairSchedule(repairScheduleId);
    if (!scheduleToDelete.isPresent()) {
      return Response.status(Response.Status.NOT_FOUND).entity(
          "Repair schedule with id \"" + repairScheduleId + "\" not found").build();
    }
    if (scheduleToDelete.get().getState() == RepairSchedule.State.ACTIVE) {
      return Response.status(Response.Status.FORBIDDEN).entity(
          "Repair schedule with id \"" + repairScheduleId
          + "\" is currently running, and must be stopped before deleting").build();
    }
    if (!scheduleToDelete.get().getOwner().equalsIgnoreCase(owner.get())) {
      return Response.status(Response.Status.FORBIDDEN).entity(
          "Repair schedule with id \"" + repairScheduleId
          + "\" is not owned by the user you defined: " + owner.get()).build();
    }
    // Need to get the RepairUnit before it's possibly deleted.
    Optional<RepairUnit> possiblyDeletedUnit =
        context.storage.getRepairUnit(scheduleToDelete.get().getRepairUnitId());
    Optional<RepairSchedule> deletedSchedule =
        context.storage.deleteRepairSchedule(repairScheduleId);
    if (deletedSchedule.isPresent()) {
      RepairScheduleStatus scheduleStatus = new RepairScheduleStatus(deletedSchedule.get(),
          possiblyDeletedUnit.get());
      return Response.ok().entity(scheduleStatus).build();
    }
    return Response.serverError().entity("delete failed for schedule with id \""
                                         + repairScheduleId + "\"").build();
  }

}
