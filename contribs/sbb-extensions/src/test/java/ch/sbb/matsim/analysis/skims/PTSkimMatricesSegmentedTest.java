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

import ch.sbb.matsim.analysis.skims.PTSkimMatricesSegmented.Indicator;
import ch.sbb.matsim.analysis.skims.PTSkimMatricesSegmented.SegmentedPtIndicators;
import ch.sbb.matsim.analysis.skims.ScheduleCheckUtils.Issue;
import ch.sbb.matsim.routing.pt.raptor.RaptorParameters;
import ch.sbb.matsim.routing.pt.raptor.RaptorStaticConfig;
import ch.sbb.matsim.routing.pt.raptor.RaptorUtils;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorData;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Synthetic corridor along the x-axis:
 * <pre>
 *  Z1 . B1 ==busA(5 km, 10 min)== B2 ~100 m~ R1 ==rail(15 km, 10 min)== R2 ~100 m~ B3 ==busC(5 km, 10 min)== B4 . Z2
 *                                   Z3 (next to B2 and R1)
 * </pre>
 * All lines run every 10 minutes and only in the +x direction. Timetables are coordinated, so every transfer
 * (100 m walk = 156 s) is followed by a wait of 144 s.
 */
public class PTSkimMatricesSegmentedTest {

	private static final double WALK_SPEED_BEELINE = (3.0 / 3.6) / 1.3; // default non_network_walk speed / factor
	private static final double WALK_100M_TIME = 100 / WALK_SPEED_BEELINE;
	private static final double WALK_100M_DIST = 100 * 1.3;
	private static final double T0 = 7.5 * 3600;
	private static final double T1 = 8.5 * 3600;

	private Config config;
	private Network network;
	private TransitSchedule schedule;
	private Map<String, Coord[]> coordsPerZone;

	@BeforeEach
	void setUp() {
		this.config = ConfigUtils.createConfig();
		this.config.transitRouter().setMaxBeelineWalkConnectionDistance(500.0);

		this.network = NetworkUtils.createNetwork(this.config);
		addLink("busA_start", -10, 0, 0, 10);
		addLink("busA", 0, 5000, 0, 5000);
		addLink("rail_start", 5090, 5100, 0, 10);
		addLink("rail", 5100, 20100, 0, 15000);
		addLink("busC_start", 20190, 20200, 0, 10);
		addLink("busC", 20200, 25200, 0, 5000);

		this.schedule = ScenarioUtils.createScenario(this.config).getTransitSchedule();
		addLine(this.schedule, "A", "bus", "B1", new Coord(0, 0), "busA_start", "B2", new Coord(5000, 0), "busA", 0);
		addLine(this.schedule, "C", "bus", "B3", new Coord(20200, 0), "busC_start", "B4", new Coord(25200, 0), "busC", 0);
		// rail departs at :05, :15, ... -> bus A arrives at :10, walk 156 s, wait 144 s
		addLine(this.schedule, "R", "rail", "R1", new Coord(5100, 0), "rail_start", "R2", new Coord(20100, 0), "rail", 300);

		this.coordsPerZone = new LinkedHashMap<>();
		this.coordsPerZone.put("Z1", new Coord[]{new Coord(0, 100)});
		this.coordsPerZone.put("Z2", new Coord[]{new Coord(25200, 100)});
		this.coordsPerZone.put("Z3", new Coord[]{new Coord(5000, 100)});
	}

	private void addLink(String id, double x0, double x1, double y, double length) {
		NetworkFactory nf = this.network.getFactory();
		Node from = this.network.getNodes().get(Id.createNodeId(id + "_from"));
		Node to = this.network.getNodes().get(Id.createNodeId(id + "_to"));
		if (from == null) {
			from = nf.createNode(Id.createNodeId(id + "_from"), new Coord(x0, y));
			this.network.addNode(from);
		}
		if (to == null) {
			to = nf.createNode(Id.createNodeId(id + "_to"), new Coord(x1, y));
			this.network.addNode(to);
		}
		Link link = nf.createLink(Id.createLinkId(id), from, to);
		link.setLength(length);
		link.setFreespeed(30);
		link.setAllowedModes(Set.of("pt"));
		this.network.addLink(link);
	}

