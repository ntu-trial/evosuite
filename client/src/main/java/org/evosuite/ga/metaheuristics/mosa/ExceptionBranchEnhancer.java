package org.evosuite.ga.metaheuristics.mosa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchCoverageFactory;
import org.evosuite.coverage.branch.BranchCoverageGoal;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.coverage.branch.BranchFitness;
import org.evosuite.coverage.fbranch.FBranchTestFitness;
import org.evosuite.ga.Chromosome;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.ga.metaheuristics.mosa.structural.MultiCriteriaManager;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.BytecodeInstructionPool;
import org.evosuite.graphs.cfg.ControlDependency;
import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.setup.CallContext;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;

public class ExceptionBranchEnhancer<T extends Chromosome> {
	private static final double EXCEPTION_THRESHOLD = 0.1;
	
	/**
	 * frequency for all covered goals
	 * Goal -> Covered Times
	 */
	private Map<FitnessFunction<T>, Integer> goalCoverageFrequency = new HashMap<>();

	/**
	 * handledGoals avoids repetitively working on the same exception goals
	 */
	private Set<ContextFitnessFunction<T>> handledExceptions = new HashSet<ContextFitnessFunction<T>>();

	/**
	 * Exception Goal -> <Corresponding Goal>
	 * note that corresponding goal can be null if it is a root or outsider 
	 * Exception goals are categorized into outsider and insider (in terms of 
	 * target method) Insiders can be categorized into root and non-root.
	 * 
	 * Only non-root insider can have value.
	 */
	private Map<ContextFitnessFunction<T>, FitnessFunction<T>> exceptionGoal2Corresponder = new HashMap<>();

	/**
	 * Exception Goal -> Occurrence Times
	 */
	private Map<ContextFitnessFunction<T>, Integer> exceptionFrequencyMap = new HashMap<>();

	/**
	 * how many times the target method is covered
	 */
	private int targetMethodCoveringTimes = 0;
	/**
	 * how many times the target method is not covered
	 */
	private int totalOutsideExceptionTimes = 0;
	
	private List<T> population;
	private MultiCriteriaManager<T> goalsManager;
	
	public ExceptionBranchEnhancer(MultiCriteriaManager<T> goalsManager) {
		super();
		this.goalsManager = goalsManager;
	}
	
	public void updatePopulation(List<T> population) {
		this.population = population;
	}
	
	public void setGoalManager(MultiCriteriaManager<T> goalsManager) {
		this.goalsManager = goalsManager;
	}
	
	private void collectMethodCoveredTimes(ExecutionResult executionResult) {
		TestCase tc = executionResult.test;
		Set<String> addedMethodNames = new HashSet<String>();
		for (int pos = 0; pos < tc.size(); pos++) {
			Statement statement = tc.getStatement(pos);
			if (statement instanceof MethodStatement) {
				MethodStatement ms = ((MethodStatement) statement);
				String fullName = ms.getMethodName() + ms.getDescriptor();
				if (addedMethodNames.contains(fullName)) {
					continue;
				}
				Integer freq = CallBlackList.calledMethods.get(fullName);
				if (freq == null) {
					freq = 0;
				}
				addedMethodNames.add(fullName);
				CallBlackList.calledMethods.put(fullName, freq + 1);
			}
		}
	}

