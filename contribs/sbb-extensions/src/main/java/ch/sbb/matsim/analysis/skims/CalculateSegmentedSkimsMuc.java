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

import ch.sbb.matsim.analysis.skims.ScheduleCheckUtils.Issue;
import ch.sbb.matsim.routing.pt.raptor.RaptorParameters;
import ch.sbb.matsim.routing.pt.raptor.RaptorStaticConfig;
import ch.sbb.matsim.routing.pt.raptor.RaptorUtils;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.misc.Time;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Calculates the segmented PT skims (bus, bus + tram/metro, bus + tram/metro + rail) and a walk skim for Munich–Bavaria.
 * <p>
 * See {@code contribs/sbb-extensions/docs/skims_muc_plan.md}. All inputs in {@link #main(String[])} are placeholders.
 */
public final class CalculateSegmentedSkimsMuc {

	private static final Logger log = LogManager.getLogger(CalculateSegmentedSkimsMuc.class);

	public static final String WALK_TIME_FILENAME = "walk_time.csv.gz";
	public static final String WALK_DISTANCE_FILENAME = "walk_dist.csv.gz";

	/** All modes expected in the schedules; anything else is reported by the schedule check. */
	public static final Set<String> KNOWN_PT_MODES = Set.of("bus", "ferry", "tram", "subway", "rail");

	/** One PT skim: output prefix, its own network and schedule file, and the main modes. */
	public record SkimDefinition(String prefix, String networkFilename, String scheduleFilename, Set<String> mainModes) {
	}

	private final String outputDirectory;
	private final int numberOfThreads;
	private final Config config;
	private final Map<String, Coord[]> coordsPerZone;

	public CalculateSegmentedSkimsMuc(String outputDirectory, int numberOfThreads, Config config, Map<String, Coord[]> coordsPerZone) {
		this.outputDirectory = outputDirectory;
		this.numberOfThreads = numberOfThreads;
		this.config = config;
		this.coordsPerZone = coordsPerZone;
	}

	public static void main(String[] args) throws IOException {
		// ---------------------------------------------------------------- inputs (placeholders)
		String zonesShapeFilename = "Z://projects//2022//fabilut//zones//zones_Munich-Bavaria_size_5452_popCap_5000_crs_31468.shp";
		String zonesIdAttributeName = "TAZ_ID";
		// network whose nodes are used to draw the sampling points (e.g. the car network)
		String samplingPointsNetworkFilename = "Z://projects//2022//fabilut//network//matsim_network_ptSchedule_forSkimCalculation//munichBavaria_car//network_Munich-Bavaria_car_motorway2tertiary_intensed_connectors.xml.gz";
		// existing zone_coordinates.csv to reuse; null = draw new points and write them to the output directory
		String samplingPointsFilename = "C://models//matsim-libs//contribs//sbb-extensions//src//project//input//zone_coordinates_muc_5452.csv"; //"Z://projects//2022//fabilut//zones//centroids_Munich-Bavaria_size_5452_popCap_5000_crs_31468_test_3nodes.csv"

		// one schedule per skim, already containing all modes of that skim: bus | bus + tram/metro | bus + tram/metro + rail
		String scheduleSkimBus = "C:\\models\\matsim-libs\\contribs\\sbb-extensions\\src\\project\\input\\munichBavaria\\bus//munichBavaria_schedule_bus_mapped.xml.gz";
		String scheduleSkimBusTramMetro = "C:\\models\\matsim-libs\\contribs\\sbb-extensions\\src\\project\\input\\munichBavaria\\bus_metroTram//munichBavaria_schedule_bus_metroTram_mapped.xml.gz";
		String scheduleSkimAll = "C:\\models\\matsim-libs\\contribs\\sbb-extensions\\src\\project\\input\\munichBavaria\\bus_metroTram_rail//munichBavaria_schedule_bus_metroTram_rail_mapped.xml.gz";

		// one network per skim; each must contain all links referenced by that skim's schedule
		String networkSkimBus = "C:\\models\\matsim-libs\\contribs\\sbb-extensions\\src\\project\\input\\munichBavaria\\bus//network_Munich-Bavaria_multimodal_motorway2tertiary_bus.xml.gz";
		String networkSkimBusTramMetro = "C:\\models\\matsim-libs\\contribs\\sbb-extensions\\src\\project\\input\\munichBavaria\\bus_metroTram//network_Munich-Bavaria_multimodal_motorway2tertiary_bus_metroTram.xml.gz";
		String networkSkimAll = "C:\\models\\matsim-libs\\contribs\\sbb-extensions\\src\\project\\input\\munichBavaria\\bus_metroTram_rail//network_Munich-Bavaria_multimodal_motorway2tertiary_bus_metroTram_rail.xml.gz";

		// car skim
		boolean calculateCarSkim = true;
		String networkSkimCar = "Z://projects//2022//fabilut//network//matsim_network_ptSchedule_forSkimCalculation//munichBavaria_car//network_Munich-Bavaria_car_motorway2tertiary_intensed_connectors.xml.gz";
		// MATSim events with congested travel times; null = free-flow speed (then only the first departure time is used)
		String eventsFilenameCar = null;
		// departure times; with events, the matrices are averaged over these times
		String[] carDepartureTimes = {"07:30:00", "08:00:00", "08:30:00"};

		String outputDirectory = "C://models//matsim-libs//contribs//sbb-extensions//src//project//output//skims_muc";
		int numberOfPointsPerZone = 3;
		int numberOfThreads = 12;
		double startTime = Time.parseTime("07:30:00");
		double endTime = Time.parseTime("08:30:00"); // must be startTime + 3600
		boolean warnOnlyOnMissingLinks = false;
		// ---------------------------------------------------------------- end of inputs

		Config config = createConfig();

		// sampling points: same file for all skims
		CalculateSkimMatrices sampler = new CalculateSkimMatrices(outputDirectory, numberOfThreads);
		if (samplingPointsFilename != null) {
			sampler.loadSamplingPointsFromFile(samplingPointsFilename);
		} else {
			sampler.calculateSamplingPointsPerZoneFromNetwork(samplingPointsNetworkFilename, numberOfPointsPerZone, zonesShapeFilename, zonesIdAttributeName, new Random(4711));
			sampler.writeSamplingPointsToFile(new File(outputDirectory, CalculateSkimMatrices.ZONE_LOCATIONS_FILENAME));
		}

		CalculateSegmentedSkimsMuc skims = new CalculateSegmentedSkimsMuc(outputDirectory, numberOfThreads, config, sampler.getCoordsPerZone());

		List<SkimDefinition> definitions = List.of(
				new SkimDefinition("bus_", networkSkimBus, scheduleSkimBus, Set.of("bus", "ferry")),
				new SkimDefinition("btm_", networkSkimBusTramMetro, scheduleSkimBusTramMetro, Set.of("tram", "subway")),
				new SkimDefinition("all_", networkSkimAll, scheduleSkimAll, Set.of("rail"))
		);

		for (SkimDefinition definition : definitions) {
			skims.calculateAndWritePtSkim(definition, startTime, endTime, warnOnlyOnMissingLinks);
		}

		skims.calculateAndWriteWalkSkim();

		if (calculateCarSkim) {
			calculateAndWriteCarSkim(sampler, networkSkimCar, eventsFilenameCar, carDepartureTimes, config);
		}
	}

	/**
	 * Car skim with the same sampling points as the PT skims, using {@link CalculateSkimMatrices}. Writes
	 * {@code car_travel_times.csv.gz} (s) and {@code car_distances.csv.gz} (m). Sampling points are connected to the nearest
	 * link that allows car. Without events, free-flow speeds are used.
	 */
	public static void calculateAndWriteCarSkim(CalculateSkimMatrices sampler, String networkFilename, String eventsFilename,
			String[] departureTimes, Config config) throws IOException {
		log.info("==== car skim (" + (eventsFilename == null ? "free flow" : "events " + eventsFilename) + ")");
		double[] times = new double[departureTimes.length];
		for (int i = 0; i < departureTimes.length; i++) {
			times[i] = Time.parseTime(departureTimes[i]);
		}
		sampler.calculateAndWriteNetworkMatrices(networkFilename, eventsFilename, times, config, "",
				link -> link.getAllowedModes().contains(TransportMode.car));
	}

	/**
	 * Default MATSim config, plus the transfer walk radius from the plan. Walk speed (3 km/h, {@code non_network_walk}) and
	 * detour factor (1.3) stay at their defaults.
	 */
	public static Config createConfig() {
		Config config = ConfigUtils.createConfig();
		config.transitRouter().setMaxBeelineWalkConnectionDistance(500.0);
		return config;
	}

	public void calculateAndWritePtSkim(SkimDefinition definition, double startTime, double endTime, boolean warnOnlyOnMissingLinks) throws IOException {
		log.info("==== PT skim " + definition.prefix() + " (main modes " + definition.mainModes() + ")");

		// schedule + network (stops without any route are ignored by the router)
		Scenario scenario = ScenarioUtils.createScenario(this.config);
		log.info("loading schedule " + definition.scheduleFilename());
		new TransitScheduleReader(scenario).readFile(definition.scheduleFilename());
		log.info("loading network " + definition.networkFilename());
		new MatsimNetworkReader(scenario.getNetwork()).readFile(definition.networkFilename());
		TransitSchedule schedule = scenario.getTransitSchedule();

		// checks
		List<Issue> issues = new ArrayList<>();
		issues.addAll(ScheduleCheckUtils.checkLinks(schedule, scenario.getNetwork()));
		issues.addAll(ScheduleCheckUtils.checkModes(schedule, KNOWN_PT_MODES));
		ScheduleCheckUtils.writeReport(issues, this.outputDirectory + "/schedule_check_" + definition.prefix() + ".csv");
		ScheduleCheckUtils.logSummary(definition.prefix(), issues);
		long errors = ScheduleCheckUtils.countErrors(issues);
		if (errors > 0) {
			String msg = errors + " schedule/network errors for skim " + definition.prefix() + ", see schedule_check_" + definition.prefix() + ".csv";
			if (warnOnlyOnMissingLinks) {
				log.warn(msg + " (continuing, warnOnly is set)");
			} else {
				throw new RuntimeException(msg);
			}
		}

		// router
		RaptorStaticConfig raptorConfig = RaptorUtils.createStaticConfig(this.config);
		raptorConfig.setOptimization(RaptorStaticConfig.RaptorOptimization.OneToAllRouting);
		SwissRailRaptorData raptorData = SwissRailRaptorData.create(schedule, scenario.getTransitVehicles(), raptorConfig, scenario.getNetwork(), null);
		RaptorParameters raptorParameters = RaptorUtils.createParameters(this.config);

		Set<String> mainModes = definition.mainModes();
		PTSkimMatricesSegmented.SegmentedPtIndicators<String> indicators = PTSkimMatricesSegmented.calculateSkimMatrices(
				raptorData, this.coordsPerZone, startTime, endTime, 120, raptorParameters, raptorConfig.getBeelineWalkDistanceFactor(),
				this.numberOfThreads,
				(line, route) -> route.getTransportMode() != null && mainModes.contains(route.getTransportMode().toLowerCase(Locale.ROOT)),
				new PTSkimMatrices.CoordAggregator() {
				});

		PTSkimMatricesSegmented.writeAsCSV(indicators, this.outputDirectory, definition.prefix());
	}

	/**
	 * Beeline distances plus walk skim: {@code walk_dist = beeline * factor}, {@code walk_time = walk_dist / speed}, with the same
	 * walk parameters as the PT router.
	 */
	public void calculateAndWriteWalkSkim() throws IOException {
		RaptorStaticConfig raptorConfig = RaptorUtils.createStaticConfig(this.config);
		double factor = raptorConfig.getBeelineWalkDistanceFactor();
		double beelineWalkSpeed = raptorConfig.getBeelineWalkSpeed(); // = speed / factor

		log.info("calc beeline and walk matrices (factor " + factor + ", speed " + (beelineWalkSpeed * factor) + " m/s)");
		FloatMatrix<String> beeline = BeelineDistanceMatrix.calculateBeelineDistanceMatrix(this.coordsPerZone.keySet(), this.coordsPerZone, this.numberOfThreads);
		FloatMatrix<String> walkDist = FloatMatrix.createFloatMatrix(this.coordsPerZone.keySet(), 0);
		FloatMatrix<String> walkTime = FloatMatrix.createFloatMatrix(this.coordsPerZone.keySet(), 0);
		for (String from : this.coordsPerZone.keySet()) {
			for (String to : this.coordsPerZone.keySet()) {
				float b = beeline.get(from, to);
				walkDist.set(from, to, (float) (b * factor));
				walkTime.set(from, to, (float) (b / beelineWalkSpeed));
			}
		}
		FloatMatrixIO.writeAsCSV(beeline, this.outputDirectory + "/" + CalculateSkimMatrices.BEELINE_DISTANCE_FILENAME);
		FloatMatrixIO.writeAsCSV(walkDist, this.outputDirectory + "/" + WALK_DISTANCE_FILENAME);
		FloatMatrixIO.writeAsCSV(walkTime, this.outputDirectory + "/" + WALK_TIME_FILENAME);
	}

}
