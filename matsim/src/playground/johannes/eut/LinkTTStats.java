/* *********************************************************************** *
 * project: org.matsim.*
 * LinkTTStats.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2007 by the members listed in the COPYING,        *
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

/**
 * 
 */
package playground.johannes.eut;

import java.util.HashMap;
import java.util.Map;

import org.matsim.interfaces.basic.v01.BasicLink;
import org.matsim.interfaces.basic.v01.BasicNetwork;
import org.matsim.network.Link;
import org.matsim.network.Node;
import org.matsim.router.util.TravelTime;

/**
 * @author illenberger
 *
 */
public class LinkTTStats {
	
	private Map<BasicLink, LinkAttributes> attributes;
	
	public LinkTTStats(BasicNetwork<Node, Link> network, TravelTime travelTimes, int binsize) {
		this(network, travelTimes, binsize, 0, 86400);
	}
	
	public LinkTTStats(BasicNetwork<Node, Link> network, TravelTime travelTimes, int start, int end, int binsize) {
		attributes = new HashMap<BasicLink, LinkAttributes>();
		analyze(network, travelTimes, start, end, binsize);
	}
	
	private void analyze(BasicNetwork<Node, Link> network, TravelTime travelTimes, int start, int end, int binsize) {
		for(BasicLink link : network.getLinks().values()) {
			double min = Double.MAX_VALUE;
			double max = Double.MIN_VALUE;
			double sum = 0;
			int samples = 0;
			
			for(int t = start; t < end; t += binsize) {
				samples++;
				/*
				 * I think, there is no reason why TravelTime should not work
				 * with BasicLinkI!
				 */
				double tt = travelTimes.getLinkTravelTime((Link) link, t);
				sum += tt;
				min = Math.min(tt, min);
				max = Math.max(tt, max);
			}
			
			double avr = sum/(double)samples;
			sum = 0;
			for(int t = start; t < end; t += binsize) {
				double tt = travelTimes.getLinkTravelTime((Link) link, t);
				sum += Math.pow(tt - avr, 2);
			}

			double variance = Math.sqrt((1.0 / (double)(samples - 1)) * sum);
			
			LinkAttributes atts = new LinkAttributes();
			atts.minTT = min;
			atts.maxTT = max;
			atts.avrTT = avr;
			atts.variance = variance;
			
			attributes.put(link, atts);
		}
	}
	
	public LinkAttributes getLinkAttributes(BasicLink link) {
		return attributes.get(link);
	}
	
	public class LinkAttributes {
		
		public double minTT;
		
		public double maxTT;
		
		public double avrTT;
		
		public double variance;
		
	}
}
