/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2015 by the members listed in the COPYING,        *
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

package playground.johannes.gsv.matrices.episodes2matrix;

import org.apache.commons.lang.StringUtils;
import playground.johannes.gsv.synPop.ActivityType;
import playground.johannes.gsv.synPop.CommonKeys;
import playground.johannes.gsv.synPop.io.XMLParser;
import playground.johannes.gsv.synPop.mid.MIDKeys;
import playground.johannes.gsv.zones.KeyMatrix;
import playground.johannes.gsv.zones.io.KeyMatrixTxtIO;
import playground.johannes.synpop.data.Episode;
import playground.johannes.synpop.data.Person;
import playground.johannes.synpop.data.PlainPerson;
import playground.johannes.synpop.data.Segment;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * @author johannes
 */
public class Episodes2Matrix {

    public static final String DIMIDO = "dimido";

    public static final String WINTER = "win";

    public static final String SUMMER = "sum";

    private static final String DIM_SEPARATOR = ".";

    public static void main(String[] args) throws IOException {
        String in = null;
        String rootDir = null;

        XMLParser reader = new XMLParser();
        reader.setValidating(false);
        reader.parse(in);

        Collection<PlainPerson> persons = reader.getPersons();

        Map<String, LegPredicate> modePreds = new LinkedHashMap<>();
        modePreds.put("car", new LegKeyValuePredicate(CommonKeys.LEG_MODE, "car"));

        Map<String, LegPredicate> purposePreds = new LinkedHashMap<>();
        purposePreds.put(ActivityType.BUISINESS, new LegKeyValuePredicate(CommonKeys.LEG_PURPOSE, ActivityType.BUISINESS));
        purposePreds.put(ActivityType.EDUCATION, new LegKeyValuePredicate(CommonKeys.LEG_PURPOSE, ActivityType.EDUCATION));
        purposePreds.put(ActivityType.LEISURE, new LegKeyValuePredicate(CommonKeys.LEG_PURPOSE, ActivityType.LEISURE));
        purposePreds.put(ActivityType.SHOP, new LegKeyValuePredicate(CommonKeys.LEG_PURPOSE, ActivityType.SHOP));
        purposePreds.put(ActivityType.WORK, new LegKeyValuePredicate(CommonKeys.LEG_PURPOSE, ActivityType.WORK));
        purposePreds.put(ActivityType.VACATIONS_SHORT, new LegKeyValuePredicate(CommonKeys.LEG_PURPOSE, ActivityType
                .VACATIONS_SHORT));
        purposePreds.put(ActivityType.VACATIONS_LONG, new LegKeyValuePredicate(CommonKeys.LEG_PURPOSE, ActivityType.VACATIONS_LONG));
        purposePreds.put(InfereWeCommuter.WECOMMUTER, new LegKeyValuePredicate(CommonKeys.LEG_PURPOSE, InfereWeCommuter
                .WECOMMUTER));

        Map<String, LegPredicate> dayPreds = new LinkedHashMap<>();
        dayPreds.put(CommonKeys.MONDAY, new PersonKeyValuePredicate(CommonKeys.DAY, CommonKeys.MONDAY));
        dayPreds.put(CommonKeys.FRIDAY, new PersonKeyValuePredicate(CommonKeys.DAY, CommonKeys.FRIDAY));
        dayPreds.put(CommonKeys.SATURDAY, new PersonKeyValuePredicate(CommonKeys.DAY, CommonKeys.SATURDAY));
        dayPreds.put(CommonKeys.SUNDAY, new PersonKeyValuePredicate(CommonKeys.DAY, CommonKeys.SUNDAY));
        PredicateORComposite dimidoPred = new PredicateORComposite();
        dimidoPred.addComponent(new PersonKeyValuePredicate(CommonKeys.DAY, CommonKeys.TUESDAY));
        dimidoPred.addComponent(new PersonKeyValuePredicate(CommonKeys.DAY, CommonKeys.WEDNESDAY));
        dimidoPred.addComponent(new PersonKeyValuePredicate(CommonKeys.DAY, CommonKeys.THURSDAY));
        dayPreds.put(DIMIDO, dimidoPred);

        Map<String, LegPredicate> seasonPreds = new LinkedHashMap<>();
        PredicateORComposite summerPred = new PredicateORComposite();
        summerPred.addComponent(new PersonKeyValuePredicate(MIDKeys.PERSON_MONTH, MIDKeys.APRIL));
        summerPred.addComponent(new PersonKeyValuePredicate(MIDKeys.PERSON_MONTH, MIDKeys.MAY));
        summerPred.addComponent(new PersonKeyValuePredicate(MIDKeys.PERSON_MONTH, MIDKeys.JUNE));
        summerPred.addComponent(new PersonKeyValuePredicate(MIDKeys.PERSON_MONTH, MIDKeys.JULY));
        summerPred.addComponent(new PersonKeyValuePredicate(MIDKeys.PERSON_MONTH, MIDKeys.AUGUST));
        summerPred.addComponent(new PersonKeyValuePredicate(MIDKeys.PERSON_MONTH, MIDKeys.SEPTEMBER));
        summerPred.addComponent(new PersonKeyValuePredicate(MIDKeys.PERSON_MONTH, MIDKeys.OCTOBER));
        seasonPreds.put(SUMMER, summerPred);
        PredicateORComposite winterPred = new PredicateORComposite();
        winterPred.addComponent(new PersonKeyValuePredicate(MIDKeys.PERSON_MONTH, MIDKeys.NOVEMBER));
        winterPred.addComponent(new PersonKeyValuePredicate(MIDKeys.PERSON_MONTH, MIDKeys.DECEMBER));
        winterPred.addComponent(new PersonKeyValuePredicate(MIDKeys.PERSON_MONTH, MIDKeys.JANUARY));
        winterPred.addComponent(new PersonKeyValuePredicate(MIDKeys.PERSON_MONTH, MIDKeys.FEBRUARY));
        winterPred.addComponent(new PersonKeyValuePredicate(MIDKeys.PERSON_MONTH, MIDKeys.MARCH));
        seasonPreds.put(WINTER, winterPred);

        Map<String, LegPredicate> directionPreds = new LinkedHashMap<>();

        for (Map.Entry<String, LegPredicate> mode : modePreds.entrySet()) {
            for (Map.Entry<String, LegPredicate> purpose : purposePreds.entrySet()) {
                for (Map.Entry<String, LegPredicate> day : dayPreds.entrySet()) {
                    for (Map.Entry<String, LegPredicate> season : seasonPreds.entrySet()) {
                        for (Map.Entry<String, LegPredicate> direction : directionPreds.entrySet()) {

                            PredicateANDComposite comp = new PredicateANDComposite();
                            comp.addComponent(mode.getValue());
                            comp.addComponent(purpose.getValue());
                            comp.addComponent(day.getValue());
                            comp.addComponent(season.getValue());
                            comp.addComponent(direction.getValue());

                            KeyMatrix m = getMatrix(persons, comp);

                            StringBuilder builder = new StringBuilder();
                            builder.append(mode.getKey());
                            builder.append(DIM_SEPARATOR);
                            builder.append(purpose.getKey());
                            builder.append(DIM_SEPARATOR);
                            builder.append(day.getKey());
                            builder.append(DIM_SEPARATOR);
                            builder.append(season.getKey());
                            builder.append(DIM_SEPARATOR);
                            builder.append(direction.getKey());

                            String out = String.format("%s/%s.txt", rootDir, builder.toString());
                            KeyMatrixTxtIO.write(m, out);
                        }
                    }
                }
            }
        }
    }

    private static KeyMatrix getMatrix(Collection<PlainPerson> persons, LegPredicate pred) {
        KeyMatrix m = new KeyMatrix();

        for (Person person : persons) {
            for (Episode episode : person.getEpisodes()) {
                for (int i = 0; i < episode.getLegs().size(); i++) {
                    Segment leg = episode.getLegs().get(i);
                    if (pred.test(leg)) {
                        Segment prev = episode.getActivities().get(i);
                        Segment next = episode.getActivities().get(i + 1);

                        String origin = prev.getAttribute(SetZones.ZONE_KEY);
                        String dest = next.getAttribute(SetZones.ZONE_KEY);

                        Double volume = m.get(origin, dest);
                        if (volume == null) volume = 0.0;

                        volume++;

                        m.set(origin, dest, volume);
                    }
                }
            }
        }

        return m;
    }
}