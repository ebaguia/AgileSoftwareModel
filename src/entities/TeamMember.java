package entities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import core.Main;
import core.Statistics;
import enums.MemberRole;
import enums.SkillArea;
import enums.TaskAllocationStrategy;

public class TeamMember {
	private int id;
	private String firstName;
	private String lastName;
	private int completedStoryPoints;
	private Map<SkillArea, Double> expertiseInSkillAreas;
	private MemberRole role;
	private Random breakTask;
	private List<String> standardLog;
	private List<String> extraInfoLog;
	private String PICKED, ACCEPTED, REJECTED;
	private String PICKED_MESSAGE, ACCEPTED_MESSAGE, REJECTED_MESSAGE;
		
	public TeamMember(int id, String firstName, String lastName, MemberRole role){
		this.id = id;
		this.firstName = firstName;
		this.lastName = lastName;
		this.role = role;
		this.completedStoryPoints = 0;
		expertiseInSkillAreas = new HashMap<>();
		expertiseInSkillAreas.put(SkillArea.BackEnd, 0d);
		expertiseInSkillAreas.put(SkillArea.FrontEnd, 0d);
		expertiseInSkillAreas.put(SkillArea.Design, 0d);
		standardLog = new ArrayList<>();
		extraInfoLog = new ArrayList<>();
		breakTask = new Random();
		PICKED = "PICKED"; ACCEPTED = "ACCEPTED"; REJECTED = "REJECTED";
		PICKED_MESSAGE = "picked for inspection";
		ACCEPTED_MESSAGE = "accepted for execution";
		REJECTED_MESSAGE = " cannot finish task within the remaining time";
	}
	
	/*before a member starts performing a task, it checks if it can do the task before the 
	 * end of sprint reaches, if not, it will return the task to the task board, and removes
	 * it from the tasks in progress, and looks for another task.
     * in fact, the worker keeps looking for a task, until it finds one that it can execute before
	 * the deadline. If no task is found as such, then the first chosen task is broken into two smaller
	 * tasks (smaller story points), the one that can be done within the time limit. 
	 * Once a task is chosen, the member returns all the rejected tasks back to the "toDoTasks", and adds 
	 * the chosen task to the "tasksInProgress" list, then updates the gui!*/
	public void startWorking(CountDownLatch latch){
		standardLog.clear();
		extraInfoLog.clear();
		Team team = Team.getTeam();
		TaskBoard taskBoard = team.getTaskBoard();
		Task chosenTask = null;
		boolean memberFinishedForThisSprint = false;
		while(team.getTeamWorking() && !memberFinishedForThisSprint){
			chosenTask = taskBoard.pollTask(this, false);
			if(chosenTask != null){
				logExtraInfo(chosenTask.getTaskID(), chosenTask.getStoryPoints(), PICKED, PICKED_MESSAGE);
				if(!memberCanPerformTask(chosenTask)){
					logExtraInfo(chosenTask.getTaskID(), chosenTask.getStoryPoints(), REJECTED, getFirstName() + REJECTED_MESSAGE);
					boolean foundTask = false; 
					List<Task> rejectedTasks = new ArrayList<>();
					rejectedTasks.add(chosenTask);
							
					while(!taskBoard.toDoTasksListIsEmpty()){//whilst there are tasks in the toDoTasks list to poll
						//keep in mind, that this worker has yet not released the lock!
						Task tempTask = taskBoard.pollTask(this, true);
						logExtraInfo(tempTask.getTaskID(), tempTask.getStoryPoints(), PICKED, PICKED_MESSAGE);
						if(memberCanPerformTask(tempTask)){
							returnRejectedTasks(rejectedTasks, taskBoard);
							logExtraInfo(tempTask.getTaskID(), tempTask.getStoryPoints(), ACCEPTED, ACCEPTED_MESSAGE);
							chosenTask = tempTask;
							foundTask = true;
							break;
						}else{
							logExtraInfo(tempTask.getTaskID(), tempTask.getStoryPoints(), REJECTED, getFirstName() + REJECTED_MESSAGE);
							rejectedTasks.add(tempTask);
						}
					}
					if(!foundTask){
						logExtraInfo(chosenTask.getTaskID(), chosenTask.getStoryPoints(), PICKED, "no other tasks could be accepted-trying to break this task");
						//if worker went through all tasks, and could not find a task,
						//gets the first chosen task and breaks it into smaller tasks.
						Task spareTask = breakTask(chosenTask);
						if(spareTask.getStoryPoints() == 0) {
							logExtraInfo(chosenTask.getTaskID(), chosenTask.getStoryPoints(), REJECTED, "could not break this task-" + getFirstName() + " finished for this sprint"); 
							memberFinishedForThisSprint = true;
						}
						else if(spareTask.getStoryPoints() != 0) {
							logExtraInfo(chosenTask.getTaskID(), chosenTask.getStoryPoints(), ACCEPTED, "broke task into smaller parts-" + ACCEPTED_MESSAGE);
							rejectedTasks.remove(chosenTask);//replace chosen task with the spare task.
							rejectedTasks.add(0, spareTask);
						}
						returnRejectedTasks(rejectedTasks, taskBoard);
					}
				}
				if(!memberFinishedForThisSprint) {
					logExtraInfo(chosenTask.getTaskID(), chosenTask.getStoryPoints(), ACCEPTED, ACCEPTED_MESSAGE);
					chosenTask.setPerformerID(getID());
					taskBoard.addToTasksInProgress(chosenTask);
					taskBoard.releaseTaskLock(this);
					logInfo(chosenTask.getTaskID(), chosenTask.getStoryPoints(), true);
					execute(team.convertTctToSystemTime(calculateTaskCompletionTime(chosenTask)), chosenTask);					
					adjustExpertiseLevels(chosenTask);
					logInfo(chosenTask.getTaskID(), chosenTask.getStoryPoints(), false);
					taskBoard.submitPerformedTaskToBoard(chosenTask);
				}else {
					taskBoard.releaseTaskLock(this);
				}
			}else if(chosenTask == null) {
				memberFinishedForThisSprint = true;
				taskBoard.releaseTaskLock(this);
			}		
		}
		Statistics.getStatRecorder().logPersonnelInfo(this, standardLog);
		Statistics.getStatRecorder().logExtraPersonnelInfo(this, extraInfoLog);
		latch.countDown();
		System.out.println(getFirstName() + " with ID " + getID() + " is done for this sprint");
	}
	
