/* *********************************************************************** *
 * project: org.matsim.*
 * SpanningTree.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2008 by the members listed in the COPYING,        *
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

package playground.toronto.ttimematrix;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

import org.apache.log4j.Logger;
import org.matsim.basic.v01.IdImpl;
import org.matsim.gbl.Gbl;
import org.matsim.interfaces.basic.v01.Id;
import org.matsim.network.Link;
import org.matsim.network.MatsimNetworkReader;
import org.matsim.network.NetworkLayer;
import org.matsim.network.Node;
import org.matsim.router.costcalculators.TravelTimeDistanceCostCalculator;
import org.matsim.router.util.TravelCost;
import org.matsim.router.util.TravelTime;
import org.matsim.trafficmonitoring.TravelTimeCalculator;
import org.matsim.utils.misc.Time;

public class SpanningTree {

	//////////////////////////////////////////////////////////////////////
	// member variables
	//////////////////////////////////////////////////////////////////////

	private final static Logger log = Logger.getLogger(SpanningTree.class);
	
	private Node origin = null;
	private double dTime = Time.UNDEFINED_TIME;
	
	private final TravelTime ttFunction;
	private final TravelCost tcFunction;
	private HashMap<Id,NodeData> nodeData;
	
	//////////////////////////////////////////////////////////////////////
	// constructors
	//////////////////////////////////////////////////////////////////////

	public SpanningTree(TravelTime tt, TravelCost tc) {
		log.info("init " + this.getClass().getName() + " module...");
		this.ttFunction = tt;
		this.tcFunction = tc;
		log.info("done.");
	}
	
	//////////////////////////////////////////////////////////////////////
	// inner classes
	//////////////////////////////////////////////////////////////////////

	public static class NodeData {
		private Node prev = null;
		private double cost = Double.MAX_VALUE;
		private double time = 0;
		public void reset() { this.prev = null; this.cost = Double.MAX_VALUE; this.time = 0; }
		public void visit(final Node comingFrom, final double cost, final double time) {
			this.prev = comingFrom;
			this.cost = cost;
			this.time = time;
		}
		public double getCost() { return this.cost; }
		public double getTime() { return this.time; }
		public Node getPrevNode() { return this.prev; }
	}

	static class ComparatorCost implements Comparator<Node> {
		protected Map<Id, ? extends NodeData> nodeData;
		ComparatorCost(final Map<Id, ? extends NodeData> nodeData) { this.nodeData = nodeData; }
		public int compare(final Node n1, final Node n2) {
			double c1 = getCost(n1);
			double c2 = getCost(n2);
			if (c1 < c2) return -1;
			if (c1 > c2) return +1;
			return n1.compareTo(n2);
		}
		protected double getCost(final Node node) { return this.nodeData.get(node.getId()).getCost(); }
	}

	//////////////////////////////////////////////////////////////////////
	// set methods
	//////////////////////////////////////////////////////////////////////
	
	public final void setOrigin(Node origin) {
		this.origin = origin;
	}

	public final void setDepartureTime(double time) {
		this.dTime = time;
	}

	//////////////////////////////////////////////////////////////////////
	// get methods
	//////////////////////////////////////////////////////////////////////
	
	public final HashMap<Id,NodeData> getTree() {
		return this.nodeData;
	}
	
	//////////////////////////////////////////////////////////////////////
	// private methods
	//////////////////////////////////////////////////////////////////////

	private void relaxNode(final Node n, PriorityQueue<Node> pendingNodes) {
		NodeData nData = nodeData.get(n.getId());
		double currTime = nData.getTime();
		double currCost = nData.getCost();
		for (Link l : n.getOutLinks().values()) {
			Node nn = l.getToNode();
			NodeData nnData = nodeData.get(nn.getId());
			if (nnData == null) { nnData = new NodeData(); this.nodeData.put(nn.getId(),nnData); }
			double visitCost = currCost+tcFunction.getLinkTravelCost(l,currTime);
			double visitTime = currTime+ttFunction.getLinkTravelTime(l,currTime);
			if (visitCost < nnData.getCost()) {
				pendingNodes.remove(nn);
				nnData.visit(n,visitCost,visitTime);
				pendingNodes.add(nn);
			}
		}
	}

	//////////////////////////////////////////////////////////////////////
	// run method
	//////////////////////////////////////////////////////////////////////

	public void run(final NetworkLayer network) {
//		log.info("running " + this.getClass().getName() + " module...");

		nodeData = new HashMap<Id,NodeData>((int)(network.getNodes().size()*1.1),0.95f);
		NodeData d = new NodeData();
		d.time = dTime;
		d.cost = 0;
		nodeData.put(origin.getId(),d);
		
		ComparatorCost comparator = new ComparatorCost(nodeData);
		PriorityQueue<Node> pendingNodes = new PriorityQueue<Node>(500,comparator);
		relaxNode(this.origin,pendingNodes);
		while (!pendingNodes.isEmpty()) {
			Node n = pendingNodes.poll();
			relaxNode(n,pendingNodes);
		}
		
//		log.info("done.");
	}
	
	//////////////////////////////////////////////////////////////////////
	// main method
	//////////////////////////////////////////////////////////////////////

	public static void main(String[] args) {
		NetworkLayer network = new NetworkLayer();
		new MatsimNetworkReader(network).readFile("../../input/network.xml");
		
		if (Gbl.getConfig() == null) { Gbl.createConfig(null); }
		TravelTime ttc = new TravelTimeCalculator(network,60,30*3600);
		SpanningTree st = new SpanningTree(ttc,new TravelTimeDistanceCostCalculator(ttc));
		Node origin = network.getNode(new IdImpl(1));
		st.setOrigin(origin);
		st.setDepartureTime(8*3600);
		st.run(network);
		HashMap<Id,NodeData> tree = st.getTree();
		for (Id id : tree.keySet()) {
			NodeData d = tree.get(id);
			if (d.getPrevNode() != null) {
				System.out.println(id+"\t"+d.getTime()+"\t"+d.getCost()+"\t"+d.getPrevNode().getId());
			}
			else {
				System.out.println(id+"\t"+d.getTime()+"\t"+d.getCost()+"\t"+"0");
			}
		}
	}
}
