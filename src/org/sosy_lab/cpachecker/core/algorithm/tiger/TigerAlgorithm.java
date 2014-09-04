/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2014  Dirk Beyer
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
package org.sosy_lab.cpachecker.core.algorithm.tiger;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;

import org.sosy_lab.common.Pair;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.FileOption;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.common.io.Files;
import org.sosy_lab.common.io.Path;
import org.sosy_lab.common.io.Paths;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.core.CPAcheckerResult.Result;
import org.sosy_lab.cpachecker.core.CounterexampleInfo;
import org.sosy_lab.cpachecker.core.ShutdownNotifier;
import org.sosy_lab.cpachecker.core.algorithm.Algorithm;
import org.sosy_lab.cpachecker.core.algorithm.CEGARAlgorithm;
import org.sosy_lab.cpachecker.core.algorithm.CPAAlgorithm;
import org.sosy_lab.cpachecker.core.algorithm.testgen.util.StartupConfig;
import org.sosy_lab.cpachecker.core.algorithm.tiger.fql.FQLSpecificationUtil;
import org.sosy_lab.cpachecker.core.algorithm.tiger.fql.PredefinedCoverageCriteria;
import org.sosy_lab.cpachecker.core.algorithm.tiger.fql.ast.Edges;
import org.sosy_lab.cpachecker.core.algorithm.tiger.fql.ast.FQLSpecification;
import org.sosy_lab.cpachecker.core.algorithm.tiger.fql.ecp.ElementaryCoveragePattern;
import org.sosy_lab.cpachecker.core.algorithm.tiger.fql.ecp.SingletonECPEdgeSet;
import org.sosy_lab.cpachecker.core.algorithm.tiger.fql.ecp.translators.GuardedEdgeLabel;
import org.sosy_lab.cpachecker.core.algorithm.tiger.fql.ecp.translators.GuardedLabel;
import org.sosy_lab.cpachecker.core.algorithm.tiger.fql.ecp.translators.InverseGuardedEdgeLabel;
import org.sosy_lab.cpachecker.core.algorithm.tiger.fql.ecp.translators.ToGuardedAutomatonTranslator;
import org.sosy_lab.cpachecker.core.algorithm.tiger.fql.translators.ecp.ClusteringCoverageSpecificationTranslator;
import org.sosy_lab.cpachecker.core.algorithm.tiger.fql.translators.ecp.CoverageSpecificationTranslator;
import org.sosy_lab.cpachecker.core.algorithm.tiger.fql.translators.ecp.IncrementalCoverageSpecificationTranslator;
import org.sosy_lab.cpachecker.core.algorithm.tiger.goals.Goal;
import org.sosy_lab.cpachecker.core.algorithm.tiger.goals.clustering.ClusteredElementaryCoveragePattern;
import org.sosy_lab.cpachecker.core.algorithm.tiger.goals.clustering.InfeasibilityPropagation;
import org.sosy_lab.cpachecker.core.algorithm.tiger.goals.clustering.InfeasibilityPropagation.Prediction;
import org.sosy_lab.cpachecker.core.algorithm.tiger.util.ARTReuse;
import org.sosy_lab.cpachecker.core.algorithm.tiger.util.PrecisionCallback;
import org.sosy_lab.cpachecker.core.algorithm.tiger.util.TestCase;
import org.sosy_lab.cpachecker.core.algorithm.tiger.util.TestSuite;
import org.sosy_lab.cpachecker.core.algorithm.tiger.util.ThreeValuedAnswer;
import org.sosy_lab.cpachecker.core.algorithm.tiger.util.WorkerRunnable;
import org.sosy_lab.cpachecker.core.algorithm.tiger.util.WorklistEntryComparator;
import org.sosy_lab.cpachecker.core.algorithm.tiger.util.Wrapper;
import org.sosy_lab.cpachecker.core.algorithm.tiger.util.WrapperUtil;
import org.sosy_lab.cpachecker.core.counterexample.Model;
import org.sosy_lab.cpachecker.core.counterexample.Model.AssignableTerm;
import org.sosy_lab.cpachecker.core.interfaces.AbstractState;
import org.sosy_lab.cpachecker.core.interfaces.CPAFactory;
import org.sosy_lab.cpachecker.core.interfaces.ConfigurableProgramAnalysis;
import org.sosy_lab.cpachecker.core.interfaces.Precision;
import org.sosy_lab.cpachecker.core.interfaces.Refiner;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;
import org.sosy_lab.cpachecker.core.interfaces.StatisticsProvider;
import org.sosy_lab.cpachecker.core.reachedset.LocationMappedReachedSet;
import org.sosy_lab.cpachecker.core.reachedset.ReachedSet;
import org.sosy_lab.cpachecker.core.waitlist.Waitlist;
import org.sosy_lab.cpachecker.cpa.arg.ARGCPA;
import org.sosy_lab.cpachecker.cpa.arg.ARGState;
import org.sosy_lab.cpachecker.cpa.arg.ARGStatistics;
import org.sosy_lab.cpachecker.cpa.arg.ARGUtils;
import org.sosy_lab.cpachecker.cpa.composite.CompositeCPA;
import org.sosy_lab.cpachecker.cpa.guardededgeautomaton.GuardedEdgeAutomatonCPA;
import org.sosy_lab.cpachecker.cpa.guardededgeautomaton.productautomaton.ProductAutomatonCPA;
import org.sosy_lab.cpachecker.cpa.predicate.PredicateAbstractionRefinementStrategy;
import org.sosy_lab.cpachecker.cpa.predicate.PredicateCPA;
import org.sosy_lab.cpachecker.cpa.predicate.PredicateCPARefiner;
import org.sosy_lab.cpachecker.cpa.predicate.PredicatePrecision;
import org.sosy_lab.cpachecker.cpa.predicate.RefinementStrategy;
import org.sosy_lab.cpachecker.exceptions.CPAException;
import org.sosy_lab.cpachecker.exceptions.PredicatedAnalysisPropertyViolationException;
import org.sosy_lab.cpachecker.util.Precisions;
import org.sosy_lab.cpachecker.util.automaton.NondeterministicFiniteAutomaton;