	private void execute(long time, Task task){
		try{
			Thread.sleep(time);
		}catch(Exception e){
			logExtraInfo(task.getTaskID(), task.getStoryPoints(), "EXCEPTION", getFirstName() + " encountered exception while performing task");
			Main.issueErrorMessage("Worker " + getFirstName() + " encountered probelm\n" + e.getMessage());
		}
	}
	
	private boolean memberCanPerformTask(Task task){
		Team team = Team.getTeam();
		double tct = calculateTaskCompletionTime(task);
		double timeLeftToDeadline = team.getTimeLeftToDeadline();
		if(tct <= timeLeftToDeadline)
			return true;
		else
			return false;
	}
	
	private void adjustExpertiseLevels(Task chosenTask){
		Team team = Team.getTeam();
		int storyPoints = chosenTask.getStoryPoints();
		double highestExpertiseLevel = team.getHighExpertiseHigherBoundary();
		Set<SkillArea> requiredSkills = chosenTask.getRequiredSkillAreas();
		
		for(SkillArea skill : requiredSkills){			
			
			double expertiseInSkillArea = expertiseInSkillAreas.get(skill);
			double progressInThisSkillArea = storyPoints * Team.getTeam().getProgressPerStoryPoint();
			expertiseInSkillArea += progressInThisSkillArea;
			
			if(expertiseInSkillArea > highestExpertiseLevel)
				expertiseInSkillArea = highestExpertiseLevel;
			
			expertiseInSkillAreas.put(skill, expertiseInSkillArea);
		}
	}
	
	private void returnRejectedTasks(List<Task> rejectedTasks, TaskBoard taskBoard){
		for(Task rejectedTask : rejectedTasks){
			taskBoard.rejectTask(rejectedTask);
		}
	}
	
