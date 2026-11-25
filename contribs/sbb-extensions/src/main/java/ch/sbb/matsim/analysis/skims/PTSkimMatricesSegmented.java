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

import ch.sbb.matsim.analysis.skims.RooftopUtils.ODConnection;
import ch.sbb.matsim.routing.pt.raptor.RaptorParameters;
import ch.sbb.matsim.routing.pt.raptor.RaptorRoute;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptor;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorCore.TravelInfo;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.misc.Counter;
import org.matsim.core.utils.misc.Time;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiPredicate;

/**
 * Calculates zone-to-zone PT skims in which every connection is split into the segments
 * <em>walk access → PT access (feeder) → PT main → PT egress (feeder) → walk egress</em>.
 * <p>
 * The routing and the rooftop logic are the same as in {@link PTSkimMatrices}. The router finds the best connections over
 * all modes in the given schedule. Afterwards, the PT legs of each connection are classified with a <em>main-mode
 * detector</em>:
 * <ul>
 *     <li>PT access: in-vehicle time/distance of all legs before the first main-mode leg</li>
 *     <li>PT main: in-vehicle time/distance from the first to the last main-mode leg, including feeder legs in between</li>
 *     <li>PT egress: in-vehicle time/distance of all legs after the last main-mode leg</li>
 *     <li>transfer walk and transfer wait: all walks and waits between the first and the last PT leg</li>
 * </ul>
 * Connections without any main-mode leg are excluded from the segment values. Their share of the time window is reported
 * through {@link Indicator#MAIN_MODE_AVAILABILITY}. If a zone pair has no main-mode connection at all, all matrices are
 * {@code Infinity}, except frequency and availability (both 0).
 * <p>
 * Adaption time and frequency are calculated over all PT connections (overall service level), like in {@link PTSkimMatrices}.
 * <p>
 * The departure time window must be exactly 3600 seconds, because {@link RooftopUtils} assumes one hour.
 * <p>
 * See {@code contribs/sbb-extensions/docs/skims_muc_plan.md} for the full description.
 */
public final class PTSkimMatricesSegmented {

	private static final Logger log = LogManager.getLogger(PTSkimMatricesSegmented.class);

	private PTSkimMatricesSegmented() {
	}

	/**
	 * All output matrices. The first {@link #ACCUMULATED_COUNT} entries are accumulated per connection and averaged over
	 * the main-mode connections. The others are derived from them or calculated separately.
	 */
	public enum Indicator {
		// accumulated per connection (order matters, see ACCUMULATED_COUNT)
		WALK_ACCESS_TIME("walk_access_time.csv.gz"),
		WALK_ACCESS_DIST("walk_access_dist.csv.gz"),
		WALK_EGRESS_TIME("walk_egress_time.csv.gz"),
		WALK_EGRESS_DIST("walk_egress_dist.csv.gz"),
		PT_ACCESS_IVT("pt_access_ivt.csv.gz"),
		PT_ACCESS_DIST("pt_access_dist.csv.gz"),
		PT_MAIN_IVT("pt_main_ivt.csv.gz"),
		PT_MAIN_DIST("pt_main_dist.csv.gz"),
		PT_EGRESS_IVT("pt_egress_ivt.csv.gz"),
		PT_EGRESS_DIST("pt_egress_dist.csv.gz"),
		PT_TRANSFER_WALK_TIME("pt_transfer_walk_time.csv.gz"),
		PT_TRANSFER_WALK_DIST("pt_transfer_walk_dist.csv.gz"),
		PT_TRANSFER_WAIT_TIME("pt_transfer_wait_time.csv.gz"),
		PT_TRANSFER_COUNT("pt_transfer_count.csv.gz"),
		// derived from the accumulated values
		PT_IVT_TOTAL("pt_ivt_total.csv.gz"),
		PT_IVD_TOTAL("pt_ivd_total.csv.gz"),
		TOTAL_TRAVEL_TIME("total_travel_time.csv.gz"),
		TOTAL_DISTANCE("total_distance.csv.gz"),
		MAIN_SHARE_TIME("main_share_time.csv.gz"),
		MAIN_SHARE_DIST("main_share_dist.csv.gz"),
		// service level
		MAIN_MODE_AVAILABILITY("main_mode_availability.csv.gz"),
		PT_ADAPTION_TIME("pt_adaptiontime.csv.gz"),
		PT_FREQUENCY("pt_frequency.csv.gz");