@Options(prefix = "tiger")
public class TigerAlgorithm implements Algorithm, PrecisionCallback<PredicatePrecision>, StatisticsProvider, Statistics {

  public static String originalMainFunction = null;

  @Option(name = "fqlQuery", description = "Coverage criterion given as an FQL query")
  private String fqlQuery = PredefinedCoverageCriteria.BASIC_BLOCK_COVERAGE; // default is basic block coverage

  @Option(name = "optimizeGoalAutomata", description = "Optimize the test goal automata")
  private boolean optimizeGoalAutomata = true;

  @Option(name = "printARGperGoal", description = "Print the ARG for each test goal")
  private boolean printARGperGoal = false;

  @Option(name = "checkCoverage", description = "Checks whether a test case for one goal covers another test goal")
  private boolean checkCoverage = true;

  @Option(name = "reuseARG", description = "Reuse ARG across test goals")
  private boolean reuseARG = true;

  @Option(name = "reusePredicates", description = "Reuse predicates across modifications of an ARG.")
  private boolean reusePredicates = true;

  @Option(name = "testsuiteFile", description = "Filename for output of generated test suite")
  @FileOption(FileOption.Type.OUTPUT_FILE)
  private Path testsuiteFile = Paths.get("testsuite.txt");

  @Option(name = "useInfeasibilityPropagation", description = "Map information on infeasibility of one test goal to other test goals.")
  private boolean useInfeasibilityPropagation = true;

  enum TimeoutStrategy {
    SKIP_AFTER_TIMEOUT,
    RETRY_AFTER_TIMEOUT
  }

  @Option(name = "timeoutStrategy", description = "How to proceed with timed-out goals if some time remains after processing all other goals.")
  private TimeoutStrategy timeoutStrategy = TimeoutStrategy.SKIP_AFTER_TIMEOUT;

  @Option(name = "limitsPerGoal.time.cpu.increment", description = "Value for which timeout gets incremented if timed-out goals are re-processed.")
  private int timeoutIncrement = 0;

  /*@Option(name = "globalCoverageCheckBeforeTimeout", description = "Perform a coverage check on all remaining coverage goals before the global time out happens.")
  private boolean globalCoverageCheckBeforeTimeout = false;

  @Option(name = "timeForGlobalCoverageCheck", description = "Time budget for coverage check before global time out.")
  private String timeForGlobalCoverageCheck = "0s";*/

  @Option(name = "limitsPerGoal.time.cpu", description = "Time limit per test goal in seconds (-1 for infinity).")
  private long cpuTimelimitPerGoal = -1;

  @Option(name = "inverseOrder", description = "Inverses the order of test goals each time a new round of re-processing of timed-out goals begins.")
  private boolean inverseOrder = true;

  @Option(name = "useOrder", description = "Enforce the original order each time a new round of re-processing of timed-out goals begins.")
  private boolean useOrder = true;

  private LogManager logger;
  private StartupConfig startupConfig;

  private ConfigurableProgramAnalysis cpa;
  private CFA cfa;

  private CoverageSpecificationTranslator mCoverageSpecificationTranslator;
  private FQLSpecification fqlSpecification;

  private Wrapper wrapper;
  private GuardedEdgeLabel mAlphaLabel;
  private GuardedEdgeLabel mOmegaLabel;
  private InverseGuardedEdgeLabel mInverseAlphaLabel;

  private TestSuite testsuite;
  private ReachedSet reachedSet = null;
  private ReachedSet outsideReachedSet = null;

  private PredicatePrecision reusedPrecision = null;

  private int statistics_numberOfTestGoals;
  private int statistics_numberOfProcessedTestGoals = 0;

  private Prediction[] lGoalPrediction;