	private Task breakTask(Task chosenTask){
		Task spareTask = new Task(chosenTask.getTaskID(), chosenTask.getTaskName(), 0, chosenTask.getRequiredSkillAreas());
		
		if(!breakTask())//only break tasks in fifty percent of the cases, not always.
			return spareTask;
	
		double timeLeft = Team.getTeam().getTimeLeftToDeadline();
		double averageExpertise = calculateAverageExpertiseCoefficient(chosenTask.getRequiredSkillAreas());
		double supposedStoryPoint = (timeLeft * averageExpertise) / Team.getTeam().getStoryPointCoefficient();
		
		int idealStoryPoints = Double.valueOf(supposedStoryPoint).intValue();
		int initialStoryPoints = chosenTask.getStoryPoints();
		int spareTaskStoryPoints = initialStoryPoints - idealStoryPoints;
		
		if(idealStoryPoints == 0) {
			//swap them!
			int temp = idealStoryPoints;
			idealStoryPoints = spareTaskStoryPoints;
			spareTaskStoryPoints = temp;
		}
		
		spareTask.setStoryPoints(spareTaskStoryPoints);
		spareTask.setPriority(chosenTask.getPriority());
		spareTask.setTaskDescription(chosenTask.getTaskDescription());
		chosenTask.setStoryPoints(idealStoryPoints);
		return spareTask;
	}
	
	private double calculateAverageExpertiseCoefficient(Set<SkillArea> requiredSkillAreas){
		int overallExpertise = 0;
		try{			
			for(SkillArea skillArea : requiredSkillAreas){
				overallExpertise += getExpertiseCoefficient(expertiseInSkillAreas.get(skillArea));
			}
		}catch(IllegalArgumentException e){
			Main.issueErrorMessage(e.getMessage());
		}catch(Exception e) {
			System.out.println("exception has been incured for worker " + getID() + " in calculate average expertise");
		}
		
		double averageExpertise =  ((double) overallExpertise / (double) requiredSkillAreas.size());
		return averageExpertise;
	}
	
	private void logInfo(int taskID, int storyPoints, boolean started) {
		//sprint,systemTime,modelTime,taskID,storyPoints,start/end,BackEnd Exp,FrontEnd Exp,Design Exp
		Team team = Team.getTeam();
		long startTime = team.getSprintStartTime();
		long systemTime = System.currentTimeMillis() - startTime;
		int sprintNo = team.getCurrentSprint();
		int systemToModelTimeCoef = team.getSystemToModelTimeCoefficient();
		String startEnd = (started) ? "start" : "end";
		long mt = systemTime / systemToModelTimeCoef;
		int modelTime = (int) mt + ((sprintNo-1)*team.getHoursPerSpring());
		String record = sprintNo + "," + systemTime + "," + modelTime + "," + taskID + "," + storyPoints + "," + startEnd + ","	+ getExpertiseAtSkillArea(SkillArea.BackEnd) 
				+ "," + getExpertiseAtSkillArea(SkillArea.FrontEnd) + "," + getExpertiseAtSkillArea(SkillArea.Design);
		standardLog.add(record);
	}
	
	private void logExtraInfo(int taskID, int storyPoints, String status, String description) {
		//sprint,systemTime,modelTime,task,storyPoints,picked/accepted/rejected,description
		Team team = Team.getTeam();
		long startTime = team.getSprintStartTime();
		long systemTime = System.currentTimeMillis() - startTime;
		int sprintNo = team.getCurrentSprint();
		int systemToModelTimeCoef = team.getSystemToModelTimeCoefficient();
		long mt = systemTime / systemToModelTimeCoef;
		int modelTime = (int) mt + ((sprintNo-1)*team.getHoursPerSpring());
		String record = sprintNo + "," + systemTime + "," + modelTime + "," + taskID + "," + storyPoints + "," + status + "," + description;
		extraInfoLog.add(record);
	}
	
	/*
	 * There is only a %50 chance to get a task broken into smaller tasks,
	 * this cannot happen every time when a task is too big to be done before
	 * the deadline. 
	 */
	private boolean breakTask() {
		int randNo = (breakTask.nextInt(100) + 1);
		if(randNo >= 1 && randNo <= 50)
			return true;
		else 
			return false;
	}
	
