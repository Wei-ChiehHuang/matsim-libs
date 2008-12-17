/* *********************************************************************** *
 * project: org.matsim.*
 * RecyclingModule1.java
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

package playground.mfeil;


import org.matsim.population.Act;
import org.matsim.gbl.MatsimRandom;
import org.matsim.replanning.modules.MultithreadedModuleA;
import org.matsim.replanning.modules.StrategyModule;


/**
 * @author Matthias Feil
 * Includes also the optimization of the distance coefficients
 */


public class RecyclingModule1 extends RecyclingModule implements StrategyModule{
		
	private final int iterations, noOfAgents;
	private final DistanceCoefficients coefficients;
	private final MultithreadedModuleA assignmentModule;
	
	public RecyclingModule1 (ControlerMFeil controler) {
		super(controler);
		this.iterations 		= 20;
		this.noOfAgents			= 10;
		this.coefficients 		= new DistanceCoefficients (1, 1);		
		this.assignmentModule	= new AgentsAssignmentInitialiser1 (controler, this.preProcessRoutingData, 
				this.estimator, this.locator, this.timer, this.cleaner, this, 
				this.minimumTime, this.coefficients, this.nonassignedAgents);
	}
	
	

	public void finish(){
		
		for (int i=0;i<this.testAgentsNumber;i++) {
			int pos = (int)MatsimRandom.random.nextDouble()*this.list[1].size();
			list[0].add(list[1].get(pos));
			schedulingModule.handlePlan(list[1].get(pos));
			list[1].remove(pos);
		}
		schedulingModule.finish();
		
		for (int i=0;i<list[0].size();i++){
			assignment.print(list[0].get(i).getPerson().getId()+"\t\t"+list[0].get(i).getScore()+"\t");
			for (int j=0;j<list[0].get(i).getActsLegs().size();j+=2){
				assignment.print(((Act)(list[0].get(i).getActsLegs().get(j))).getType()+"\t");
			}
			assignment.println();
		}
		assignment.println();
		
		agents = new OptimizedAgents (list[0]);
		
		Statistics.prt=false;
		this.detectCoefficients();
		Statistics.prt=true;
		
		assignmentModule.init();
		
		for (int i=0;i<list[1].size();i++){
			assignmentModule.handlePlan(list[1].get(i));
		}
		assignmentModule.finish();
		for (int i=0;i<Statistics.list.size();i++){
			for (int j=0;j<Statistics.list.get(i).size();j++){
				assignment.print(Statistics.list.get(i).get(j)+"\t");
			}
			assignment.println();
		}
		Statistics.list.clear();
	}
	
	private void detectCoefficients (){
		
		double offset = 0.5;
		double basis = this.coefficients.getPrimActsDistance();
		double [][] score = new double [2][2];
		double [][] tmp = new double [this.iterations][3];
		int inc=1;
		
		for (int i=0;i<this.iterations;i++){
			score[0][1]=basis+(i+1)*offset;
			this.coefficients.setPrimActsDistance(score[0][1]);
			score[0][0] = this.calculate();
			
			if (basis-(i+1)*offset>=0) score[1][1]=basis-(i+1)*offset;
			else {
				score [1][1] = basis+((this.iterations)+inc)*offset;
				inc += 1;
			}
			this.coefficients.setPrimActsDistance(score[1][1]);
			score[1][0] = this.calculate();
				double tmpScore = Double.MIN_VALUE;
			double x=0;
			for (int j=0;j<score.length;j++){
				if (score[j][0]>tmpScore){
					tmpScore = score[j][0];
					x = score[j][1];
				}
			}
			tmp[i][0]=tmpScore;
			tmp[i][1]= x;
		}
		double tmpScoreFinal = Double.MIN_VALUE;
		for (int i=0;i<this.iterations;i++){
			if (tmp[i][0]>tmpScoreFinal){
				tmpScoreFinal = tmp[i][0];
				this.coefficients.setPrimActsDistance(tmp[i][1]);
				System.out.println("PrimActsDistance = "+tmp[i][1]);
				System.out.println("HomeLocationDistance = "+this.coefficients.gethomeLocationDistance());
				System.out.println("Score = "+tmpScoreFinal);
				System.out.println();
			}
		}
	}
	
	private double calculate (){
		double score = 0;
		this.assignmentModule.init();
		for (int j=0;j<java.lang.Math.min(this.noOfAgents, list[1].size());j++){
			assignmentModule.handlePlan(list[1].get(j));
		}
		assignmentModule.finish();
		for (int j=0;j<java.lang.Math.min(this.noOfAgents, list[1].size());j++){
			score += this.list[1].get(j).getScore(); 
		}
		return score;
	}

}