  public TigerAlgorithm(Algorithm pAlgorithm, ConfigurableProgramAnalysis pCpa, ShutdownNotifier pShutdownNotifier,
      CFA pCfa, Configuration pConfig, LogManager pLogger) throws InvalidConfigurationException {

    startupConfig = new StartupConfig(pConfig, pLogger, pShutdownNotifier);
    startupConfig.getConfig().inject(this);

    logger = pLogger;

    cpa = pCpa;
    cfa = pCfa;

    testsuite = new TestSuite();


    assert originalMainFunction != null;
    mCoverageSpecificationTranslator = new CoverageSpecificationTranslator(pCfa.getFunctionHead(originalMainFunction));


    wrapper = new Wrapper(pCfa, originalMainFunction);

    mAlphaLabel = new GuardedEdgeLabel(new SingletonECPEdgeSet(wrapper.getAlphaEdge()));
    mInverseAlphaLabel = new InverseGuardedEdgeLabel(mAlphaLabel);
    mOmegaLabel = new GuardedEdgeLabel(new SingletonECPEdgeSet(wrapper.getOmegaEdge()));


    // get internal representation of FQL query
    logger.logf(Level.INFO, "FQL query string: %s", fqlQuery);

    fqlSpecification = FQLSpecificationUtil.getFQLSpecification(fqlQuery);

    logger.logf(Level.INFO, "FQL query: %s", fqlSpecification.toString());

    // TODO fix this restriction
    if (fqlSpecification.hasPassingClause()) {
      logger.logf(Level.SEVERE, "No PASSING clauses supported at the moment!");

      throw new InvalidConfigurationException("No PASSING clauses supported at the moment!");
    }

    // TODO fix this restriction
    if (fqlSpecification.hasPredicate()) {
      logger.logf(Level.SEVERE, "No predicates in FQL queries supported at the moment!");

      throw new InvalidConfigurationException("No predicates in FQL queries supported at the moment!");
    }
  }

  @Override
  public boolean run(ReachedSet pReachedSet) throws CPAException, InterruptedException,
      PredicatedAnalysisPropertyViolationException {

    // we empty pReachedSet to stop complaints of an incomplete analysis
    // Problem: pReachedSet does not match the internal CPA structure!
    logger.logf(Level.INFO, "We will not use the provided reached set since it violates the internal structure of Tiger's CPAs");
    logger.logf(Level.INFO, "We empty pReachedSet to stop complaints of an incomplete analysis");
    outsideReachedSet = pReachedSet;
    outsideReachedSet.clear();


    // Optimization: Infeasibility propagation
    Pair<Boolean, LinkedList<Edges>> lInfeasibilityPropagation;

    if (useInfeasibilityPropagation) {
      lInfeasibilityPropagation = InfeasibilityPropagation.canApplyInfeasibilityPropagation(fqlSpecification);
    }
    else {
      lInfeasibilityPropagation = Pair.of(Boolean.FALSE, null);
    }

    LinkedList<ElementaryCoveragePattern> goalPatterns;

    if (lInfeasibilityPropagation.getFirst()) {
      goalPatterns = extractTestGoalPatterns_InfeasibilityPropagation(fqlSpecification, lInfeasibilityPropagation.getSecond());

      lGoalPrediction = new Prediction[statistics_numberOfTestGoals];

      for (int i = 0; i < statistics_numberOfTestGoals; i++) {
        lGoalPrediction[i] = Prediction.UNKNOWN;
      }
    }
    else {
      // (ii) translate query into set of test goals
      // I didn't move this operation to the constructor since it is a potentially expensive operation.
      goalPatterns = extractTestGoalPatterns(fqlSpecification);

      lGoalPrediction = null;
    }




    // (iii) do test generation for test goals ...
    boolean wasSound = true;
    if (!testGeneration(goalPatterns, lInfeasibilityPropagation)) {
      logger.logf(Level.WARNING, "Test generation contained unsound reachability analysis runs!");
      wasSound = false;
    }

    assert(goalPatterns.isEmpty());

    return wasSound;
  }

  private LinkedList<ElementaryCoveragePattern> extractTestGoalPatterns_InfeasibilityPropagation(FQLSpecification pFQLQuery, LinkedList<Edges> pEdges) {
    logger.logf(Level.INFO, "Extracting test goals.");

    CFANode lInitialNode = this.mAlphaLabel.getEdgeSet().iterator().next().getSuccessor();
    ClusteringCoverageSpecificationTranslator lTranslator = new ClusteringCoverageSpecificationTranslator(mCoverageSpecificationTranslator.mPathPatternTranslator, pEdges, lInitialNode);

    ElementaryCoveragePattern[] lGoalPatterns = lTranslator.createElementaryCoveragePatternsAndClusters();
    statistics_numberOfTestGoals = lGoalPatterns.length;

    logger.logf(Level.INFO, "Number of test goals: %d", statistics_numberOfTestGoals);

    LinkedList<ElementaryCoveragePattern> goalPatterns = new LinkedList<>();

    for (int lGoalIndex = 0; lGoalIndex < statistics_numberOfTestGoals; lGoalIndex++) {
      goalPatterns.add(lGoalPatterns[lGoalIndex]);
    }

    return goalPatterns;
  }

