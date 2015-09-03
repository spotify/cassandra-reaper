package com.spotify.reaper.service;

import java.util.Collection;
import java.util.Timer;
import java.util.TimerTask;

import javax.ws.rs.core.Response;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.spotify.reaper.AppContext;
import com.spotify.reaper.core.RepairRun;
import com.spotify.reaper.core.RepairRun.RunState;
import com.spotify.reaper.resources.RepairRunResource;

public class RepairRunCleaner extends TimerTask {

	private static final Logger LOG = LoggerFactory.getLogger(RepairRunCleaner.class);

	private static TimerTask repairRunCleaner;

	public static void start(AppContext context) {
		if (null == repairRunCleaner) {
			LOG.info("Starting new RepairRunCleaner instance");
			repairRunCleaner = new RepairRunCleaner(context);
			Timer timer = new Timer("RepairRunCleanerTimer");
			timer.schedule(repairRunCleaner, 1000, 1000 * 60 * 60 * 24); // activate once
																		// per day
		} else {
			LOG.warn("there is already one instance of RepairRunCleaner running, not starting new one");
		}
	}

	private AppContext context;

	private RepairRunCleaner(AppContext context) {
		this.context = context;
	}

	@Override
	public void run() {
		LOG.debug("Checking for repair runs that are stopped...");
		try {
			Collection<RepairRun> repairRuns = context.storage.getRepairRunsWithState(RunState.DONE);
			for (RepairRun repairRun : repairRuns) {
				if(repairRun.getEndTime().isBefore(DateTime.now().plusDays(repairRun.getDaysToExpireAfterDone()))) {
					RepairRunResource repairRunResource = new RepairRunResource(context);
					Response deleteResponce = repairRunResource.deleteRepairRun(repairRun.getId(), Optional.of(repairRun.getOwner()));
					if (deleteResponce.getStatus() != Response.Status.OK.getStatusCode()) {
						LOG.error("Cannot delete expired repair run with ID {} with error message {}", repairRun.getId(), deleteResponce.getEntity().toString());
					}
				}
			}
		} catch (Exception ex) {
			LOG.error("Exception caught in RepairRunCleaner");
			LOG.error("catch exception", ex);
		}
	}

}
