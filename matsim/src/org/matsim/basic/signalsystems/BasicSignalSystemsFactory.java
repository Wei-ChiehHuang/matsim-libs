/* *********************************************************************** *
 * project: org.matsim.*
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

package org.matsim.basic.signalsystems;

import org.matsim.interfaces.basic.v01.Id;

/**
 * @author dgrether
 */
public class BasicSignalSystemsFactory {
	
	public BasicLanesToLinkAssignment createLanesToLinkAssignment(Id id) {
		return new BasicLanesToLinkAssignment(id);
	}

	public BasicLane createLane(Id id) {
		return new BasicLane(id);
	}

	public BasicSignalSystemDefinition createLightSignalSystemDefinition(
			Id id) {
		return new BasicSignalSystemDefinition(id);
	}

	public BasicSignalGroupDefinition createLightSignalGroupDefinition(
			Id linkRefId, Id id) {
		return new BasicSignalGroupDefinition(linkRefId, id);
	}
	
}