  private LinkedList<ElementaryCoveragePattern> extractTestGoalPatterns(FQLSpecification pFQLQuery) {
    logger.logf(Level.INFO, "Extracting test goals.");


    // TODO check for (temporarily) unsupported features

    // TODO enable use of infeasibility propagation


    IncrementalCoverageSpecificationTranslator lTranslator = new IncrementalCoverageSpecificationTranslator(mCoverageSpecificationTranslator.mPathPatternTranslator);

    statistics_numberOfTestGoals = lTranslator.getNumberOfTestGoals(pFQLQuery.getCoverageSpecification());
    logger.logf(Level.INFO, "Number of test goals: %d", statistics_numberOfTestGoals);

    Iterator<ElementaryCoveragePattern> lGoalIterator = lTranslator.translate(pFQLQuery.getCoverageSpecification());
    LinkedList<ElementaryCoveragePattern> lGoalPatterns = new LinkedList<>();

    for (int lGoalIndex = 0; lGoalIndex < statistics_numberOfTestGoals; lGoalIndex++) {
      lGoalPatterns.add(lGoalIterator.next());
    }

    return lGoalPatterns;
  }

  private boolean isCovered(int goalIndex, Goal lGoal) {
    for (TestCase testcase : testsuite.getTestCases()) {
      ThreeValuedAnswer isCovered = TigerAlgorithm.accepts(lGoal.getAutomaton(), testcase.getPath());
      if (isCovered.equals(ThreeValuedAnswer.ACCEPT)) {
        // test goal is already covered by an existing test case
        logger.logf(Level.INFO, "Test goal %d is already covered by an existing test case.", goalIndex);

        testsuite.addTestCase(testcase, lGoal);

        return true;
      }
      else if (isCovered.equals(ThreeValuedAnswer.UNKNOWN)) {
        logger.logf(Level.WARNING, "Coverage check for goal %d could not be performed in a precise way!", goalIndex);
      }
    }

    return false;
  }

  private boolean testGeneration(LinkedList<ElementaryCoveragePattern> pTestGoalPatterns, Pair<Boolean, LinkedList<Edges>> pInfeasibilityPropagation) throws CPAException, InterruptedException {
    boolean wasSound = true;

    int goalIndex = 0;
    int numberOfTestGoals = pTestGoalPatterns.size();

    NondeterministicFiniteAutomaton<GuardedEdgeLabel> previousAutomaton = null;

    while (!pTestGoalPatterns.isEmpty()) {
      statistics_numberOfProcessedTestGoals++;

      ElementaryCoveragePattern lTestGoalPattern = pTestGoalPatterns.poll();

      goalIndex++;

      logger.logf(Level.INFO, "Processing test goal %d of %d.", goalIndex, numberOfTestGoals);

      Goal lGoal = constructGoal(goalIndex, lTestGoalPattern, mAlphaLabel, mInverseAlphaLabel, mOmegaLabel,  optimizeGoalAutomata);

      if (lGoalPrediction != null && lGoalPrediction[goalIndex - 1] == Prediction.INFEASIBLE) {
        logger.logf(Level.INFO, "This goal is predicted as infeasible!");

        testsuite.addInfeasibleGoal(lGoal);

        continue;
      }

      NondeterministicFiniteAutomaton<GuardedEdgeLabel> currentAutomaton = lGoal.getAutomaton();

      if (ARTReuse.isDegeneratedAutomaton(currentAutomaton)) {
        // current goal is for sure infeasible
        logger.logf(Level.INFO, "Test goal infeasible.");

        testsuite.addInfeasibleGoal(lGoal);

        lGoalPrediction[goalIndex - 1] = Prediction.INFEASIBLE;

        continue; // we do not want to modify the ARG for the degenerated automaton to keep more reachability information
      }

      if (checkCoverage && isCovered(goalIndex, lGoal)) {
        lGoalPrediction[goalIndex - 1] = Prediction.FEASIBLE;
        continue;
      }

      if (!runReachabilityAnalysis(goalIndex, lGoal, previousAutomaton, pInfeasibilityPropagation)) {
        logger.logf(Level.WARNING, "Analysis run was unsound!");
        wasSound = false;
      }

      previousAutomaton = currentAutomaton;
    }

    // reprocess timed-out goals
    if (timeoutStrategy.equals(TimeoutStrategy.RETRY_AFTER_TIMEOUT)) {
      // TODO move to upper loop
      Map<Goal, Integer> coverageCheckOpt = new HashMap<>();

      //int previousNumberOfTestCases = 0;
      //int previousPreviousNumberOfTestCases = testsuite.getNumberOfTestCases();

      boolean order = true;

      do {
        if (timeoutIncrement > 0) {
          long oldCPUTimeLimitPerGoal = cpuTimelimitPerGoal;
          cpuTimelimitPerGoal += timeoutIncrement;
          logger.logf(Level.INFO, "Incremented timeout from %d to %d seconds.", oldCPUTimeLimitPerGoal, cpuTimelimitPerGoal);
        }

        Collection<Entry<Integer, Goal>> set;

        if (useOrder) {
          if (inverseOrder) {
            order = !order;
          }

          // keep original order of goals (or inverse of it)
          if (order) {
            set = new TreeSet<>(WorklistEntryComparator.ORDER_RESPECTING_COMPARATOR);
          }
          else {
            set = new TreeSet<>(WorklistEntryComparator.ORDER_INVERTING_COMPARATOR);
          }

          set.addAll(testsuite.getTimedOutGoals().entrySet());
        }
        else {
          set = new LinkedList<>();
          set.addAll(testsuite.getTimedOutGoals().entrySet());
        }

        testsuite.getTimedOutGoals().clear();

        for (Entry<Integer, Goal> entry : set) {
          goalIndex = entry.getKey();
          Goal lGoal = entry.getValue();

          logger.logf(Level.INFO, "Processing test goal %d of %d.", goalIndex, numberOfTestGoals);

          if (lGoalPrediction != null && lGoalPrediction[goalIndex - 1] == Prediction.INFEASIBLE) {
            logger.logf(Level.INFO, "This goal is predicted as infeasible!");

            testsuite.addInfeasibleGoal(lGoal);

            continue;
          }

          // TODO optimization: do not check for coverage if no new testcases were generated.
          if (checkCoverage) {
            if (coverageCheckOpt.containsKey(lGoal)) {
              if (coverageCheckOpt.get(lGoal) < testsuite.getNumberOfTestCases()) {
                if (isCovered(goalIndex, lGoal)) {
                  continue;
                }
                else {
                  // TODO optimization: only add if goal times out!
                  coverageCheckOpt.put(lGoal, testsuite.getNumberOfTestCases());
                }
              }
            }
          }

          /*if (checkCoverage && (previousNumberOfTestCases < testsuite.getNumberOfTestCases()) && isCovered(goalIndex, lGoal)) {
            continue;
          }*/

          if (!runReachabilityAnalysis(goalIndex, lGoal, previousAutomaton, pInfeasibilityPropagation)) {
            logger.logf(Level.WARNING, "Analysis run was unsound!");
            wasSound = false;
          }

          previousAutomaton = lGoal.getAutomaton();
        }

        //previousNumberOfTestCases = previousPreviousNumberOfTestCases;
        //previousPreviousNumberOfTestCases = testsuite.getNumberOfTestCases();
      }
      while (testsuite.hasTimedoutTestGoals());
    }

    return wasSound;
  }