	private static void addLine(TransitSchedule schedule, String lineId, String mode, String stop1, Coord c1, String link1, String stop2, Coord c2, String link2,
			double departureOffset) {
		TransitScheduleFactory f = schedule.getFactory();
		TransitStopFacility s1 = f.createTransitStopFacility(Id.create(stop1, TransitStopFacility.class), c1, false);
		s1.setLinkId(Id.createLinkId(link1));
		TransitStopFacility s2 = f.createTransitStopFacility(Id.create(stop2, TransitStopFacility.class), c2, false);
		s2.setLinkId(Id.createLinkId(link2));
		schedule.addStopFacility(s1);
		schedule.addStopFacility(s2);

		List<TransitRouteStop> stops = List.of(f.createTransitRouteStop(s1, 0, 0), f.createTransitRouteStop(s2, 600, 600));
		TransitRoute route = f.createTransitRoute(Id.create("r", TransitRoute.class),
				RouteUtils.createLinkNetworkRouteImpl(Id.createLinkId(link1), Id.createLinkId(link2)), stops, mode);
		for (int i = 0; i < 40; i++) {
			route.addDeparture(f.createDeparture(Id.create(i, Departure.class), 5 * 3600 + departureOffset + i * 600));
		}
		TransitLine line = f.createTransitLine(Id.create(lineId, TransitLine.class));
		line.addRoute(route);
		schedule.addTransitLine(line);
	}

	private SegmentedPtIndicators<String> run(Set<String> mainModes) {
		Scenario scenario = ScenarioUtils.createScenario(this.config);
		RaptorStaticConfig raptorConfig = RaptorUtils.createStaticConfig(this.config);
		raptorConfig.setOptimization(RaptorStaticConfig.RaptorOptimization.OneToAllRouting);
		SwissRailRaptorData data = SwissRailRaptorData.create(this.schedule,scenario.getTransitVehicles(), raptorConfig, this.network, null);
		RaptorParameters parameters = RaptorUtils.createParameters(this.config);
		return PTSkimMatricesSegmented.calculateSkimMatrices(data, this.coordsPerZone, T0, T1, 120, parameters,
				raptorConfig.getBeelineWalkDistanceFactor(), 2,
				(line, route) -> mainModes.contains(route.getTransportMode()),
				new PTSkimMatrices.CoordAggregator() {
				});
	}

	private static float v(SegmentedPtIndicators<String> pti, Indicator ind, String from, String to) {
		return pti.get(ind).get(from, to);
	}

	@Test
	void busRailBus_railIsMain() {
		SegmentedPtIndicators<String> pti = run(Set.of("rail"));
		double eps = 1.0;

		Assertions.assertEquals(WALK_100M_TIME, v(pti, Indicator.WALK_ACCESS_TIME, "Z1", "Z2"), eps);
		Assertions.assertEquals(WALK_100M_DIST, v(pti, Indicator.WALK_ACCESS_DIST, "Z1", "Z2"), eps);
		Assertions.assertEquals(WALK_100M_TIME, v(pti, Indicator.WALK_EGRESS_TIME, "Z1", "Z2"), eps);
		Assertions.assertEquals(WALK_100M_DIST, v(pti, Indicator.WALK_EGRESS_DIST, "Z1", "Z2"), eps);

		Assertions.assertEquals(600, v(pti, Indicator.PT_ACCESS_IVT, "Z1", "Z2"), eps);
		Assertions.assertEquals(5000, v(pti, Indicator.PT_ACCESS_DIST, "Z1", "Z2"), eps);
		Assertions.assertEquals(600, v(pti, Indicator.PT_MAIN_IVT, "Z1", "Z2"), eps);
		Assertions.assertEquals(15000, v(pti, Indicator.PT_MAIN_DIST, "Z1", "Z2"), eps);
		Assertions.assertEquals(600, v(pti, Indicator.PT_EGRESS_IVT, "Z1", "Z2"), eps);
		Assertions.assertEquals(5000, v(pti, Indicator.PT_EGRESS_DIST, "Z1", "Z2"), eps);

		Assertions.assertEquals(2 * WALK_100M_TIME, v(pti, Indicator.PT_TRANSFER_WALK_TIME, "Z1", "Z2"), eps);
		Assertions.assertEquals(2 * WALK_100M_DIST, v(pti, Indicator.PT_TRANSFER_WALK_DIST, "Z1", "Z2"), eps);
		Assertions.assertEquals(2 * (300 - WALK_100M_TIME), v(pti, Indicator.PT_TRANSFER_WAIT_TIME, "Z1", "Z2"), eps);
		Assertions.assertEquals(2, v(pti, Indicator.PT_TRANSFER_COUNT, "Z1", "Z2"), 1e-3);

		Assertions.assertEquals(1800, v(pti, Indicator.PT_IVT_TOTAL, "Z1", "Z2"), eps);
		Assertions.assertEquals(25000, v(pti, Indicator.PT_IVD_TOTAL, "Z1", "Z2"), eps);
		Assertions.assertEquals(2 * WALK_100M_TIME + 1800 + 600, v(pti, Indicator.TOTAL_TRAVEL_TIME, "Z1", "Z2"), eps);
		Assertions.assertEquals(4 * WALK_100M_DIST + 25000, v(pti, Indicator.TOTAL_DISTANCE, "Z1", "Z2"), eps);
		Assertions.assertEquals(1.0 / 3.0, v(pti, Indicator.MAIN_SHARE_TIME, "Z1", "Z2"), 1e-3);
		Assertions.assertEquals(15000.0 / 25000.0, v(pti, Indicator.MAIN_SHARE_DIST, "Z1", "Z2"), 1e-3);

		Assertions.assertEquals(1.0, v(pti, Indicator.MAIN_MODE_AVAILABILITY, "Z1", "Z2"), 1e-3);
		Assertions.assertEquals(6.0, v(pti, Indicator.PT_FREQUENCY, "Z1", "Z2"), 0.1); // every 10 min
	}

