/* *********************************************************************** *
 * project: org.matsim.* 												   *
 *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2023 by the members listed in the COPYING,        *
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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiPredicate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.feature.simple.SimpleFeature;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.collections.QuadTree;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.gis.GeoFileReader;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.core.utils.misc.StringUtils;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

/**
 * Calculates, for each zone, the beeline distance to the nearest stop of several stop categories (e.g. train, metro/tram, bus).
 * <p>
 * A stop belongs to a category if it is served by at least one transit route matching the category's predicate. Stops that exist in the schedule but are not
 * served by any matching route are ignored.
 * <p>
 * Two distances are calculated per zone and category:
 * <ul>
 *     <li>the distance from the zone centroid to the nearest stop</li>
 *     <li>if sampling points are loaded (see {@link CalculateSkimMatrices#ZONE_LOCATIONS_FILENAME}), the average over all sampling points of the distance
 *     from the sampling point to its nearest stop</li>
 * </ul>
 * All distances are in the units of the coordinate system, so zones and schedules must use the same (projected) CRS.
 */
public class CalculateZoneStopDistances {

    public static final String ZONE_STOP_DISTANCES_FILENAME = "zone_stop_distances.csv";
    private static final Logger log = LogManager.getLogger(CalculateZoneStopDistances.class);

    private final String outputDirectory;
    private final Map<String, Coord> centroidPerZone = new TreeMap<>();
    private Map<String, Coord[]> coordsPerZone = null;
    private final Map<String, QuadTree<TransitStopFacility>> stopsPerCategory = new LinkedHashMap<>();
    private final Map<String, TransitSchedule> scheduleCache = new HashMap<>();

    public CalculateZoneStopDistances(String outputDirectory) {
        this.outputDirectory = outputDirectory;
        File outputDir = new File(outputDirectory);
        if (!outputDir.exists()) {
            log.info("create output directory " + outputDirectory);
            outputDir.mkdirs();
        }
    }

    public static void main(String[] args) throws IOException {
        //String zonesShapeFilename = args[0];
        String zonesShapeFilename = "Z://projects//2022//fabilut//zones//zones_Munich-Bavaria_size_5452_popCap_5000_crs_31468.shp";

        //String zonesIdAttributeName = args[1];
        String zonesIdAttributeName = "TAZ_ID";

        //String transitScheduleFilename = args[2..4];
        String transitScheduleFilename_Bus =            "Z://projects//2022//fabilut//network//matsim_network_ptSchedule_forSkimCalculation//munichBavaria_bus//munichBavaria_schedule_bus_mapped.xml.gz";
        String transitScheduleFilename_TramMetro =      "Z://projects//2022//fabilut//network//matsim_network_ptSchedule_forSkimCalculation//munichBavaria_metroTram//munichBavaria_schedule_urbanRail_mapped.xml.gz";
        String transitScheduleFilename_RegionalRail =   "Z://projects//2022//fabilut//network//matsim_network_ptSchedule_forSkimCalculation//munichBavaria_regionalRail//munichBavaria_schedule_regionalRail_mapped.xml.gz";

        //String outputDirectory = args[5];
        String outputDirectory = "C://Users//Wei//Downloads//Munich-Bavaria_20250825gtfs_0800-0900_full";

        CalculateZoneStopDistances distances = new CalculateZoneStopDistances(outputDirectory);
        distances.loadZones(zonesShapeFilename, zonesIdAttributeName);

        // optional: also use the sampling points written by CalculateSkimMatrices
        File samplingPointsFile = new File(outputDirectory, CalculateSkimMatrices.ZONE_LOCATIONS_FILENAME);
        if (samplingPointsFile.exists()) {
            distances.loadSamplingPointsFromFile(samplingPointsFile.getAbsolutePath());
        }

        distances.addStopCategory("train", transitScheduleFilename_RegionalRail, (line, route) -> route.getTransportMode().equalsIgnoreCase("rail"));
        distances.addStopCategory("metro", transitScheduleFilename_TramMetro, (line, route) -> route.getTransportMode().equalsIgnoreCase("subway"));
        distances.addStopCategory("tram", transitScheduleFilename_TramMetro, (line, route) -> route.getTransportMode().equalsIgnoreCase("tram"));
        distances.addStopCategory("bus", transitScheduleFilename_Bus, (line, route) -> route.getTransportMode().equalsIgnoreCase("bus") || route.getTransportMode().equalsIgnoreCase("ferry"));

        distances.calculateAndWriteDistances(new File(outputDirectory, ZONE_STOP_DISTANCES_FILENAME));
    }

    public final void loadZones(String zonesShapeFilename, String zonesIdAttributeName) {
        log.info("loading zones from " + zonesShapeFilename);
        Collection<SimpleFeature> zones = new GeoFileReader().readFileAndInitialize(zonesShapeFilename);
        for (SimpleFeature zone : zones) {
            String zoneId = zone.getAttribute(zonesIdAttributeName).toString();
            Point centroid = ((Geometry) zone.getDefaultGeometry()).getCentroid();
            this.centroidPerZone.put(zoneId, new Coord(centroid.getX(), centroid.getY()));
        }
        log.info("loaded " + this.centroidPerZone.size() + " zones");
    }

