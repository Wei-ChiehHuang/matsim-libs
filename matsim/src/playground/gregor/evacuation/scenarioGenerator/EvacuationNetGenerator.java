/* *********************************************************************** *
 * project: org.matsim.*
 * EvacuationNetGenerator.java
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

package playground.gregor.evacuation.scenarioGenerator;

import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.log4j.Logger;
import org.matsim.basic.v01.IdImpl;
import org.matsim.evacuation.EvacuationAreaFileReader;
import org.matsim.evacuation.EvacuationAreaLink;
import org.matsim.evacuation.EvacuationPlansGeneratorAndNetworkTrimmer;
import org.matsim.interfaces.basic.v01.Id;
import org.matsim.network.Link;
import org.matsim.network.MatsimNetworkReader;
import org.matsim.network.NetworkLayer;
import org.matsim.network.NetworkWriter;
import org.matsim.network.Node;
import org.matsim.network.algorithms.NetworkCleaner;
import org.matsim.utils.geometry.CoordImpl;

public class EvacuationNetGenerator {

	private final static Logger log = Logger.getLogger(EvacuationPlansGeneratorAndNetworkTrimmer.class);

	//evacuation Nodes an Link
	private final static String saveLinkId = "el1";
	private final static String saveNodeAId = "en1";
	private final static String saveNodeBId = "en2";

	//	the positions of the evacuation nodes - for now hard coded
	// Since the real positions of this nodes not really matters
	// and for the moment we are going to evacuate Padang only,
	// the save nodes are located east of the city.
	// Doing so, the visualization of the resulting evacuation network is much clearer in respect of coinciding links.
	private final static String saveAX = "662433";
	private final static String saveAY = "9898853";
	private final static String saveBX = "662433";
	private final static String saveBY = "9898853";

	private HashMap<Id, EvacuationAreaLink> evacuationAreaLinks = new HashMap<Id, EvacuationAreaLink>();
	private final HashSet<Node> saveNodes = new HashSet<Node>();
	private final HashSet<Node> redundantNodes = new HashSet<Node>();

	
	/**
	 * Creates links from all save nodes to the evacuation node A
	 *
	 * @param network
	 */
	private void createEvacuationLinks(final NetworkLayer network) {

		Node nodeA = network.createNode(new IdImpl(saveNodeAId), new CoordImpl(saveAX, saveAY));
		Node nodeB = network.createNode(new IdImpl(saveNodeBId), new CoordImpl(saveBX, saveBY));

		/* TODO [GL] the capacity of the evacuation links should be a very high value but for unknown reason Double.MAX_VALUE
		 * does not work, may be the better solution will be to implement a method in QueueLink to set the spaceCap to infinity
		 *anyway, this solution is just a workaround the spaceCap problem should be solved in an other way - gl */
		final double capacity = Double.parseDouble("99999999999999999999"); // (new Double (Double.MAX_VALUE)).toString();
		network.createLink(new IdImpl(saveLinkId), nodeA, nodeB, 10, 100000, capacity, 1);

		int linkId = 1;
		for (final Node node : network.getNodes().values()) {
			final String nodeId =  node.getId().toString();
			if (isSaveNode(node) && !nodeId.equals(saveNodeAId) && !nodeId.equals(saveNodeBId)){
				linkId++;
				final String sLinkID = "el" + Integer.toString(linkId);
				network.createLink(new IdImpl(sLinkID), node, nodeA, 10, 100000, capacity, 1);
			}
		}
	}

	/**
	 * @param node
	 * @return true if <code>node</node> is outside the evacuation area
	 */
	private boolean isSaveNode(final Node node) {
		return this.saveNodes.contains(node);
	}

	/**
	 * Returns true if <code>node</code> is redundant. A node is
	 * redundant if it is not next to the evacuation area.
	 *
	 * @param node
	 * @return true if <code>node</code> is redundant.
	 */
	private boolean isRedundantNode(final Node node) {
		return this.redundantNodes.contains(node);
	}


	public void generateEvacuationNet(final NetworkLayer network, final HashMap<Id, EvacuationAreaLink> evacuationAreaLinks) {
		this.evacuationAreaLinks = evacuationAreaLinks;
		log.info("generating evacuation plans ...");
		log.info(" * classifing nodes");
		classifyNodes(network);
		log.info(" * cleaning up the network");
		cleanUpNetwork(network);
		log.info(" * creating evacuation links");
		createEvacuationLinks(network);
		log.info("done");
	}

	/**
	 * Classifies the nodes. Nodes that are next to the evacuation area and
	 * reachable from inside the evacuation area will be classified as save
	 * nodes. Other nodes outside the evacuation area will be classified
	 * as redundant nodes.
	 *
	 * @param network
	 */
	private void classifyNodes(final NetworkLayer network) {
		/* classes:
		 * 0: default, assume redundant
		 * 1: redundant node
		 * 2: save nodes, can be reached from evacuation area
		 * 3: "normal" nodes within the evacuation area
		 */
		for (final Node node : network.getNodes().values()) {
			int inCat = 0;
			for (final Link link : node.getInLinks().values()) {
				if (this.evacuationAreaLinks.containsKey(link.getId())) {
					if ((inCat == 0) || (inCat == 3)) {
						inCat = 3;
					}	else {
						inCat = 2;
						break;
					}
				} else {
					if (inCat <= 1) {
						inCat = 1;
					} else {
						inCat = 2;
						break;
					}
				}
			}
			switch (inCat) {
				case 2:
					this.saveNodes.add(node);
					break;
				case 3:
					break;
				case 1:
				default:
					this.redundantNodes.add(node);
			}
		}

	}

	/**
	 * Removes all links and nodes outside the evacuation area except the
	 * nodes next to the evacuation area that are reachable from inside the
	 * evacuation area ("save nodes").
	 *
	 * @param network
	 */
	private void cleanUpNetwork(final NetworkLayer network) {

		final ConcurrentLinkedQueue<Link> l = new ConcurrentLinkedQueue<Link>();
		for (final Link link : network.getLinks().values()) {
			if (!this.evacuationAreaLinks.containsKey(link.getId())) {
				l.add(link);
			}
		}

		Link link = l.poll();
		while (link != null){
			network.removeLink(link);
			link = l.poll();
		}

		final ConcurrentLinkedQueue<Node> n = new ConcurrentLinkedQueue<Node>();
		for (final Node node : network.getNodes().values()) {
			if (isRedundantNode(node)) {
				n.add(node);
			}
		}

		Node node = n.poll();
		while (node != null) {
			network.removeNode(node);
			node = n.poll();
		}
		new NetworkCleaner().run(network);
	}
	
	

	
	public static void main(final String [] args) {
		
		if (args.length != 3) {
			throw new RuntimeException("wrong number of arguments! Pleas run EvacuationAreaFileGenerator config.xml" );
		}
		final String netfile = args[0];
		final String evacnet = args[1];
		final String evacareafile = args[2];
		

//		final World world = Gbl.createWorld();

		log.info("loading network from " + netfile);
		final NetworkLayer network = new NetworkLayer();
		new MatsimNetworkReader(network).readFile(netfile);
//		world.setNetworkLayer(network);
//		world.complete();
		log.info("done.");

		
		
//		log.info("loading evacuationarea from " + Gbl.getConfig().evacuation().getEvacuationAreaFile());
		log.info("loading evacuationarea from " + evacareafile);
		final HashMap<Id,EvacuationAreaLink> el = new HashMap<Id,EvacuationAreaLink>();
		
		try {
//			new EvacuationAreaFileReader(el).readFile(Gbl.getConfig().evacuation().getEvacuationAreaFile());
			new EvacuationAreaFileReader(el).readFile(evacareafile);
		} catch (final Exception e) {
			e.printStackTrace();
		}
		log.info("done.");
		new EvacuationNetGenerator().generateEvacuationNet(network, el);
		
		new NetworkWriter(network,evacnet).write();

	}
}