	@Test
	void busOnly_noMainMode_isInfinity() {
		SegmentedPtIndicators<String> pti = run(Set.of("rail"));
		// Z1 -> Z3 is served by bus A only
		for (Indicator ind : Indicator.values()) {
			float expected = switch (ind) {
				case PT_FREQUENCY, MAIN_MODE_AVAILABILITY -> 0f;
				default -> Float.POSITIVE_INFINITY;
			};
			Assertions.assertEquals(expected, v(pti, ind, "Z1", "Z3"), ind.name());
		}
	}

	@Test
	void feederBetweenMainLegs_countsAsMain() {
		// bus as main: bus - rail - bus is main from the first to the last bus leg, including the rail leg
		SegmentedPtIndicators<String> pti = run(Set.of("bus"));
		Assertions.assertEquals(0, v(pti, Indicator.PT_ACCESS_IVT, "Z1", "Z2"), 1e-3);
		Assertions.assertEquals(0, v(pti, Indicator.PT_EGRESS_IVT, "Z1", "Z2"), 1e-3);
		Assertions.assertEquals(1800, v(pti, Indicator.PT_MAIN_IVT, "Z1", "Z2"), 1.0);
		Assertions.assertEquals(1.0, v(pti, Indicator.MAIN_SHARE_TIME, "Z1", "Z2"), 1e-6);
		Assertions.assertEquals(1.0, v(pti, Indicator.MAIN_SHARE_DIST, "Z1", "Z2"), 1e-6);

		// Z1 -> Z3: single bus leg
		Assertions.assertEquals(600, v(pti, Indicator.PT_MAIN_IVT, "Z1", "Z3"), 1.0);
		Assertions.assertEquals(0, v(pti, Indicator.PT_TRANSFER_COUNT, "Z1", "Z3"), 1e-3);
		Assertions.assertEquals(0, v(pti, Indicator.PT_TRANSFER_WAIT_TIME, "Z1", "Z3"), 1e-3);
	}

	@Test
	void unreachableAndIntrazonal_areInfinity() {
		SegmentedPtIndicators<String> pti = run(Set.of("bus"));
		// lines only run in +x direction
		Assertions.assertEquals(Float.POSITIVE_INFINITY, v(pti, Indicator.TOTAL_TRAVEL_TIME, "Z2", "Z1"));
		Assertions.assertEquals(0f, v(pti, Indicator.PT_FREQUENCY, "Z2", "Z1"));
		// intrazonal: walk only
		Assertions.assertEquals(Float.POSITIVE_INFINITY, v(pti, Indicator.TOTAL_TRAVEL_TIME, "Z1", "Z1"));
	}

	@Test
	void noMainModeInSchedule_allInfinity() {
		SegmentedPtIndicators<String> pti = run(Set.of("tram"));
		Assertions.assertEquals(Float.POSITIVE_INFINITY, v(pti, Indicator.PT_MAIN_IVT, "Z1", "Z2"));
		Assertions.assertEquals(0f, v(pti, Indicator.MAIN_MODE_AVAILABILITY, "Z1", "Z2"));
	}

	@Test
	void scheduleChecks() {
		Assertions.assertEquals(0, ScheduleCheckUtils.countErrors(ScheduleCheckUtils.checkLinks(this.schedule, this.network)));
		this.network.removeLink(Id.createLinkId("rail"));
		List<Issue> issues = ScheduleCheckUtils.checkLinks(this.schedule, this.network);
		Assertions.assertEquals(2, ScheduleCheckUtils.countErrors(issues)); // stop R2 and the rail route

		Assertions.assertTrue(ScheduleCheckUtils.checkModes(this.schedule, Set.of("bus", "rail")).isEmpty());
		Assertions.assertEquals(1, ScheduleCheckUtils.checkModes(this.schedule, Set.of("bus")).size()); // the rail route
	}
}
