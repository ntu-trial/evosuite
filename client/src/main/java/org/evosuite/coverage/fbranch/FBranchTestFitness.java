package org.evosuite.coverage.fbranch;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchCoverageGoal;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.graphs.cfg.ControlDependency;
import org.evosuite.setup.Call;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;

/**
 * F stands for flag fitness
 * 
 * @author linyun
 *
 */
public class FBranchTestFitness extends BranchCoverageTestFitness {

	private static final long serialVersionUID = -3538507758440177708L;
//	private static Logger logger = LoggerFactory.getLogger(FBranchTestFitness.class);

//	private final BranchCoverageGoal goal;
//	private boolean inconsistencyHappen = false;

	public FBranchTestFitness(BranchCoverageGoal branchGoal) {
		super(branchGoal);
	}

	public Branch getBranch() {
		return this.goal.getBranch();
	}

	@Override
	public double getFitness(TestChromosome individual, ExecutionResult result) {
//		this.inconsistencyHappen = false;
		/**
		 * if the result does not exercise this branch node, we do not further process the detailed
		 * branch distance as we need to pass its parent branch node.
		 */
		Double value = null;
		if(this.goal.getValue()) {
			value = result.getTrace().getTrueDistances().get(this.goal.getBranch().getActualBranchId());
		}
		else {
			value = result.getTrace().getFalseDistances().get(this.goal.getBranch().getActualBranchId());
		}
		
		if(value != null && value == 0) {
			return 0;
		}
		
		FlagEffectResult r = FlagEffectEvaluator.checkFlagEffect(goal);
		if(!r.hasFlagEffect) {
			if(value == null){
				Double branchDisntanceWithApproachLevel = getBranchDisntanceWithApproachLevel(result, this.goal);
				if(branchDisntanceWithApproachLevel != null) {
					return normalize(branchDisntanceWithApproachLevel);					
				}
				
				return 1;
			}
			else {
				return normalize(value);
			}
		}
		else {
			List<Call> callContext = new ArrayList<>();
			callContext.add(r.call);
			
			double interproceduralFitness = FlagEffectEvaluator.calculateInterproceduralFitness(
					r.interproceduralFlagCall, callContext, goal, result);
			double normalizedFitness = normalize(interproceduralFitness);
			
			System.currentTimeMillis();
			return normalizedFitness;
		}
	}
	
	private Double getBranchDisntanceWithApproachLevel(ExecutionResult result, BranchCoverageGoal goal) {
		
		Set<ControlDependency> cds = goal.getBranch().getInstruction().getControlDependencies();
		
		if(cds.isEmpty()) {
			return null;
		}
		else {
			ControlDependency cd = cds.iterator().next();
			Double value = null;
			if(cd.getBranchExpressionValue()) {
				value = result.getTrace().getTrueDistances().get(cd.getBranch().getActualBranchId());
			}
			else {
				value = result.getTrace().getFalseDistances().get(cd.getBranch().getActualBranchId());
			}
			
			if(value != null) {
				return 1 + normalize(value);
			}
			else {
				String className = cd.getBranch().getInstruction().getClassName();
				String methodName = cd.getBranch().getInstruction().getMethodName();
				
				BranchCoverageGoal newGoal = new BranchCoverageGoal(cd, className, methodName);
				Double subValue = getBranchDisntanceWithApproachLevel(result, newGoal);
				if(subValue != null) {
					return 1 + subValue;
				}
				else {
					return null;
				}
			}
		}
		
	}

	@Override
	public int compareTo(TestFitnessFunction other) {
		if (other instanceof FBranchTestFitness) {
			FBranchTestFitness otherBranchFitness = (FBranchTestFitness) other;
			return getBranchGoal().compareTo(otherBranchFitness.getBranchGoal());
		} else if (other instanceof BranchCoverageTestFitness) {
			BranchCoverageTestFitness otherBranchFitness = (BranchCoverageTestFitness) other;
			return getBranchGoal().compareTo(otherBranchFitness.getBranchGoal());
		}
		return compareClassName(other);
	}

	public BranchCoverageGoal getBranchGoal() {
		return goal;
	}

//	public boolean isInconsistencyHappen() {
//		return inconsistencyHappen;
//	}
//
//	public void setInconsistencyHappen(boolean inconsistencyHappen) {
//		this.inconsistencyHappen = inconsistencyHappen;
//	}

	public String getClassName() {
		return this.goal.getClassName();
	}

	public String getMethod() {
		return this.goal.getMethodName();
	}

	public boolean getBranchExpressionValue() {
		return this.goal.getValue();
	}

}