	public void enhanceBranchGoals() {
		/**
		 * disabling the mock framework so that we can get the actual stack for an
		 * exception
		 */
		MockFramework.disable();

		for (T individual : this.population) {
			ExecutionResult executionResult = ((TestChromosome) individual).getLastExecutionResult();
			Map<Integer, Integer> falseGoals = executionResult.getTrace().getCoveredFalse();
			Map<Integer, Integer> trueGoals = executionResult.getTrace().getCoveredTrue();
			Collection<Throwable> allExceptions = executionResult.getAllThrownExceptions();

			// className -> methodName -> lineNumber -> coveredTimes
			Map<String, Map<String, Map<Integer, Integer>>> coverageMap = executionResult.getTrace().getCoverageData();

			if (coverageMap.get(Properties.TARGET_CLASS) != null
					&& coverageMap.get(Properties.TARGET_CLASS).get(Properties.TARGET_METHOD) != null) {
				targetMethodCoveringTimes++;
			} else {
				totalOutsideExceptionTimes++;
			}

			/**
			 * TODO (high) linyun, the branch coverage is not context sensitive.
			 * 
			 * we collect the number of coverage of each goal (i.e., branch) so that we can
			 * detect how many "bombs" between each goal.
			 */
			collectCoveredTimes(falseGoals, false);
			collectCoveredTimes(trueGoals, true);
			
			collectMethodCoveredTimes(executionResult);
			
			if (!allExceptions.isEmpty()) {
				Throwable thrownException = allExceptions.iterator().next();

				StackTraceElement[] stack = thrownException.getStackTrace();
				ContextFitnessFunction<T> newExceptionGoal = createNewGoals(thrownException, stack, 0);
				
				/**
				 * record what method call can trigger exception
				 */
				StackTraceElement elementToCallException = findElementForException(stack);
				if(elementToCallException != null) {
					String methodSig = BranchEnhancementUtil.covert2Sig(elementToCallException);
					if(methodSig == null) {
						/**
						 * TODO, it means some method is not instrumented.
						 */
					}
					
					Integer freq = CallBlackList.exceptionTriggeringCall.get(methodSig);		
					if(freq == null) {
						freq = 0;
					}
					// methodSig doesn't correspond to any class here
					CallBlackList.exceptionTriggeringCall.put(methodSig, freq+1);
				}
				
				/**
				 * record the where the exception happens.
				 */
				if (newExceptionGoal != null) {
					FitnessFunction<T> goalInGraph = getCorrespondingGoalInGraph(stack, newExceptionGoal.getFitnessFunction());
					exceptionGoal2Corresponder.put(newExceptionGoal, goalInGraph);

					Integer freq = exceptionFrequencyMap.get(newExceptionGoal);
					if (freq == null) {
						freq = 0;
					}
					exceptionFrequencyMap.put(newExceptionGoal, freq+1);

				} else {
					/**
					 *  means that the goal for the exception is not instrumented
					 */
					
				}
			}
		}

		boolean needToEvolveGoalGraph = needToEvolveGoalGraph();
		
		if(needToEvolveGoalGraph) {
			evolveGoalGraphWithException();			
		}
		
		MockFramework.enable();

	}

	private StackTraceElement findElementForException(StackTraceElement[] stack) {
		StackTraceElement target = null;
		// Not sure what this means
		for(StackTraceElement element: stack) {
			if(element.getClassName().startsWith("sun.")) {
				break;
			}
			target = element;
		}
		
		return target;
	}

	private boolean needToEvolveGoalGraph() {
		for(FitnessFunction<T> goal: this.goalsManager.getCurrentGoals()) {
			if(this.handledExceptions.contains(goal)) {
				return false;
			}
		}
		return true;
	}

	private ContextFitnessFunction<T> createNewGoals(Throwable exception, StackTraceElement[] stack, int level) {
		Set<ControlDependency> cds = new HashSet<ControlDependency>();
		if (stack != null && stack.length > level && stack[level] != null) {
			String className = stack[level].getClassName();
			int lineNum = stack[level].getLineNumber();

			List<BytecodeInstruction> insList = BytecodeInstructionPool
					.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT())
					.getAllInstructionsAtClass(className, lineNum);

			if(insList != null) {
				for (BytecodeInstruction ins : insList) {
					if (ins.getASMNodeString().contains(exception.getClass().getSimpleName())) {
						cds = ins.getControlDependencies();
						
						if (cds != null && cds.size() > 0) {
							for (ControlDependency cd : cds) {
								FitnessFunction<T> fitness = createOppFitnessFunction(cd);
								CallContext context = new CallContext(stack);
								return new ContextFitnessFunction<T>(context, fitness);
							}
							
							break;
						} else {
							ContextFitnessFunction<T> fitness = createNewGoals(exception, stack, level + 1);
							return fitness;
						}
					}
				}
			}
			
		}

