/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2010  Dirk Beyer
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
package org.sosy_lab.cpachecker.core.algorithm;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;

import org.sosy_lab.common.LogManager;
import org.sosy_lab.common.Pair;

import org.sosy_lab.cpachecker.core.CPAchecker;
import org.sosy_lab.cpachecker.core.ReachedElements;
import org.sosy_lab.cpachecker.core.CPAcheckerResult.Result;
import org.sosy_lab.cpachecker.core.interfaces.AbstractElement;
import org.sosy_lab.cpachecker.core.interfaces.ConfigurableProgramAnalysis;
import org.sosy_lab.cpachecker.core.interfaces.MergeOperator;
import org.sosy_lab.cpachecker.core.interfaces.Precision;
import org.sosy_lab.cpachecker.core.interfaces.PrecisionAdjustment;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;
import org.sosy_lab.cpachecker.core.interfaces.StatisticsProvider;
import org.sosy_lab.cpachecker.core.interfaces.StopOperator;
import org.sosy_lab.cpachecker.core.interfaces.TransferRelation;
import org.sosy_lab.cpachecker.exceptions.CPAException;

public class CPAAlgorithm implements Algorithm, StatisticsProvider {

  private static class CPAStatistics implements Statistics {

    private long totalTime = 0;
    private long chooseTime = 0;
    private long precisionTime = 0;
    private long transferTime = 0;
    private long mergeTime = 0;
    private long stopTime = 0;

    private int countIterations = 0;
    private int countWaitlistSize = 0;
    private int countSuccessors = 0;
    private int maxSuccessors = 0;
    private int countMerge = 0;
    private int countStop = 0;

    @Override
    public String getName() {
      return "CPA algorithm";
    }

    @Override
    public void printStatistics(PrintWriter out, Result pResult,
        ReachedElements pReached) {
      out.println("Number of iterations:           " + countIterations);
      out.println("Average size of waitlist:       " + countWaitlistSize/countIterations);
      out.println("Number of computed successors:  " + countSuccessors);
      out.println("Max successors for one element: " + maxSuccessors);
      out.println("Number of times merged:         " + countMerge);
      out.println("Number of times stopped:        " + countStop);
      out.println();
      out.println("Total time for CPA algorithm:   " + toTime(totalTime));
      out.println("Time for choose from waitlist:  " + toTime(chooseTime));
      out.println("Time for precision adjustment:  " + toTime(precisionTime));
      out.println("Time for transfer relation:     " + toTime(transferTime));
      out.println("Time for merge operator:        " + toTime(mergeTime));
      out.println("Time for stop operator:         " + toTime(stopTime));
    }

    private String toTime(long timeMillis) {
      return String.format("% 5d.%03ds", timeMillis/1000, timeMillis%1000);
    }
  }

  private final CPAStatistics stats = new CPAStatistics();

  private final ConfigurableProgramAnalysis cpa;

  private final LogManager logger;

  public CPAAlgorithm(ConfigurableProgramAnalysis cpa, LogManager logger) {
    this.cpa = cpa;
    this.logger = logger;
  }

