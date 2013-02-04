/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2013  Dirk Beyer
 *  All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 *  CPAchecker web page:
 *    http://cpachecker.sosy-lab.org
 */
package org.sosy_lab.cpachecker.cpa.predicate;

import static org.sosy_lab.cpachecker.cpa.predicate.ImpactUtils.*;
import static org.sosy_lab.cpachecker.util.StatisticsUtils.div;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import org.sosy_lab.common.LogManager;
import org.sosy_lab.common.Pair;
import org.sosy_lab.common.Timer;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.cpachecker.core.CPAcheckerResult.Result;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;
import org.sosy_lab.cpachecker.core.reachedset.ReachedSet;
import org.sosy_lab.cpachecker.cpa.arg.ARGReachedSet;
import org.sosy_lab.cpachecker.cpa.arg.ARGState;
import org.sosy_lab.cpachecker.exceptions.CPAException;
import org.sosy_lab.cpachecker.exceptions.RefinementFailedException;
import org.sosy_lab.cpachecker.util.predicates.Solver;
import org.sosy_lab.cpachecker.util.predicates.interfaces.BooleanFormula;
import org.sosy_lab.cpachecker.util.predicates.interfaces.view.BooleanFormulaManagerView;
import org.sosy_lab.cpachecker.util.predicates.interfaces.view.FormulaManagerView;

/**
 * Refinement strategy similar to McMillan's Impact algorithm.
 * The states of the ARG are strengthened by conjunctively adding the interpolants to them.
 */
class ImpactRefinementStrategy implements RefinementStrategy {

  private class Stats implements Statistics {

    private int totalPathLengthToInfeasibility = 0; // measured in blocks
    private int totalUnchangedPrefixLength = 0; // measured in blocks
    private int totalNumberOfAffectedStates = 0;

    private final Timer itpCheck  = new Timer();
    private final Timer coverTime = new Timer();
    private final Timer argUpdate = new Timer();

    @Override
    public String getName() {
      return "Impact Refiner";
    }

    @Override
    public void printStatistics(PrintStream out, Result pResult, ReachedSet pReached) {
      int numberOfRefinements = argUpdate.getNumberOfIntervals();

      out.println("  Checking whether itp is new:        " + itpCheck);
      out.println("  Coverage checks:                    " + coverTime);
      out.println("  ARG update:                         " + argUpdate);
      out.println();
      out.println("Avg. length of refined path (in blocks):    " + div(totalPathLengthToInfeasibility, numberOfRefinements));
      out.println("Avg. number of blocks unchanged in path:    " + div(totalUnchangedPrefixLength, numberOfRefinements));
      out.println("Avg. number of affected states:             " + div(totalNumberOfAffectedStates, numberOfRefinements));
    }
  }

  private final Stats stats = new Stats();

  private final FormulaManagerView fmgr;
  private final BooleanFormulaManagerView bfmgr;
  private final Solver solver;

  protected ImpactRefinementStrategy(final Configuration config, final LogManager logger,
      final FormulaManagerView pFmgr, final Solver pSolver) throws InvalidConfigurationException, CPAException {

    solver = pSolver;
    fmgr = pFmgr;
    bfmgr = pFmgr.getBooleanFormulaManager();
  }

  @Override
  public void performRefinement(ARGReachedSet pReached, List<ARGState> path,
      List<BooleanFormula> interpolants, boolean pRepeatedCounterexample) throws CPAException {

    startRefinementOfPath();

    ARGState lastElement = path.get(path.size()-1);
    assert lastElement.isTarget();

    path = path.subList(0, path.size()-1); // skip last element, itp is always false there
    assert interpolants.size() ==  path.size();

    List<ARGState> changedElements = new ArrayList<>();
    ARGState infeasiblePartOfART = lastElement;
    for (Pair<BooleanFormula, ARGState> interpolationPoint : Pair.zipList(interpolants, path)) {
      stats.totalPathLengthToInfeasibility++;
      BooleanFormula itp = interpolationPoint.getFirst();
      ARGState w = interpolationPoint.getSecond();

      if (bfmgr.isTrue(itp)) {
        // do nothing
        stats.totalUnchangedPrefixLength++;
        continue;
      }

      if (bfmgr.isFalse(itp)) {
        // we have reached the part of the path that is infeasible
        infeasiblePartOfART = w;
        break;
      }

      if (!performRefinementForState(itp, w)) {
        changedElements.add(w);
      }
    }
    if (infeasiblePartOfART == lastElement) {
      stats.totalPathLengthToInfeasibility++;
    }
    stats.totalNumberOfAffectedStates += changedElements.size();

    if (changedElements.isEmpty() && pRepeatedCounterexample) {
      // TODO One cause for this exception is that the CPAAlgorithm sometimes
      // re-adds the parent of the error element to the waitlist, and thus the
      // error element would get re-discovered immediately again.
      // Currently the CPAAlgorithm does this only when there are siblings of
      // the target state, which should rarely happen.
      // We still need a better handling for this situation.
      throw new RefinementFailedException(RefinementFailedException.Reason.RepeatedCounterexample, null);
    }

    finishRefinementOfPath(infeasiblePartOfART, changedElements, pReached, pRepeatedCounterexample);

    assert !pReached.asReachedSet().contains(lastElement);
  }

  public void startRefinementOfPath() {
  }

  /**
   * For each interpolant, we strengthen the corresponding state by
   * conjunctively adding the interpolant to its state formula.
   */
  public boolean performRefinementForState(BooleanFormula itp,
      ARGState w) {

    itp = fmgr.uninstantiate(itp);
    BooleanFormula stateFormula = getStateFormula(w);

    stats.itpCheck.start();
    boolean isNewItp = !solver.implies(stateFormula, itp);
    stats.itpCheck.stop();

    if (isNewItp) {
      addFormulaToState(itp, w, fmgr);
    }
    return !isNewItp;
  }

  /**
   * After a path was strengthened, we need to take care of the coverage relation.
   * We also remove the infeasible part from the ARG,
   * and re-establish the coverage invariant (i.e., that states on the path
   * are either covered or cannot be covered).
   */
  public void finishRefinementOfPath(ARGState infeasiblePartOfART,
      List<ARGState> changedElements, ARGReachedSet pReached,
      boolean pRepeatedCounterexample)
      throws CPAException {

    stats.argUpdate.start();
    for (ARGState w : changedElements) {
      pReached.removeCoverageOf(w);
    }

    pReached.removeInfeasiblePartofARG(infeasiblePartOfART);
    stats.argUpdate.stop();

    // optimization: instead of closing all ancestors of v,
    // close only those that were strengthened during refine
    stats.coverTime.start();
    try {
      for (ARGState w : changedElements) {
        if (pReached.tryToCover(w)) {
          break; // all further elements are covered anyway
        }
      }
    } finally {
      stats.coverTime.stop();
    }
  }

  @Override
  public Statistics getStatistics() {
    return stats;
  }
}