  private boolean runReachabilityAnalysis(int goalIndex, Goal pGoal, NondeterministicFiniteAutomaton<GuardedEdgeLabel> pPreviousGoalAutomaton, Pair<Boolean, LinkedList<Edges>> pInfeasibilityPropagation) throws CPAException, InterruptedException {
    GuardedEdgeAutomatonCPA lAutomatonCPA = new GuardedEdgeAutomatonCPA(pGoal.getAutomaton());


    List<ConfigurableProgramAnalysis> lAutomatonCPAs = new ArrayList<>(1);//(2);

    /*if (pPassingCPA != null) {
      lAutomatonCPAs.add(pPassingCPA);
    }*/

    lAutomatonCPAs.add(lAutomatonCPA);



    LinkedList<ConfigurableProgramAnalysis> lComponentAnalyses = new LinkedList<>();
    // TODO what is the more efficient order for the CPAs? Can we substitute a placeholder CPA? or inject an automaton in to an automaton CPA?
    //int lProductAutomatonIndex = lComponentAnalyses.size();
    int lProductAutomatonIndex = lComponentAnalyses.size();
    lComponentAnalyses.add(ProductAutomatonCPA.create(lAutomatonCPAs, false));

    // TODO experiment
    if (cpa instanceof CompositeCPA) {
      CompositeCPA compositeCPA = (CompositeCPA)cpa;
      lComponentAnalyses.addAll(compositeCPA.getWrappedCPAs());
    }
    else {
      lComponentAnalyses.add(cpa);
    }


    ARGCPA lARTCPA;
    try {
      // create composite CPA
      CPAFactory lCPAFactory = CompositeCPA.factory();
      lCPAFactory.setChildren(lComponentAnalyses);
      lCPAFactory.setConfiguration(startupConfig.getConfig());
      lCPAFactory.setLogger(logger);
      lCPAFactory.set(cfa, CFA.class);

      ConfigurableProgramAnalysis lCPA = lCPAFactory.createInstance();

      // create ART CPA
      CPAFactory lARTCPAFactory = ARGCPA.factory();
      lARTCPAFactory.set(cfa, CFA.class);
      lARTCPAFactory.setChild(lCPA);
      lARTCPAFactory.setConfiguration(startupConfig.getConfig());
      lARTCPAFactory.setLogger(logger);

      lARTCPA = (ARGCPA)lARTCPAFactory.createInstance();
    } catch (InvalidConfigurationException | CPAException e) {
      throw new RuntimeException(e);
    }

    if (reuseARG && (reachedSet != null)) {
      ARTReuse.modifyReachedSet(reachedSet, cfa.getMainFunction(), lARTCPA, lProductAutomatonIndex, pPreviousGoalAutomaton, pGoal.getAutomaton());

      // reusedPrecision == null indicates that there is no PredicateCPA
      if (reusePredicates && reusedPrecision != null) {
        for (AbstractState lWaitlistElement : reachedSet.getWaitlist()) {
          Precision lOldPrecision = reachedSet.getPrecision(lWaitlistElement);
          Precision lNewPrecision = Precisions.replaceByType(lOldPrecision, reusedPrecision, PredicatePrecision.class);

          reachedSet.updatePrecision(lWaitlistElement, lNewPrecision);
        }
      }
    }
    else {
      reachedSet = new LocationMappedReachedSet(Waitlist.TraversalMethod.BFS); // TODO why does TOPSORT not exist anymore?

      AbstractState lInitialElement = lARTCPA.getInitialState(cfa.getMainFunction());
      Precision lInitialPrecision = lARTCPA.getInitialPrecision(cfa.getMainFunction());

      reachedSet.add(lInitialElement, lInitialPrecision);

      outsideReachedSet.add(lInitialElement, lInitialPrecision);

      if (reusePredicates) {
        // initialize reused predicate precision
        PredicateCPA predicateCPA = lARTCPA.retrieveWrappedCpa(PredicateCPA.class);

        if (predicateCPA != null) {
          reusedPrecision = (PredicatePrecision)predicateCPA.getInitialPrecision(cfa.getMainFunction());
        }
        else {
          logger.logf(Level.INFO, "No predicate CPA available to reuse predicates!");
        }
      }
    }

    ShutdownNotifier algNotifier = ShutdownNotifier.createWithParent(startupConfig.getShutdownNotifier());

    CPAAlgorithm cpaAlg;

    try {
      cpaAlg = CPAAlgorithm.create(lARTCPA, logger, startupConfig.getConfig(), algNotifier);
    } catch (InvalidConfigurationException e1) {
      throw new RuntimeException(e1);
    }

    CEGARAlgorithm cegarAlg;
    try {
      cegarAlg = new CEGARAlgorithm(cpaAlg, lARTCPA, startupConfig.getConfig(), logger);
    } catch (InvalidConfigurationException e) {
      throw new RuntimeException(e);
    }

    Refiner refiner = cegarAlg.getRefiner();
    if (refiner instanceof PredicateCPARefiner) {
      PredicateCPARefiner predicateRefiner = (PredicateCPARefiner)refiner;

      if (reusePredicates) {
        RefinementStrategy strategy = predicateRefiner.getRefinementStrategy();
        assert(strategy instanceof PredicateAbstractionRefinementStrategy);

        PredicateAbstractionRefinementStrategy refinementStrategy = (PredicateAbstractionRefinementStrategy)strategy;
        refinementStrategy.setPrecisionCallback(this);
      }
    }


    ARGStatistics lARTStatistics;
    try {
      lARTStatistics = new ARGStatistics(startupConfig.getConfig(), lARTCPA);
    } catch (InvalidConfigurationException e) {
      throw new RuntimeException(e);
    }
    Set<Statistics> lStatistics = new HashSet<>();
    lStatistics.add(lARTStatistics);
    cegarAlg.collectStatistics(lStatistics);


    boolean analysisWasSound = false;
    boolean hasTimedOut = false;

    if (cpuTimelimitPerGoal < 0) {
      // run algorithm without time limit
      analysisWasSound = cegarAlg.run(reachedSet);
    }
    else {
      // run algorithm with time limit
      WorkerRunnable workerRunnable = new WorkerRunnable(cegarAlg, reachedSet, cpuTimelimitPerGoal, algNotifier);

      Thread workerThread = new Thread(workerRunnable);

      workerThread.start();
      workerThread.join();

      if (workerRunnable.exceptionWasCaught()) {
        throw new RuntimeException("Handle!");
      }
      else {
        analysisWasSound = workerRunnable.analysisWasSound();

        if (workerRunnable.hasTimeout()) {
          logger.logf(Level.INFO, "Test goal timed out!");

          testsuite.addTimedOutGoal(goalIndex, pGoal);

          hasTimedOut = true;
        }
      }
    }

    if (printARGperGoal) {
      Path argFile = Paths.get("output", "ARG_goal_" + goalIndex + ".dot");

      try (Writer w = Files.openOutputFile(argFile)) {
        ARGUtils.writeARGAsDot(w, (ARGState) reachedSet.getFirstState());
      } catch (IOException e) {
        logger.logUserException(Level.WARNING, e, "Could not write ARG to file");
      }
    }

    // TODO maybe we should check for this: from(reached).anyMatch(IS_TARGET_STATE)
    Map<ARGState, CounterexampleInfo> counterexamples = lARTCPA.getCounterexamples();

    if (!hasTimedOut) {
      if (counterexamples.isEmpty()) {
        // test goal is not feasible
        logger.logf(Level.INFO, "Test goal infeasible.");

        lGoalPrediction[goalIndex - 1] = Prediction.INFEASIBLE;

        testsuite.addInfeasibleGoal(pGoal);
        // TODO add missing soundness checks!

        if (pInfeasibilityPropagation.getFirst()) {
          logger.logf(Level.INFO, "Do infeasibility propagation!");



          HashSet<CFAEdge> lTargetEdges = new HashSet<>();

          ClusteredElementaryCoveragePattern lClusteredPattern = (ClusteredElementaryCoveragePattern)pGoal.getPattern();

          ListIterator<ClusteredElementaryCoveragePattern> lRemainingPatterns = lClusteredPattern.getRemainingElementsInCluster();

          int lTmpIndex = goalIndex - 1; // caution lIndex starts at 0

          while (lRemainingPatterns.hasNext()) {
            Prediction lPrediction = lGoalPrediction[lTmpIndex];

            ClusteredElementaryCoveragePattern lRemainingPattern = lRemainingPatterns.next();

            if (lPrediction.equals(Prediction.UNKNOWN)) {
              lTargetEdges.add(lRemainingPattern.getLastSingletonCFAEdge());
            }

            lTmpIndex++;
          }

          Collection<CFAEdge> lFoundEdges = InfeasibilityPropagation.dfs2(lClusteredPattern.getCFANode(), lClusteredPattern.getLastSingletonCFAEdge(), lTargetEdges);

          lRemainingPatterns = lClusteredPattern.getRemainingElementsInCluster();

          lTmpIndex = goalIndex - 1;

          while (lRemainingPatterns.hasNext()) {
            Prediction lPrediction = lGoalPrediction[lTmpIndex];

            ClusteredElementaryCoveragePattern lRemainingPattern = lRemainingPatterns.next();

            if (lPrediction.equals(Prediction.UNKNOWN)) {
              if (!lFoundEdges.contains(lRemainingPattern.getLastSingletonCFAEdge())) {
                //mFeasibilityInformation.setStatus(lTmpIndex+1, FeasibilityInformation.FeasibilityStatus.INFEASIBLE);
                // TODO remove ???

                lGoalPrediction[lTmpIndex] = Prediction.INFEASIBLE;
              }
            }

            lTmpIndex++;
          }



        }
      }
      else {
        // test goal is feasible
        logger.logf(Level.INFO, "Test goal is feasible.");

        // TODO add missing soundness checks!


        lGoalPrediction[goalIndex - 1] = Prediction.FEASIBLE;

        assert counterexamples.size() == 1;

        for (Map.Entry<ARGState, CounterexampleInfo> lEntry : counterexamples.entrySet()) {
          CounterexampleInfo cex = lEntry.getValue();

          if (cex.isSpurious()) {
            logger.logf(Level.WARNING, "Counterexample is spurious!");
          }
          else {
            Model model = cex.getTargetPathModel();

            Comparator<Map.Entry<Model.AssignableTerm, Object>> comp = new Comparator<Map.Entry<Model.AssignableTerm, Object>>() {

              @Override
              public int compare(Entry<AssignableTerm, Object> pArg0, Entry<AssignableTerm, Object> pArg1) {
                assert pArg0.getKey().getName().equals(pArg1.getKey().getName());
                assert pArg0.getKey() instanceof Model.Variable;
                assert pArg1.getKey() instanceof Model.Variable;

                Model.Variable v0 = (Model.Variable)pArg0.getKey();
                Model.Variable v1 = (Model.Variable)pArg1.getKey();

                return (v0.getSSAIndex() - v1.getSSAIndex());
              }

            };

            TreeSet<Map.Entry<Model.AssignableTerm, Object>> inputs = new TreeSet<>(comp);

            for (Map.Entry<Model.AssignableTerm, Object> e : model.entrySet()) {
              if (e.getKey() instanceof Model.Variable) {
                Model.Variable v = (Model.Variable)e.getKey();

                if (v.getName().equals(WrapperUtil.CPAtiger_INPUT + "::__retval__")) {
                  inputs.add(e);
                }
              }
            }

            List<BigInteger> inputValues = new ArrayList<>(inputs.size());

            for (Map.Entry<Model.AssignableTerm, Object> e : inputs) {
              assert e.getValue() instanceof BigInteger;
              inputValues.add((BigInteger)e.getValue());
            }

            TestCase testcase = new TestCase(inputValues, cex.getTargetPath().asEdgesList());
            testsuite.addTestCase(testcase, pGoal);
          }
        }
      }
    }

    return analysisWasSound;
  }