  @Override
  public void run(final ReachedElements reachedElements) throws CPAException {
    long startTotalTime = System.currentTimeMillis();
    final TransferRelation transferRelation = cpa.getTransferRelation();
    final MergeOperator mergeOperator = cpa.getMergeOperator();
    final StopOperator stopOperator = cpa.getStopOperator();
    final PrecisionAdjustment precisionAdjustment = cpa.getPrecisionAdjustment();

    while (reachedElements.hasWaitingElement()) {
      CPAchecker.stopIfNecessary();

      stats.countIterations++;

      // Pick next element using strategy
      // BFS, DFS or top sort according to the configuration
      stats.countWaitlistSize += reachedElements.getWaitlistSize();

      long start = System.currentTimeMillis();
      final AbstractElement element = reachedElements.popFromWaitlist();
      final Precision precision = reachedElements.getPrecision(element);
      long end = System.currentTimeMillis();
      stats.chooseTime += (end - start);

      logger.log(Level.FINER, "Retrieved element from waitlist");
      logger.log(Level.ALL, "Current element is", element, "with precision", precision);

      start = System.currentTimeMillis();
      Collection<? extends AbstractElement> successors = transferRelation.getAbstractSuccessors(element, precision, null);
      end = System.currentTimeMillis();
      stats.transferTime += (end - start);
      // TODO When we have a nice way to mark the analysis result as incomplete, we could continue analysis on a CPATransferException with the next element from waitlist.

      int numSuccessors = successors.size();
      logger.log(Level.FINER, "Current element has", numSuccessors, "successors");
      stats.countSuccessors += numSuccessors;
      stats.maxSuccessors = Math.max(numSuccessors, stats.maxSuccessors);

      boolean elementWasMerged = false;
      
      for (AbstractElement successor : successors) {
        logger.log(Level.FINER, "Considering successor of current element");
        logger.log(Level.ALL, "Successor of", element, "\nis", successor);

        start = System.currentTimeMillis();
        Pair<AbstractElement, Precision> adjustedSuccessor = 
          precisionAdjustment.prec(successor, precision, reachedElements);
        end = System.currentTimeMillis();
        stats.precisionTime += (end - start);

        if (adjustedSuccessor == null) {
          logger.log(Level.FINER, "Successor is not reachable (determined by precision adjustment)");
          continue;
        }
        
        successor = adjustedSuccessor.getFirst();
        Precision successorPrecision = adjustedSuccessor.getSecond();
        
        Collection<AbstractElement> reached = reachedElements.getReached(successor);

        // AG as an optimization, we allow the mergeOperator to be null,
        // as a synonym of a trivial operator that never merges
        if (mergeOperator != null && !reached.isEmpty()) {
          start = System.currentTimeMillis();

          List<AbstractElement> toRemove = new ArrayList<AbstractElement>();
          List<Pair<AbstractElement, Precision>> toAdd = new ArrayList<Pair<AbstractElement, Precision>>();

          logger.log(Level.FINER, "Considering", reached.size(), "elements from reached set for merge");
          for (AbstractElement reachedElement : reached) {
            AbstractElement mergedElement = mergeOperator.merge(successor, reachedElement, successorPrecision);

            if (!mergedElement.equals(reachedElement)) {
              logger.log(Level.FINER, "Successor was merged with element from reached set");
              logger.log(Level.ALL,
                  "Merged", successor, "\nand", reachedElement, "\n-->", mergedElement);
              stats.countMerge++;

              if (reachedElement == element) {
                elementWasMerged = true;
                // The abstract state "element" was merged and will be removed
                // from the reached set.
              }

              toRemove.add(reachedElement);
              toAdd.add(new Pair<AbstractElement, Precision>(mergedElement, successorPrecision));
            }
          }
          reachedElements.removeAll(toRemove);
          reachedElements.addAll(toAdd);

          end = System.currentTimeMillis();
          stats.mergeTime += (end - start);
        }

        start = System.currentTimeMillis();
        boolean stop = stopOperator.stop(successor, reached, successorPrecision);
        end = System.currentTimeMillis();
        stats.stopTime += (end - start);

        if (stop) {
          logger.log(Level.FINER, "Successor is covered or unreachable, not adding to waitlist");
          stats.countStop++;

        } else {
          logger.log(Level.FINER, "No need to stop, adding successor to waitlist");

          reachedElements.add(successor, successorPrecision);
        }

        if (successorPrecision.isBreak()) {
          // re-add the old element to the waitlist, there may be unhandled
          // successors left that otherwise would be forgotten
          if (!elementWasMerged) {
            // Optimization:
            // Only do this if the old element was not merged.
            // Otherwise it is not longer in the reached set, but the result of
            // the merge (which covers the abstract state "element") is in
            // the reached set and the waitlist.
            reachedElements.reAddToWaitlist(element);
          }
          
          stats.totalTime += (System.currentTimeMillis() - startTotalTime);
          return;
        }
      }
    }
    stats.totalTime += System.currentTimeMillis() - startTotalTime;
  }

  @Override
  public ConfigurableProgramAnalysis getCPA() {
    return cpa;
  }

  @Override
  public void collectStatistics(Collection<Statistics> pStatsCollection) {
    pStatsCollection.add(stats);
  }
}