	/**
	 * In this model, motivation is directly proportional to learning potential if
	 * the task allocation strategy is learning-based, as is inversely proportional
	 * to learning potential if the task allocation strategy is expertise-based.
	 * @param task
	 * @return
	 */
	public double calculateMotivation(Task task){
		Team team = Team.getTeam();
		int storyPoints = task.getStoryPoints();
		double learningPotential = 0d;
		double progressPerStoryPoint = team.getProgressPerStoryPoint();
		double highestExpertiseLevel = team.getHighExpertiseHigherBoundary();
		Set<SkillArea> requiredSkillAreas = task.getRequiredSkillAreas();
		try {
			for(SkillArea skillArea : requiredSkillAreas){
				double expertiseInThisSkillArea = expertiseInSkillAreas.get(skillArea);
				if(expertiseInThisSkillArea < highestExpertiseLevel){
					double potentialProgressInThisSkillArea = storyPoints * progressPerStoryPoint;
					if ((expertiseInThisSkillArea + potentialProgressInThisSkillArea) > highestExpertiseLevel){
						potentialProgressInThisSkillArea = highestExpertiseLevel - expertiseInThisSkillArea;
					}
					learningPotential += potentialProgressInThisSkillArea; 
				}
			}
		}catch(Exception e) {
			System.out.println("An exception occurred for worker " + getID() + " when calculating motivation");
		}
		
		if(team.getTaskAllocationStrategy().equals(TaskAllocationStrategy.ExpertiseBased)){
			if(learningPotential != 0d)
				learningPotential = 1d / learningPotential;
			else
				learningPotential = Double.MAX_VALUE;
		}
		
		return learningPotential;
	}
	
	public double calculateTaskCompletionTime(Task task){
		Set<SkillArea> requiredSkillAreas = task.getRequiredSkillAreas();
		
		int storyPoints = task.getStoryPoints();
		
		double averageExpertise = calculateAverageExpertiseCoefficient(requiredSkillAreas);
		
		double tct =  Team.getTeam().getStoryPointCoefficient() * storyPoints / averageExpertise;
		return tct;
	}
	
	public int getExpertiseCoefficient(double expertis) throws IllegalArgumentException{
		Team team = Team.getTeam();
		int expertiseLevel = (int) Math.ceil(expertis);
		if(expertiseLevel >= team.getLowExpertiseLowerBoundary() && expertiseLevel <= team.getLowExpertiseHigherBoundary())
			return team.getLowExpertiseCoefficient();
		else if(expertiseLevel >= team.getMediumExpertiseLowerBoundary() && expertiseLevel <= team.getMediumExpertiseHigherBoundary())
			return team.getMediumExpertiseCoefficient();
		else if(expertiseLevel >= team.getHighExpertiseLowerBoundary() && expertiseLevel <= team.getHighExpertiseHigherBoundary())
			return team.getHighExpertiseCoefficient();
		else
			throw new IllegalArgumentException("Expertise level in a skill area must be between 0 and 30 inclusive! Received expertise level: " + expertiseLevel);
	}
	
	public String getFirstName(){
		return this.firstName;
	}
	
	public void setFirstName(String name){
		this.firstName = name;
	}
	
	public String getLastName(){
		return this.lastName;
	}
	
	public void setLastName(String name){
		this.lastName = name;
	}
	
	public int getID(){
		return this.id;
	}
	
	public void setID(int id){
		this.id = id;
	}
	
	public MemberRole getMemberRole(){
		return this.role;
	}
	
	public void setMemberRole(MemberRole role){
		this.role = role;
	}
	
	public double getExpertiseAtSkillArea(SkillArea skillArea){
		if(skillArea.equals(SkillArea.Testing))
			return 0d;
		else
			return expertiseInSkillAreas.get(skillArea);
	}
	
	public void setExpertiseAtSkillArea(SkillArea skillArea, double expertise){
		if(skillArea.equals(SkillArea.Testing))
			throw new IllegalArgumentException("EXPERTISE LEVEL FOR TESTING SKILL IS NOT SUPPORTED AT THE MOMENT");
		else{
			expertiseInSkillAreas.put(skillArea, expertise);
		}
	}
	
	public int getCompletedStoryPoints(){
		return this.completedStoryPoints;
	}
	
	public void setCompletedStoryPoints(int storyPoints){
		this.completedStoryPoints = storyPoints;
	}
}