		return null;
	}
	
	/**
	 * TODO (high) for ziheng, need to consider the call graph
	 * 
	 * return null if the exception is not incurred from the target method.
	 * 
	 * @param stack
	 * @param newExceptionGoal
	 * @return
	 */
	private FitnessFunction<T> getCorrespondingGoalInGraph(StackTraceElement[] stack, FitnessFunction<T> newExceptionGoal) {
		for (StackTraceElement element : stack) {
			List<FitnessFunction<T>> allCorrespondingGolas = new ArrayList<FitnessFunction<T>>();
			allCorrespondingGolas.addAll(this.goalsManager.getCurrentGoals());
			allCorrespondingGolas.addAll(this.goalsManager.getCoveredGoals());
			
			for (FitnessFunction<T> ff : allCorrespondingGolas) {
				if(ff instanceof BranchFitness) {
					BranchCoverageGoal branchGoal = ((BranchFitness)(ff)).getBranchGoal();
					String branchClassName = branchGoal.getBranch().getInstruction().getClassName();
					String branchMethodName = branchGoal.getBranch().getInstruction().getMethodName();
					if (element.getClassName().equals(branchClassName)
							&& element.getMethodName().equals(branchMethodName.split(Pattern.quote("("))[0])) {
						List<BytecodeInstruction> insList = BytecodeInstructionPool
								.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT())
								.getAllInstructionsAtLineNumber(element.getClassName(), branchMethodName,
										element.getLineNumber());
						if (insList == null) {
							System.currentTimeMillis();
							continue;
						}
						
						Set<ControlDependency> cds = insList.get(0).getControlDependencies();
						
						if (cds.isEmpty()) {
							return null;
						}
						
						/**
						 * Choose an arbitrary control dependency
						 */
						ControlDependency cd = cds.iterator().next();
						while(cd != null) {
							if (cd.getBranch().equals(branchGoal.getBranch())
									&& cd.getBranchExpressionValue() == branchGoal.getValue()) {
								return ff;
							}
							
							cds = cd.getBranch().getInstruction().getControlDependencies();
							cd = cds.isEmpty() ? null : cds.iterator().next();
						}
					}
					
				}
				
			}
		}

		return null;
	}

	/**
	 * Update covered times for every goal
	 * @param goals
	 * @param value
	 */
	private void collectCoveredTimes(Map<Integer, Integer> goals, boolean value) {
		List<FitnessFunction<T>> totalGoals = new ArrayList<FitnessFunction<T>>();
		totalGoals.addAll(this.goalsManager.getCurrentGoals());
		totalGoals.addAll(this.goalsManager.getCoveredGoals());
		for (Integer goalID : goals.keySet()) {
			for (FitnessFunction<T> ff : totalGoals) {
				if(ff instanceof BranchFitness) {
					BranchCoverageGoal g = ((BranchFitness)ff).getBranchGoal();
					if (g.getId() == goalID
							&& g.getValue() == value) {
						if (goalCoverageFrequency.get(ff) == null) {
							goalCoverageFrequency.put(ff, 1);
						} else {
							goalCoverageFrequency.replace(ff, goalCoverageFrequency.get(ff) + 1);
						}
					}
					
				}
				
				
			}
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private FitnessFunction<T> createOppFitnessFunction(ControlDependency cd) {
		Branch branch = cd.getBranch();
		boolean value = cd.getBranchExpressionValue();
		Class clazz = checkCriterion();
		BranchCoverageGoal goal = new BranchCoverageGoal(branch, !value, cd.getBranch().getClassName(),
				cd.getBranch().getMethodName());
		if (clazz.equals(BranchCoverageTestFitness.class)) {
			return (FitnessFunction<T>) BranchCoverageFactory.createBranchCoverageTestFitness(branch, !value);
		} else if (clazz.equals(FBranchTestFitness.class)) {
			return (FitnessFunction<T>) new FBranchTestFitness(goal);
		} else {
			return null;
		}
	}

	private void evolveGoalGraphWithException() {
		Map<ContextFitnessFunction<T>, Double> exceptionOccuringProbability = updateExceptionOcurringProbability(
				exceptionGoal2Corresponder, exceptionFrequencyMap, goalCoverageFrequency, targetMethodCoveringTimes,
				totalOutsideExceptionTimes);
		
		ContextFitnessFunction<T> exceptionGoal = findTopValidException(exceptionOccuringProbability);
		System.currentTimeMillis();
		if(exceptionGoal != null) {
			FitnessFunction<T> corresponder = exceptionGoal2Corresponder.get(exceptionGoal);
			if(corresponder == null) {
				updateRoot(exceptionGoal);
			}
			else {
				updatePath(exceptionGoal, corresponder);
			}
			
			handledExceptions.add(exceptionGoal);	
		}
		
		resetStates();
	}
	
	private ContextFitnessFunction<T> findTopValidException(
			Map<ContextFitnessFunction<T>, Double> exceptionOccuringProbability) {
		
		List<ContextFitnessFunction<T>> rankedExceptionGoalWithStructure = rankExceptionGoalWithStructure(exceptionOccuringProbability);
		for(ContextFitnessFunction<T> exceptionGoal: rankedExceptionGoalWithStructure) {
			if(exceptionOccuringProbability.get(exceptionGoal) > EXCEPTION_THRESHOLD
					&& !exceptionGoal.isContextAvoidable()
					&& !handledExceptions.contains(exceptionGoal)) {
				return exceptionGoal;
			}
		}
		
		return null;
	}

	/**
	 * TODO (high) ziheng, find the most top exception goal
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private List<ContextFitnessFunction<T>> rankExceptionGoalWithStructure(
			Map<ContextFitnessFunction<T>, Double> exceptionOccuringProbability) {
		List<ContextFitnessFunction<T>> list = new ArrayList<ContextFitnessFunction<T>>(exceptionOccuringProbability.keySet());
		
		//TODO 
		list.sort(new Comparator() {
			@Override
			public int compare(Object o1, Object o2) {
				FBranchTestFitness f1 = (FBranchTestFitness)((ContextFitnessFunction)o1).getFitnessFunction();
				FBranchTestFitness f2 = (FBranchTestFitness)((ContextFitnessFunction)o2).getFitnessFunction();
				return f1.getBranch().getInstruction().getLineNumber() - f2.getBranch().getInstruction().getLineNumber();
			}
		});
		
		return list;
	}

	private void resetStates() {
		this.exceptionFrequencyMap.clear();
		this.goalCoverageFrequency.clear();
		this.totalOutsideExceptionTimes = 0;
		this.targetMethodCoveringTimes = 0;
	}

	@SuppressWarnings("unchecked")
	private void updateRoot(ContextFitnessFunction<T> newGoal) {
		this.goalsManager.getBranchFitnessGraph().updateRoot((FitnessFunction<T>)newGoal);
		this.goalsManager.getCurrentGoals().clear();
		this.goalsManager.getCurrentGoals().add((FitnessFunction<T>)newGoal);
		this.goalsManager.updateBranchGoal((FitnessFunction<T>)newGoal);
	}

	@SuppressWarnings("unchecked")
	private void updatePath(ContextFitnessFunction<T> newGoal, FitnessFunction<T> parentGoal) {
		this.goalsManager.getBranchFitnessGraph().updatePath((FitnessFunction<T>)newGoal, parentGoal);
		this.goalsManager.getCurrentGoals().add((FitnessFunction<T>)newGoal);
		this.goalsManager.updateBranchGoal((FitnessFunction<T>)newGoal);
		for (FitnessFunction<T> child : ((MultiCriteriaManager<T>) goalsManager).getBranchFitnessGraph()
				.getStructuralChildren((FitnessFunction<T>)newGoal)) {
			this.goalsManager.getCurrentGoals().remove(child);
		}
	}

	private Map<ContextFitnessFunction<T>, Double> updateExceptionOcurringProbability(
			Map<ContextFitnessFunction<T>, FitnessFunction<T>> exceptionGoal2Corresponder,
			Map<ContextFitnessFunction<T>, Integer> exceptionFrequencyMap,
			Map<FitnessFunction<T>, Integer> goalCoverageFrequency, 
			int targetMethodCoveringTimes,
			int totalOutsideExceptionTimes) {
		Map<ContextFitnessFunction<T>, Double> exceptionOccuringProbability = new HashMap<ContextFitnessFunction<T>, Double>();

		for (ContextFitnessFunction<T> exceptionGoal : exceptionGoal2Corresponder.keySet()) {
			FitnessFunction<T> corresponder = exceptionGoal2Corresponder.get(exceptionGoal);

			Integer freq = exceptionFrequencyMap.get(exceptionGoal);
			if(freq == null) {
				continue;
			}
			
			Integer totalCoverage = null;
			Double prob = 0.0;		
			//Non-root insider
			if (corresponder != null) {
				totalCoverage = goalCoverageFrequency.get(corresponder);
				prob = freq * 1.0 / totalCoverage;
			} else {
				totalCoverage = exceptionGoal.isInTarget() ? targetMethodCoveringTimes
						: totalOutsideExceptionTimes;
				prob = freq * 1.0 / totalCoverage;
			}
			exceptionOccuringProbability.put(exceptionGoal, prob);
		}

		return exceptionOccuringProbability;
	}
	
	
	@SuppressWarnings("rawtypes")
	private Class checkCriterion() {
		for (Properties.Criterion criterion : Properties.CRITERION) {
			switch (criterion) {
			case FBRANCH:
				return FBranchTestFitness.class;
			case BRANCH:
				return BranchCoverageTestFitness.class;
			default:
				return null;
			}
		}
		return null;
	}

}