  public static ThreeValuedAnswer accepts(NondeterministicFiniteAutomaton<GuardedEdgeLabel> pAutomaton, List<CFAEdge> pCFAPath) {
    Set<NondeterministicFiniteAutomaton.State> lCurrentStates = new HashSet<>();
    Set<NondeterministicFiniteAutomaton.State> lNextStates = new HashSet<>();

    lCurrentStates.add(pAutomaton.getInitialState());

    boolean lHasPredicates = false;

    for (CFAEdge lCFAEdge : pCFAPath) {
      for (NondeterministicFiniteAutomaton.State lCurrentState : lCurrentStates) {
        // Automaton accepts as soon as it sees a final state (implicit self-loop)
        if (pAutomaton.getFinalStates().contains(lCurrentState)) {
          return ThreeValuedAnswer.ACCEPT;
        }

        for (NondeterministicFiniteAutomaton<GuardedEdgeLabel>.Edge lOutgoingEdge : pAutomaton.getOutgoingEdges(lCurrentState)) {
          GuardedEdgeLabel lLabel = lOutgoingEdge.getLabel();

          if (lLabel.hasGuards()) {
            lHasPredicates = true;
          }
          else {
            if (lLabel.contains(lCFAEdge)) {
              lNextStates.add(lOutgoingEdge.getTarget());
            }
          }
        }
      }

      lCurrentStates.clear();

      Set<NondeterministicFiniteAutomaton.State> lTmp = lCurrentStates;
      lCurrentStates = lNextStates;
      lNextStates = lTmp;
    }

    for (NondeterministicFiniteAutomaton.State lCurrentState : lCurrentStates) {
      // Automaton accepts as soon as it sees a final state (implicit self-loop)
      if (pAutomaton.getFinalStates().contains(lCurrentState)) {
        return ThreeValuedAnswer.ACCEPT;
      }
    }

    if (lHasPredicates) {
      return ThreeValuedAnswer.UNKNOWN;
    }
    else {
      return ThreeValuedAnswer.REJECT;
    }
  }