		public static final int ACCUMULATED_COUNT = 14;

		public final String fileName;

		Indicator(String fileName) {
			this.fileName = fileName;
		}
	}

	public static <T> SegmentedPtIndicators<T> calculateSkimMatrices(SwissRailRaptorData raptorData, Map<T, Coord[]> coordsPerZone,
			double minDepartureTime, double maxDepartureTime, double stepSize_seconds, RaptorParameters parameters,
			double walkBeelineDistanceFactor, int numberOfThreads, BiPredicate<TransitLine, TransitRoute> mainModeDetector,
			PTSkimMatrices.CoordAggregator coordAggregator) {

		double timeWindow = maxDepartureTime - minDepartureTime;
		if (Math.abs(timeWindow - 3600.0) > 1e-6) {
			throw new IllegalArgumentException("The departure time window must be exactly 3600 seconds, as RooftopUtils assumes one hour. Got "
					+ Time.writeTime(minDepartureTime) + " - " + Time.writeTime(maxDepartureTime));
		}

		Set<T> zoneIds = coordsPerZone.keySet();
		SegmentedPtIndicators<T> pti = new SegmentedPtIndicators<>(zoneIds);
		Config config = ConfigUtils.createConfig();

		ConcurrentLinkedQueue<T> originZones = new ConcurrentLinkedQueue<>(zoneIds);
		String label = "PT-SegmentedMatrix-" + Time.writeTime(minDepartureTime) + "-" + Time.writeTime(maxDepartureTime);
		Counter counter = new Counter(label + " zone ", " / " + coordsPerZone.size());
		Thread[] threads = new Thread[numberOfThreads];
		for (int i = 0; i < numberOfThreads; i++) {
			SwissRailRaptor raptor = new SwissRailRaptor.Builder(raptorData, config).build();
			RowWorker<T> worker = new RowWorker<>(originZones, zoneIds, coordsPerZone, pti, raptor, parameters, minDepartureTime, maxDepartureTime,
					stepSize_seconds, walkBeelineDistanceFactor, counter, mainModeDetector, coordAggregator);
			threads[i] = new Thread(worker, label + "-" + i);
			threads[i].start();
		}

		for (Thread thread : threads) {
			try {
				thread.join();
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		}

		finish(pti, zoneIds, timeWindow);
		return pti;
	}

	private static <T> void finish(SegmentedPtIndicators<T> pti, Set<T> zoneIds, double timeWindow) {
		Indicator[] all = Indicator.values();
		for (T from : zoneIds) {
			for (T to : zoneIds) {
				float pairWeight = pti.pairWeight.get(from, to);
				float mainWeight = pti.mainWeight.get(from, to);
				float ptWeight = pti.ptWeight.get(from, to);

				if (mainWeight <= 0) {
					// no connection that uses the main mode: everything Infinity, frequency and availability 0
					for (Indicator ind : all) {
						pti.get(ind).set(from, to, Float.POSITIVE_INFINITY);
					}
					pti.get(Indicator.PT_FREQUENCY).set(from, to, 0f);
					pti.get(Indicator.MAIN_MODE_AVAILABILITY).set(from, to, 0f);
					continue;
				}

				// segment values: average over main-mode connections
				float avg = 1.0f / mainWeight;
				for (int i = 0; i < Indicator.ACCUMULATED_COUNT; i++) {
					pti.get(all[i]).multiply(from, to, avg);
				}
				float accIvt = pti.get(Indicator.PT_ACCESS_IVT).get(from, to);
				float mainIvt = pti.get(Indicator.PT_MAIN_IVT).get(from, to);
				float egrIvt = pti.get(Indicator.PT_EGRESS_IVT).get(from, to);
				float accIvd = pti.get(Indicator.PT_ACCESS_DIST).get(from, to);
				float mainIvd = pti.get(Indicator.PT_MAIN_DIST).get(from, to);
				float egrIvd = pti.get(Indicator.PT_EGRESS_DIST).get(from, to);
				float ivt = accIvt + mainIvt + egrIvt;
				float ivd = accIvd + mainIvd + egrIvd;

				pti.get(Indicator.PT_IVT_TOTAL).set(from, to, ivt);
				pti.get(Indicator.PT_IVD_TOTAL).set(from, to, ivd);
				pti.get(Indicator.TOTAL_TRAVEL_TIME).set(from, to,
						pti.get(Indicator.WALK_ACCESS_TIME).get(from, to)
								+ ivt
								+ pti.get(Indicator.PT_TRANSFER_WALK_TIME).get(from, to)
								+ pti.get(Indicator.PT_TRANSFER_WAIT_TIME).get(from, to)
								+ pti.get(Indicator.WALK_EGRESS_TIME).get(from, to));
				pti.get(Indicator.TOTAL_DISTANCE).set(from, to,
						pti.get(Indicator.WALK_ACCESS_DIST).get(from, to)
								+ ivd
								+ pti.get(Indicator.PT_TRANSFER_WALK_DIST).get(from, to)
								+ pti.get(Indicator.WALK_EGRESS_DIST).get(from, to));
				// share by segment; a main leg with zero in-vehicle time still means "main only"
				pti.get(Indicator.MAIN_SHARE_TIME).set(from, to, ivt > 0 ? mainIvt / ivt : 1.0f);
				pti.get(Indicator.MAIN_SHARE_DIST).set(from, to, ivd > 0 ? mainIvd / ivd : 1.0f);

				// availability: share of point pairs x wished departure times served by the main mode
				float availabilitySum = pti.get(Indicator.MAIN_MODE_AVAILABILITY).get(from, to);
				pti.get(Indicator.MAIN_MODE_AVAILABILITY).set(from, to, pairWeight > 0 ? Math.min(1.0f, availabilitySum / pairWeight) : 0f);

				// service level over all PT connections
				float adaptionTime = pti.get(Indicator.PT_ADAPTION_TIME).get(from, to) / ptWeight;
				pti.get(Indicator.PT_ADAPTION_TIME).set(from, to, adaptionTime);
				pti.get(Indicator.PT_FREQUENCY).set(from, to, (float) (timeWindow / adaptionTime / 4.0));
			}
		}
	}

	/**
	 * Writes all indicators to {@code outputDirectory/prefix + fileName}.
	 */
	public static <T> void writeAsCSV(SegmentedPtIndicators<T> pti, String outputDirectory, String prefix) throws IOException {
		String p = prefix == null ? "" : prefix;
		log.info("write segmented PT matrices to " + outputDirectory + (p.isEmpty() ? "" : " with prefix " + p));
		for (Indicator ind : Indicator.values()) {
			FloatMatrixIO.writeAsCSV(pti.get(ind), outputDirectory + "/" + p + ind.fileName);
		}
	}

	/**
	 * Segment values of a single connection, indexed like the first {@link Indicator#ACCUMULATED_COUNT} indicators.
	 * Returns {@code null} if the connection has no main-mode leg.
	 */
	static double[] classifyConnection(ODConnection connection, double walkDistancePerSecond, BiPredicate<TransitLine, TransitRoute> mainModeDetector) {
		return classifyRoute(connection.travelInfo.getRaptorRoute().getParts(), connection.accessTime, connection.egressTime, walkDistancePerSecond, mainModeDetector);
	}

	/**
	 * Package-private for testing. {@code parts} are the parts of a {@link RaptorRoute} from the first to the last stop.
	 */
	static double[] classifyRoute(Iterable<RaptorRoute.RoutePart> parts, double accessTime, double egressTime, double walkDistancePerSecond,
			BiPredicate<TransitLine, TransitRoute> mainModeDetector) {
		List<RaptorRoute.RoutePart> partList = new ArrayList<>();
		for (RaptorRoute.RoutePart part : parts) {
			partList.add(part);
		}

		// collect PT legs (a leg includes its chained parts, where the passenger stays seated)
		List<Integer> legPartIndex = new ArrayList<>();
		List<double[]> legValues = new ArrayList<>(); // {ivt, distance, wait, isMain}
		for (int i = 0; i < partList.size(); i++) {
			RaptorRoute.RoutePart part = partList.get(i);
			if (part.line == null) {
				continue;
			}
			double distance = nonNaN(part.distance);
			boolean isMain = mainModeDetector.test(part.line, part.route);
			double lastArrival = part.arrivalTime;
			RaptorRoute.RoutePart chained = part.chainedPart;
			while (chained != null) {
				distance += nonNaN(chained.distance);
				isMain = isMain || mainModeDetector.test(chained.line, chained.route);
				lastArrival = chained.arrivalTime;
				chained = chained.chainedPart;
			}
			double ivt = lastArrival - part.boardingTime;
			double wait = Math.max(0, part.boardingTime - part.depTime);
			legPartIndex.add(i);
			legValues.add(new double[]{ivt, distance, wait, isMain ? 1 : 0});
		}

		int firstMain = -1;
		int lastMain = -1;
		for (int k = 0; k < legValues.size(); k++) {
			if (legValues.get(k)[3] > 0) {
				if (firstMain < 0) {
					firstMain = k;
				}
				lastMain = k;
			}
		}
		if (firstMain < 0) {
			return null;
		}

		double[] v = new double[Indicator.ACCUMULATED_COUNT];
		v[Indicator.WALK_ACCESS_TIME.ordinal()] = accessTime;
		v[Indicator.WALK_ACCESS_DIST.ordinal()] = accessTime * walkDistancePerSecond;
		v[Indicator.WALK_EGRESS_TIME.ordinal()] = egressTime;
		v[Indicator.WALK_EGRESS_DIST.ordinal()] = egressTime * walkDistancePerSecond;

		// walks inside the route: before the first PT leg they extend the access walk, after the last PT leg the egress walk,
		// otherwise they are transfer walks
		int firstLegPart = legPartIndex.getFirst();
		int lastLegPart = legPartIndex.getLast();
		for (int i = 0; i < partList.size(); i++) {
			RaptorRoute.RoutePart part = partList.get(i);
			if (part.line != null) {
				continue;
			}
			double time = nonNaN(part.arrivalTime - part.depTime);
			double distance = nonNaN(part.distance);
			if (i < firstLegPart) {
				v[Indicator.WALK_ACCESS_TIME.ordinal()] += time;
				v[Indicator.WALK_ACCESS_DIST.ordinal()] += distance;
			} else if (i > lastLegPart) {
				v[Indicator.WALK_EGRESS_TIME.ordinal()] += time;
				v[Indicator.WALK_EGRESS_DIST.ordinal()] += distance;
			} else {
				v[Indicator.PT_TRANSFER_WALK_TIME.ordinal()] += time;
				v[Indicator.PT_TRANSFER_WALK_DIST.ordinal()] += distance;
			}
		}

		for (int k = 0; k < legValues.size(); k++) {
			double[] leg = legValues.get(k);
			if (k > 0) {
				// the wait at the first stop is represented by the adaption time, not counted here
				v[Indicator.PT_TRANSFER_WAIT_TIME.ordinal()] += leg[2];
			}
			if (k < firstMain) {
				v[Indicator.PT_ACCESS_IVT.ordinal()] += leg[0];
				v[Indicator.PT_ACCESS_DIST.ordinal()] += leg[1];
			} else if (k > lastMain) {
				v[Indicator.PT_EGRESS_IVT.ordinal()] += leg[0];
				v[Indicator.PT_EGRESS_DIST.ordinal()] += leg[1];
			} else {
				v[Indicator.PT_MAIN_IVT.ordinal()] += leg[0];
				v[Indicator.PT_MAIN_DIST.ordinal()] += leg[1];
			}
		}
		v[Indicator.PT_TRANSFER_COUNT.ordinal()] = legValues.size() - 1;
		return v;
	}

	private static double nonNaN(double value) {
		return Double.isNaN(value) ? 0.0 : value;
	}

	static class RowWorker<T> implements Runnable {

		private final ConcurrentLinkedQueue<T> originZones;
		private final Set<T> destinationZones;
		private final Map<T, Coord[]> coordsPerZone;
		private final SegmentedPtIndicators<T> pti;
		private final SwissRailRaptor raptor;
		private final RaptorParameters parameters;
		private final double minDepartureTime;
		private final double maxDepartureTime;
		private final double stepSize;
		private final double walkDistancePerSecond;
		private final Counter counter;
		private final BiPredicate<TransitLine, TransitRoute> mainModeDetector;
		private final PTSkimMatrices.CoordAggregator coordAggregator;

		RowWorker(ConcurrentLinkedQueue<T> originZones, Set<T> destinationZones, Map<T, Coord[]> coordsPerZone, SegmentedPtIndicators<T> pti,
				SwissRailRaptor raptor, RaptorParameters parameters, double minDepartureTime, double maxDepartureTime, double stepSize,
				double walkBeelineDistanceFactor, Counter counter, BiPredicate<TransitLine, TransitRoute> mainModeDetector,
				PTSkimMatrices.CoordAggregator coordAggregator) {
			this.originZones = originZones;
			this.destinationZones = destinationZones;
			this.coordsPerZone = coordsPerZone;
			this.pti = pti;
			this.raptor = raptor;
			this.parameters = parameters;
			this.minDepartureTime = minDepartureTime;
			this.maxDepartureTime = maxDepartureTime;
			this.stepSize = stepSize;
			// walk time = beeline / beelineWalkSpeed, walk distance = beeline * factor  =>  distance = time * beelineWalkSpeed * factor
			this.walkDistancePerSecond = parameters.getBeelineWalkSpeed() * walkBeelineDistanceFactor;
			this.counter = counter;
			this.mainModeDetector = mainModeDetector;
			this.coordAggregator = coordAggregator;
		}

		private static Collection<TransitStopFacility> findStopCandidates(Coord coord, SwissRailRaptor raptor, RaptorParameters parameters) {
			Collection<TransitStopFacility> stops = raptor.getUnderlyingData().findNearbyStops(coord.getX(), coord.getY(), parameters.getSearchRadius());
			if (stops.isEmpty()) {
				TransitStopFacility nearest = raptor.getUnderlyingData().findNearestStop(coord.getX(), coord.getY());
				double nearestStopDistance = CoordUtils.calcEuclideanDistance(coord, nearest.getCoord());
				stops = raptor.getUnderlyingData().findNearbyStops(coord.getX(), coord.getY(), nearestStopDistance + parameters.getExtensionRadius());
			}
			return stops;
		}

		@Override
		public void run() {
			while (true) {
				T fromZoneId = this.originZones.poll();
				if (fromZoneId == null) {
					return;
				}
				this.counter.incCounter();
				Coord[] fromCoords = this.coordsPerZone.get(fromZoneId);
				if (fromCoords != null) {
					for (CalculateSkimMatrices.WeightedCoord fromCoord : this.coordAggregator.aggregateCoords(fromCoords)) {
						calcForRow(fromZoneId, fromCoord.coord(), fromCoord.weight());
					}
				}
			}
		}

		private void calcForRow(T fromZoneId, Coord fromCoord, double fromCoordWeight) {
			double walkSpeed = this.parameters.getBeelineWalkSpeed();

			Collection<TransitStopFacility> fromStops = findStopCandidates(fromCoord, this.raptor, this.parameters);
			Map<Id<TransitStopFacility>, Double> accessTimes = new HashMap<>();
			for (TransitStopFacility stop : fromStops) {
				double distance = CoordUtils.calcEuclideanDistance(fromCoord, stop.getCoord());
				accessTimes.put(stop.getId(), distance / walkSpeed);
			}

			// trees over [min - window, max + window), see plan section 3.3
			List<Map<Id<TransitStopFacility>, TravelInfo>> trees = new ArrayList<>();
			double timeWindow = this.maxDepartureTime - this.minDepartureTime;
			double endTime = this.maxDepartureTime + timeWindow;
			for (double time = this.minDepartureTime - timeWindow; time < endTime; time += this.stepSize) {
				trees.add(this.raptor.calcTree(fromStops, time, this.parameters, null));
			}

			for (T toZoneId : this.destinationZones) {
				Coord[] toCoords = this.coordsPerZone.get(toZoneId);
				if (toCoords != null) {
					for (Coord toCoord : toCoords) {
						calcForOD(fromZoneId, toZoneId, toCoord, accessTimes, trees, (float) fromCoordWeight);
					}
				}
			}
		}

		private void calcForOD(T fromZoneId, T toZoneId, Coord toCoord, Map<Id<TransitStopFacility>, Double> accessTimes,
				List<Map<Id<TransitStopFacility>, TravelInfo>> trees, float weight) {
			this.pti.pairWeight.add(fromZoneId, toZoneId, weight);

			double walkSpeed = this.parameters.getBeelineWalkSpeed();
			Collection<TransitStopFacility> toStops = findStopCandidates(toCoord, this.raptor, this.parameters);
			Map<Id<TransitStopFacility>, Double> egressTimes = new HashMap<>();
			for (TransitStopFacility stop : toStops) {
				double distance = CoordUtils.calcEuclideanDistance(stop.getCoord(), toCoord);
				egressTimes.put(stop.getId(), distance / walkSpeed);
			}

			List<ODConnection> connections = buildODConnections(trees, accessTimes, egressTimes);
			if (connections.isEmpty()) {
				return;
			}
			connections = RooftopUtils.sortAndFilterConnections(connections, this.maxDepartureTime);

			// service level over all PT connections
			double avgAdaptionTime = RooftopUtils.calcAverageAdaptionTime(connections, this.minDepartureTime, this.maxDepartureTime);
			this.pti.get(Indicator.PT_ADAPTION_TIME).add(fromZoneId, toZoneId, (float) (avgAdaptionTime * weight));
			this.pti.ptWeight.add(fromZoneId, toZoneId, weight);

			Map<ODConnection, Double> connectionShares = RooftopUtils.calcConnectionShares(connections, this.minDepartureTime, this.maxDepartureTime);

			double totalShare = 0;
			double mainShare = 0;
			double[] sum = new double[Indicator.ACCUMULATED_COUNT];
			for (Map.Entry<ODConnection, Double> e : connectionShares.entrySet()) {
				double share = e.getValue();
				if (share <= 0) {
					continue;
				}
				totalShare += share;
				double[] values = classifyConnection(e.getKey(), this.walkDistancePerSecond, this.mainModeDetector);
				if (values == null) {
					continue; // no main-mode leg
				}
				mainShare += share;
				for (int i = 0; i < sum.length; i++) {
					sum[i] += share * values[i];
				}
			}
			if (mainShare <= 0) {
				return;
			}

			// renormalise the shares of the main-mode connections to 1
			Indicator[] all = Indicator.values();
			for (int i = 0; i < sum.length; i++) {
				this.pti.get(all[i]).add(fromZoneId, toZoneId, (float) (sum[i] / mainShare * weight));
			}
			this.pti.mainWeight.add(fromZoneId, toZoneId, weight);
			this.pti.get(Indicator.MAIN_MODE_AVAILABILITY).add(fromZoneId, toZoneId, (float) (mainShare / totalShare * weight));
		}

		private List<ODConnection> buildODConnections(List<Map<Id<TransitStopFacility>, TravelInfo>> trees, Map<Id<TransitStopFacility>, Double> accessTimes,
				Map<Id<TransitStopFacility>, Double> egressTimes) {
			List<ODConnection> connections = new ArrayList<>();
			for (Map<Id<TransitStopFacility>, TravelInfo> tree : trees) {
				for (Map.Entry<Id<TransitStopFacility>, Double> egressEntry : egressTimes.entrySet()) {
					TravelInfo info = tree.get(egressEntry.getKey());
					if (info != null && !info.isWalkOnly()) {
						Double accessTime = accessTimes.get(info.departureStop);
						connections.add(new ODConnection(info.ptDepartureTime, info.ptTravelTime, accessTime, egressEntry.getValue(), info.transferCount, info));
					}
				}
			}
			return connections;
		}
	}

	public static class SegmentedPtIndicators<T> {

		private final Map<Indicator, FloatMatrix<T>> matrices = new EnumMap<>(Indicator.class);

		/** sum of weights of all point pairs (denominator for availability) */
		public final FloatMatrix<T> pairWeight;
		/** sum of weights of point pairs with at least one PT connection (denominator for adaption time) */
		public final FloatMatrix<T> ptWeight;
		/** sum of weights of point pairs with at least one main-mode connection (denominator for segment values) */
		public final FloatMatrix<T> mainWeight;

		SegmentedPtIndicators(Set<T> zones) {
			for (Indicator ind : Indicator.values()) {
				this.matrices.put(ind, FloatMatrix.createFloatMatrix(zones, 0));
			}
			this.pairWeight = FloatMatrix.createFloatMatrix(zones, 0);
			this.ptWeight = FloatMatrix.createFloatMatrix(zones, 0);
			this.mainWeight = FloatMatrix.createFloatMatrix(zones, 0);
		}

		public FloatMatrix<T> get(Indicator indicator) {
			return this.matrices.get(indicator);
		}
	}
}
