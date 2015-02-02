package com.spotify.reaper.resources;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import com.spotify.reaper.core.Cluster;
import com.spotify.reaper.core.RepairRun;
import com.spotify.reaper.core.RepairSegment;
import com.spotify.reaper.resources.view.Overview;
import com.spotify.reaper.storage.IStorage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Path("/overview")
@Produces(MediaType.APPLICATION_JSON)
public class OverviewResource {

  private static final Logger LOG = LoggerFactory.getLogger(OverviewResource.class);

  private final IStorage storage;

  public OverviewResource(IStorage storage) {
    this.storage = storage;
  }

  @GET
  public Response get() {
    Collection<RepairRun> runs;
    List<Overview> overviews = Lists.newArrayList();
    Collection<Cluster> clusters = storage.getClusters();
    for (Cluster cluster : clusters) {
      runs = storage.getRepairRunsForCluster(cluster.getName());
      for (RepairRun run : runs) {
        int all = storage.getRepairUnit(run.getRepairUnitId()).get().getSegmentCount();
        int done = storage.getSegmentAmountForRepairRun(run.getId(), RepairSegment.State.DONE);
        String status = run.getRunState().name();
        overviews.add(new Overview(cluster.getName(), run.getId(), status, all, done));
      }
    }
    return Response.status(Response.Status.OK).entity(overviews).build();
  }

}