  /**
   * Constructs a test goal from the given pattern.
   * @param pGoalPattern
   * @param pAlphaLabel
   * @param pInverseAlphaLabel
   * @param pOmegaLabel
   * @param pUseAutomatonOptimization
   * @return
   */
  private Goal constructGoal(int pIndex, ElementaryCoveragePattern pGoalPattern, GuardedEdgeLabel pAlphaLabel,
      GuardedEdgeLabel pInverseAlphaLabel, GuardedLabel pOmegaLabel, boolean pUseAutomatonOptimization) {

    NondeterministicFiniteAutomaton<GuardedEdgeLabel> automaton = ToGuardedAutomatonTranslator.toAutomaton(pGoalPattern, pAlphaLabel, pInverseAlphaLabel, pOmegaLabel);
    automaton = FQLSpecificationUtil.optimizeAutomaton(automaton, pUseAutomatonOptimization);

    Goal lGoal = new Goal(pIndex, pGoalPattern, automaton);

    return lGoal;
  }

  @Override
  public PredicatePrecision getPrecision() {
    return reusedPrecision;
  }

  @Override
  public void setPrecision(PredicatePrecision pNewPrec) {
    reusedPrecision = pNewPrec;
  }

  @Override
  public void collectStatistics(Collection<Statistics> pStatsCollection) {
    pStatsCollection.add(this);
  }

