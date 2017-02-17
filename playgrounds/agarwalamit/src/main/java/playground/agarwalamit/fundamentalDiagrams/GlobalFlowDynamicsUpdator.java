/* *********************************************************************** *
 * project: org.matsim.*
 * FundamentalDiagramsNmodes											   *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2012 by the members listed in the COPYING,        *
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

package playground.agarwalamit.fundamentalDiagrams;

import java.util.HashMap;
import java.util.Map;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.VehicleEntersTrafficEvent;
import org.matsim.api.core.v01.events.VehicleLeavesTrafficEvent;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleEntersTrafficEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleLeavesTrafficEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.events.algorithms.Vehicle2DriverEventHandler;

/**
 * @author ssix, amit
 * A class supposed to go attached to the GenerateFundametalDiagramData class.
 * It aims at analyzing the flow of events in order to detect:
 * The permanent regime of the system and the following searched values:
 * the permanent flow, the permanent density and the permanent average 
 * velocity for each velocity group.
 */

class GlobalFlowDynamicsUpdator implements LinkEnterEventHandler, PersonDepartureEventHandler, VehicleEntersTrafficEventHandler, VehicleLeavesTrafficEventHandler {
	
	private final Map<String, TravelModesFlowDynamicsUpdator> travelModesFlowData;
	private final TravelModesFlowDynamicsUpdator globalFlowData;
	private final Map<Id<Person>, String> person2Mode = new HashMap<>();
	
	public final Id<Link> flowDynamicsUpdateLink;
	private final Vehicle2DriverEventHandler delegate = new Vehicle2DriverEventHandler();
	
	private boolean permanentRegime;

	/**
	 * @param travelModeFlowDataContainer
	 * @param lengthOfTrack
	 * container to store static properties of vehicles and dynamic flow properties during simulation 
	 */
	GlobalFlowDynamicsUpdator( final Map<String, TravelModesFlowDynamicsUpdator> travelModeFlowDataContainer, final Id<Link> startLinkOfTrack, final double lengthOfTrack){
		int totalAgents = 0;
		this.travelModesFlowData = travelModeFlowDataContainer;
		for (String vehTyp : travelModeFlowDataContainer.keySet()){
			this.travelModesFlowData.get(vehTyp).initDynamicVariables();
			totalAgents += this.travelModesFlowData.get(vehTyp).getnumberOfAgents();
		}
		this.globalFlowData = new TravelModesFlowDynamicsUpdator(this.travelModesFlowData.size(), startLinkOfTrack,  lengthOfTrack);
		this.globalFlowData.setnumberOfAgents(totalAgents);
		this.globalFlowData.initDynamicVariables();
		this.permanentRegime = false;
		this.flowDynamicsUpdateLink = startLinkOfTrack;
	}

	@Override
	public void reset(int iteration) {	
		for (String vehTyp : travelModesFlowData.keySet()){
			this.travelModesFlowData.get(vehTyp).reset();
		}
		this.globalFlowData.reset();
		this.permanentRegime = false;
		this.delegate.reset(iteration);
	}
	@Override
	public void handleEvent(PersonDepartureEvent event) {
		person2Mode.put(event.getPersonId(), event.getLegMode());
	}

	@Override
	public void handleEvent(LinkEnterEvent event) {
		if (!(permanentRegime)){
			Id<Person> personId = this.delegate.getDriverOfVehicle(event.getVehicleId());
			String travelMode = person2Mode.get(personId);

			this.travelModesFlowData.get(travelMode).handle(event);
			double pcuPerson = this.travelModesFlowData.get(travelMode).getVehicleType().getPcuEquivalents();

			//Aggregated data update
			double nowTime = event.getTime();
			if (event.getLinkId().equals(flowDynamicsUpdateLink)){
				this.globalFlowData.updateFlow900(nowTime, pcuPerson);
				this.globalFlowData.updateSpeedTable(nowTime,personId);
				//Waiting for all agents to be on the track before studying stability
				if ((this.globalFlowData.getNumberOfDrivingAgents() == this.globalFlowData.numberOfAgents) && (nowTime > FundamentalDiagramDataGenerator.MAX_ACT_END_TIME * 2)){
					/*//Taking speed check out, as it is not reliable on the global speed table
					 *  Maybe making a list of moving averages could be smart, 
					 *  but there is no reliable converging process even in that case. (ssix, 25.10.13)
					 * if (!(this.globalData.isSpeedStable())){
						this.globalData.checkSpeedStability(); 
						System.out.println("Checking speed stability in global data for: "+this.globalData.getSpeedTable());
					}*/
					if (!(this.globalFlowData.isFlowStable())){
						this.globalFlowData.checkFlowStability900();
					}

					//Checking modes stability
					boolean modesStable = true;
					for (String vehTyp : travelModesFlowData.keySet()){
						if (this.travelModesFlowData.get(vehTyp).numberOfAgents != 0){
							if (! this.travelModesFlowData.get(vehTyp).isSpeedStable() || ! this.travelModesFlowData.get(vehTyp).isFlowStable() ) {
								modesStable = false;
								break;
							} 
						}
					}
					if (modesStable){
						//Checking global stability
						if ( /*this.globalFlowData.isSpeedStable() &&*/ this.globalFlowData.isFlowStable() ){
							FundamentalDiagramDataGenerator.LOG.info("========== Global permanent regime is attained");
							for (String vehTyp : travelModesFlowData.keySet()){
								this.travelModesFlowData.get(vehTyp).saveDynamicVariables();
							}
							this.globalFlowData.setPermanentAverageVelocity(this.globalFlowData.getActualAverageVelocity());
							this.globalFlowData.setPermanentFlow(this.globalFlowData.getCurrentHourlyFlow()); 
							double globalDensity = 0.;
							for (TravelModesFlowDynamicsUpdator mode : this.travelModesFlowData.values()){
								globalDensity += mode.getPermanentDensity();
							}
							this.globalFlowData.setPermanentDensity(globalDensity);
							this.permanentRegime = true;
							//How to: end simulation immediately? => solved by granting the mobsim agents access to permanentRegime
							//and making them exit the simulation as soon as permanentRegime is true.
						}
					}
				}
			}
		}
	}

	@Override
	public void handleEvent(VehicleEntersTrafficEvent event) {
		this.delegate.handleEvent(event);
		this.travelModesFlowData.get(person2Mode.get(event.getPersonId())).handle(event);
	}

	@Override
	public void handleEvent(VehicleLeavesTrafficEvent event) {
		this.delegate.handleEvent(event);
		this.travelModesFlowData.get(person2Mode.get(event.getPersonId())).handle(event);
	}
	
	boolean isPermanent(){
		return permanentRegime;
	}

	TravelModesFlowDynamicsUpdator getGlobalData(){
		return this.globalFlowData;
	}
}