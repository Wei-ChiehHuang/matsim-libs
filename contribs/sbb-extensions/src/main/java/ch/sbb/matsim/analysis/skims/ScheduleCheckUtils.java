/* *********************************************************************** *
 * project: org.matsim.*
 *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2026 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */
package ch.sbb.matsim.analysis.skims;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Consistency checks of a transit schedule against the network used for a skim.
 * <p>
 * See section 6 of {@code contribs/sbb-extensions/docs/skims_muc_plan.md}.
 */
public final class ScheduleCheckUtils {

	private static final Logger log = LogManager.getLogger(ScheduleCheckUtils.class);

	public static final String SEVERITY_ERROR = "ERROR";
	public static final String SEVERITY_WARN = "WARN";

	private ScheduleCheckUtils() {
	}

	/** One finding of the checks. */
	public record Issue(String check, String severity, String id, String detail) {
	}

	/**
	 * Checks that all links referenced by stops and routes exist in the network. Missing links are reported as ERROR, because
	 * in-vehicle distances are computed from link lengths.
	 */
	public static List<Issue> checkLinks(TransitSchedule schedule, Network network) {
		List<Issue> issues = new ArrayList<>();
		Map<Id<Link>, ? extends Link> links = network.getLinks();
		for (TransitStopFacility stop : schedule.getFacilities().values()) {
			if (stop.getLinkId() == null) {
				issues.add(new Issue("STOP_WITHOUT_LINK", SEVERITY_WARN, stop.getId().toString(), "stop has no link reference"));
			} else if (!links.containsKey(stop.getLinkId())) {
				issues.add(new Issue("STOP_LINK_MISSING", SEVERITY_ERROR, stop.getId().toString(), "link " + stop.getLinkId() + " not in network"));
			}
		}
		for (TransitLine line : schedule.getTransitLines().values()) {
			for (TransitRoute route : line.getRoutes().values()) {
				String routeKey = line.getId() + "|" + route.getId();
				NetworkRoute nr = route.getRoute();
				if (nr == null) {
					issues.add(new Issue("ROUTE_WITHOUT_NETWORK_ROUTE", SEVERITY_ERROR, routeKey, "route has no network route, distances cannot be computed"));
					continue;
				}
				List<Id<Link>> routeLinks = new ArrayList<>();
				routeLinks.add(nr.getStartLinkId());
				routeLinks.addAll(nr.getLinkIds());
				routeLinks.add(nr.getEndLinkId());
				Set<Id<Link>> reported = new HashSet<>();
				for (Id<Link> linkId : routeLinks) {
					if (linkId != null && !links.containsKey(linkId) && reported.add(linkId)) {
						issues.add(new Issue("ROUTE_LINK_MISSING", SEVERITY_ERROR, routeKey, "link " + linkId + " not in network"));
					}
				}
			}
		}
		return issues;
	}

	/** Reports routes whose transport mode is not in {@code allowedModes} (case-insensitive) as WARN. */
	public static List<Issue> checkModes(TransitSchedule schedule, Set<String> allowedModes) {
		Set<String> allowed = new HashSet<>();
		allowedModes.forEach(m -> allowed.add(m.toLowerCase()));
		List<Issue> issues = new ArrayList<>();
		for (TransitLine line : schedule.getTransitLines().values()) {
			for (TransitRoute route : line.getRoutes().values()) {
				String mode = route.getTransportMode();
				if (mode == null || !allowed.contains(mode.toLowerCase())) {
					issues.add(new Issue("UNKNOWN_MODE", SEVERITY_WARN, line.getId() + "|" + route.getId(), "mode '" + mode + "'"));
				}
			}
		}
		return issues;
	}

	public static void writeReport(List<Issue> issues, String filename) throws IOException {
		try (BufferedWriter writer = IOUtils.getBufferedWriter(filename)) {
			writer.write("CHECK;SEVERITY;ID;DETAIL\n");
			for (Issue issue : issues) {
				writer.write(issue.check() + ";" + issue.severity() + ";" + issue.id() + ";" + issue.detail().replace(';', ',') + "\n");
			}
		}
	}

	/** Logs a summary (count per check and severity). */
	public static void logSummary(String label, List<Issue> issues) {
		Map<String, Integer> counts = new TreeMap<>();
		for (Issue issue : issues) {
			counts.merge(issue.severity() + " " + issue.check(), 1, Integer::sum);
		}
		log.info("schedule check " + label + ": " + (counts.isEmpty() ? "no issues" : ""));
		counts.forEach((k, v) -> log.info("  " + k + ": " + v));
	}

	public static long countErrors(List<Issue> issues) {
		return issues.stream().filter(i -> SEVERITY_ERROR.equals(i.severity())).count();
	}
}