  @Override
  public void printStatistics(PrintStream pOut, Result pResult, ReachedSet pReached) {
    // TODO Print information about feasible, infeasible, timed-out, and unprocessed test goals.

    if (testsuiteFile != null) {
      // write generated test suite and mapping to file system
      try (Writer writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(testsuiteFile.toFile()), "utf-8"))) {
        writer.write(testsuite.toString());
        writer.close();
      } catch (IOException e){
        throw new RuntimeException(e);
      }
    }

    int numberOfTimedoutTestGoals = statistics_numberOfProcessedTestGoals - (testsuite.getNumberOfFeasibleTestGoals() + testsuite.getNumberOfInfeasibleTestGoals());

    pOut.println("Number of test goals:                              " + statistics_numberOfTestGoals);
    pOut.println("Number of processed test goals:                    " + statistics_numberOfProcessedTestGoals);
    pOut.println("Number of feasible test goals:                     " + testsuite.getNumberOfFeasibleTestGoals());
    pOut.println("Number of infeasible test goals:                   " + testsuite.getNumberOfInfeasibleTestGoals());
    //pOut.println("Number of timedout test goals:                     " + testsuite.getNumberOfTimedoutTestGoals());
    pOut.println("Number of timedout test goals:                     " + numberOfTimedoutTestGoals);

    if (statistics_numberOfProcessedTestGoals > testsuite.getNumberOfFeasibleTestGoals() + testsuite.getNumberOfInfeasibleTestGoals() + testsuite.getNumberOfTimedoutTestGoals()) {
      pOut.println("Timeout occured during processing of a test goal!");
    }
  }

  @Override
  public String getName() {
    return "TigerAlgorithm";
  }

}