    public void setSamplingPoints(Map<String, Coord[]> coordsPerZone) {
        this.coordsPerZone = coordsPerZone;
    }

    public final void loadSamplingPointsFromFile(String filename) throws IOException {
        log.info("loading sampling points from " + filename);
        String expectedHeader = "ZONE;POINT_INDEX;X;Y";
        Map<String, List<Coord>> coords = new HashMap<>();
        try (BufferedReader reader = IOUtils.getBufferedReader(filename)) {
            String header = reader.readLine();
            if (!expectedHeader.equals(header)) {
                throw new RuntimeException("Bad header, expected '" + expectedHeader + "', got: '" + header + "'.");
            }
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = StringUtils.explode(line, ';');
                double x = Double.parseDouble(parts[2]);
                double y = Double.parseDouble(parts[3]);
                coords.computeIfAbsent(parts[0], k -> new ArrayList<>()).add(new Coord(x, y));
            }
        }
        this.coordsPerZone = new HashMap<>();
        for (Map.Entry<String, List<Coord>> e : coords.entrySet()) {
            this.coordsPerZone.put(e.getKey(), e.getValue().toArray(new Coord[0]));
        }
    }

    /**
     * Registers a stop category. All stops served by at least one route matching {@code routeFilter} are used as candidates for the nearest stop.
     */
    public final void addStopCategory(String category, String transitScheduleFilename, BiPredicate<TransitLine, TransitRoute> routeFilter) {
        TransitSchedule schedule = this.scheduleCache.computeIfAbsent(transitScheduleFilename, filename -> {
            log.info("loading schedule from " + filename);
            Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
            new TransitScheduleReader(scenario).readFile(filename);
            return scenario.getTransitSchedule();
        });

        Map<TransitStopFacility, Boolean> servedStops = new LinkedHashMap<>();
        for (TransitLine line : schedule.getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                if (routeFilter.test(line, route)) {
                    for (TransitRouteStop stop : route.getStops()) {
                        servedStops.put(stop.getStopFacility(), Boolean.TRUE);
                    }
                }
            }
        }

        if (servedStops.isEmpty()) {
            log.warn("no stops found for category " + category + ", distances will be NaN.");
            this.stopsPerCategory.put(category, null);
            return;
        }

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (TransitStopFacility stop : servedStops.keySet()) {
            Coord c = stop.getCoord();
            minX = Math.min(minX, c.getX());
            minY = Math.min(minY, c.getY());
            maxX = Math.max(maxX, c.getX());
            maxY = Math.max(maxY, c.getY());
        }
        QuadTree<TransitStopFacility> qt = new QuadTree<>(minX, minY, maxX, maxY);
        for (TransitStopFacility stop : servedStops.keySet()) {
            qt.put(stop.getCoord().getX(), stop.getCoord().getY(), stop);
        }
        log.info("category " + category + ": " + qt.size() + " served stops");
        this.stopsPerCategory.put(category, qt);
    }

    public final void calculateAndWriteDistances(File file) throws IOException {
        boolean withSamplingPoints = this.coordsPerZone != null;
        log.info("calc distances to nearest stops and write them to " + file.getAbsolutePath());
        try (BufferedWriter writer = IOUtils.getBufferedWriter(file.getAbsolutePath())) {
            writer.write("ZONE;CENTROID_X;CENTROID_Y");
            for (String category : this.stopsPerCategory.keySet()) {
                writer.write(";" + category + "_NEAREST_STOP_ID;" + category + "_CENTROID_DIST");
                if (withSamplingPoints) {
                    writer.write(";" + category + "_SAMPLING_POINTS_AVG_DIST");
                }
            }
            writer.write("\n");

            for (Map.Entry<String, Coord> e : this.centroidPerZone.entrySet()) {
                String zoneId = e.getKey();
                Coord centroid = e.getValue();
                writer.write(zoneId + ";" + centroid.getX() + ";" + centroid.getY());
                Coord[] samplingPoints = withSamplingPoints ? this.coordsPerZone.get(zoneId) : null;

                for (QuadTree<TransitStopFacility> stops : this.stopsPerCategory.values()) {
                    if (stops == null) {
                        writer.write(";;" + Double.NaN);
                        if (withSamplingPoints) {
                            writer.write(";" + Double.NaN);
                        }
                        continue;
                    }
                    TransitStopFacility nearest = stops.getClosest(centroid.getX(), centroid.getY());
                    writer.write(";" + nearest.getId() + ";" + CoordUtils.calcEuclideanDistance(centroid, nearest.getCoord()));
                    if (withSamplingPoints) {
                        writer.write(";" + calcAverageDistanceToNearestStop(samplingPoints, stops));
                    }
                }
                writer.write("\n");
            }
        }
    }

    private static double calcAverageDistanceToNearestStop(Coord[] coords, QuadTree<TransitStopFacility> stops) {
        if (coords == null || coords.length == 0) {
            return Double.NaN;
        }
        double sum = 0.0;
        for (Coord coord : coords) {
            TransitStopFacility nearest = stops.getClosest(coord.getX(), coord.getY());
            sum += CoordUtils.calcEuclideanDistance(coord, nearest.getCoord());
        }
        return sum / coords.length;
    }

}
