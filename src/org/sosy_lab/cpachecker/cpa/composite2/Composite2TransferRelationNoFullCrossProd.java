/*
 * CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2016  Dirk Beyer
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
package org.sosy_lab.cpachecker.cpa.composite2;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.FluentIterable.from;
import static org.sosy_lab.cpachecker.util.AbstractStates.extractStateByType;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.ast.c.CFunctionCall;
import org.sosy_lab.cpachecker.cfa.ast.c.CSimpleDeclaration;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFAEdgeType;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.cfa.model.c.CStatementEdge;
import org.sosy_lab.cpachecker.core.interfaces.AbstractState;
import org.sosy_lab.cpachecker.core.interfaces.AbstractStateWithLocations;
import org.sosy_lab.cpachecker.core.interfaces.ConfigurableProgramAnalysis;
import org.sosy_lab.cpachecker.core.interfaces.Precision;
import org.sosy_lab.cpachecker.core.interfaces.TransferRelation;
import org.sosy_lab.cpachecker.core.interfaces.pcc.ProofChecker;
import org.sosy_lab.cpachecker.cpa.assumptions.storage.AssumptionStorageTransferRelation;
import org.sosy_lab.cpachecker.cpa.predicate.PredicateTransferRelation;
import org.sosy_lab.cpachecker.exceptions.CPATransferException;
import org.sosy_lab.cpachecker.util.AbstractStates;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Options(prefix="cpa.composite")
public final class Composite2TransferRelationNoFullCrossProd implements TransferRelation {

  @Option(secure=true, description="By enabling this option the Composite2TransferRelation"
      + " will compute abstract successors for as many edges as possible in one call. For"
      + " any chain of edges in the CFA which does not have more than one outgoing or leaving"
      + " edge the components of the Composite2CPA are called for each of the edges in this"
      + " chain. Strengthening is still computed after every edge."
      + " The main difference is that while this option is enabled not every ARGState may"
      + " have a single edge connecting to the child/parent ARGState but it may instead"
      + " be a list.")
  private boolean aggregateBasicBlocks = false;

  private final ImmutableList<TransferRelation> transferRelations;
  private final CFA cfa;
  private final int size;
  private int assumptionIndex = -1;
  private int predicatesIndex = -1;

  public Composite2TransferRelationNoFullCrossProd(ImmutableList<TransferRelation> pTransferRelations,
                                                   Configuration pConfig, CFA pCFA) throws InvalidConfigurationException {
    pConfig.inject(this);
    transferRelations = pTransferRelations;
    cfa = pCFA;
    size = pTransferRelations.size();

    // prepare special case handling if both predicates and assumptions are used
    for (int i = 0; i < size; i++) {
      TransferRelation t = pTransferRelations.get(i);
      if (t instanceof PredicateTransferRelation) {
        predicatesIndex = i;
      }
      if (t instanceof AssumptionStorageTransferRelation) {
        assumptionIndex = i;
      }
    }
  }

  @Override
  public Collection<Composite2State> getAbstractSuccessors(
      AbstractState element, Precision precision)
        throws CPATransferException, InterruptedException {
    Composite2State compositeState = (Composite2State) element;
    Composite2Precision compositePrecision = (Composite2Precision)precision;
    Collection<Composite2State> results;

    AbstractStateWithLocations locState = extractStateByType(compositeState, AbstractStateWithLocations.class);
    if (locState == null) {
      throw new CPATransferException("Analysis without any CPA tracking locations is not supported, please add one to the configuration (e.g., LocationCPA).");
    }

    results = new ArrayList<>(2);

    for (CFAEdge edge : locState.getOutgoingEdges()) {
      getAbstractSuccessorForEdge(compositeState, compositePrecision, edge, results);
    }

    return results;
  }

  @Override
  public Collection<Composite2State> getAbstractSuccessorsForEdge(
      AbstractState element, Precision precision, CFAEdge cfaEdge)
        throws CPATransferException, InterruptedException {
    Composite2State compositeState = (Composite2State) element;
    Composite2Precision compositePrecision = (Composite2Precision)precision;

    Collection<Composite2State> results = new ArrayList<>(1);
    getAbstractSuccessorForEdge(compositeState, compositePrecision, cfaEdge, results);

    return results;
  }


  private void getAbstractSuccessorForEdge(Composite2State compositeState, Composite2Precision compositePrecision, CFAEdge cfaEdge,
                                           Collection<Composite2State> compositeSuccessors) throws CPATransferException, InterruptedException {

    if (aggregateBasicBlocks) {
      final CFANode startNode = cfaEdge.getPredecessor();

      // dynamic multiEdges may be used if the following conditions apply
      if (isValidMultiEdgeStart(startNode)
          && isValidMultiEdgeComponent(cfaEdge)) {

        Collection<Composite2State> currentStates = new ArrayList<>(1);
        currentStates.add(compositeState);

        while (isValidMultiEdgeComponent(cfaEdge)) {
          Collection<Composite2State> successorStates = new ArrayList<>(currentStates.size());

          for (Composite2State currentState : currentStates) {
            getAbstractSuccessorForSimpleEdge(currentState, compositePrecision, cfaEdge, successorStates);
          }

          // if we found a target state in the current successors immediately return
          if (from(successorStates).anyMatch(AbstractStates.IS_TARGET_STATE)) {
            compositeSuccessors.addAll(successorStates);
            return;
          }

          // make successor states the new to-be-handled states for the next edge
          currentStates = Collections.unmodifiableCollection(successorStates);

          // if there is more than one leaving edge we do not create a further
          // multi edge part
          if (cfaEdge.getSuccessor().getNumLeavingEdges() == 1) {
            cfaEdge = cfaEdge.getSuccessor().getLeavingEdge(0);
          } else {
            break;
          }
        }

        compositeSuccessors.addAll(currentStates);

        // no use for dynamic multi edges right now, just compute the successor
        // for the given edge
      } else {
        getAbstractSuccessorForSimpleEdge(compositeState, compositePrecision, cfaEdge, compositeSuccessors);
      }

    } else {
      getAbstractSuccessorForSimpleEdge(compositeState, compositePrecision, cfaEdge, compositeSuccessors);
    }
  }

  private boolean isValidMultiEdgeStart(CFANode node) {
    return node.getNumLeavingEdges() == 1         // linear chain of edges
        && node.getLeavingSummaryEdge() == null   // without a functioncall
        && node.getNumEnteringEdges() > 0;        // without a functionstart
  }

  /**
   * This method checks if the given edge and its successor node are a valid
   * component for a continuing dynamic MultiEdge.
   */
  private boolean isValidMultiEdgeComponent(CFAEdge edge) {
    boolean result = edge.getEdgeType() == CFAEdgeType.BlankEdge
        || edge.getEdgeType() == CFAEdgeType.DeclarationEdge
        || edge.getEdgeType() == CFAEdgeType.StatementEdge
        || edge.getEdgeType() == CFAEdgeType.ReturnStatementEdge;

    CFANode nodeAfterEdge = edge.getSuccessor();

    result = result && nodeAfterEdge.getNumLeavingEdges() == 1
                    && nodeAfterEdge.getNumEnteringEdges() == 1
                    && nodeAfterEdge.getLeavingSummaryEdge() == null
                    && !nodeAfterEdge.isLoopStart()
                    && nodeAfterEdge.getClass() == CFANode.class;

    return result && !containsFunctionCall(edge);
  }

  /**
   * This method checks, if the given (statement) edge contains a function call
   * directly or via a function pointer.
   *
   * @param edge the edge to inspect
   * @return whether or not this edge contains a function call or not.
   */
  private boolean containsFunctionCall(CFAEdge edge) {
    if (edge.getEdgeType() == CFAEdgeType.StatementEdge) {
      CStatementEdge statementEdge = (CStatementEdge)edge;

      if ((statementEdge.getStatement() instanceof CFunctionCall)) {
        CFunctionCall call = ((CFunctionCall)statementEdge.getStatement());
        CSimpleDeclaration declaration = call.getFunctionCallExpression().getDeclaration();

        // declaration == null -> functionPointer
        // functionName exists in CFA -> functioncall with CFA for called function
        // otherwise: call of non-existent function, example: nondet_int() -> ignore this case
        return declaration == null || cfa.getAllFunctionNames().contains(declaration.getQualifiedName());
      }
      return (statementEdge.getStatement() instanceof CFunctionCall);
    }
    return false;
  }

  private void getAbstractSuccessorForSimpleEdge(Composite2State compositeState, Composite2Precision compositePrecision, CFAEdge cfaEdge,
                                                 Collection<Composite2State> compositeSuccessors) throws CPATransferException, InterruptedException {
    assert cfaEdge != null;

    // first, call all the post operators
    Collection<List<AbstractState>> allResultingElements =
        callTransferRelation(compositeState, compositePrecision, cfaEdge);

    // second, call strengthen for each result
    for (List<AbstractState> lReachedState : allResultingElements) {

      Collection<List<AbstractState>> lResultingElements =
          callStrengthen(lReachedState, compositePrecision, cfaEdge);

      // finally, create a Composite2State for each result of strengthen
      for (List<AbstractState> lList : lResultingElements) {
        compositeSuccessors.add(new Composite2State(lList));
      }
    }
  }

  private Collection<List<AbstractState>> callTransferRelation(
      final Composite2State compositeState,
      final Composite2Precision compositePrecision, final CFAEdge cfaEdge)
          throws CPATransferException, InterruptedException {

    int resultCount = 1;
    List<? extends AbstractState> componentElements = compositeState.getWrappedStates();
    checkArgument(componentElements.size() == size, "State with wrong number of component states given");
    List<Collection<? extends AbstractState>> allComponentsSuccessors = new ArrayList<>(size);

    for (int i = 0; i < size; i++) {
      TransferRelation lCurrentTransfer = transferRelations.get(i);
      AbstractState lCurrentElement = componentElements.get(i);
      Precision lCurrentPrecision = compositePrecision.get(i);

      Collection<? extends AbstractState> componentSuccessors;
      componentSuccessors = lCurrentTransfer.getAbstractSuccessorsForEdge(
          lCurrentElement, lCurrentPrecision, cfaEdge);
      resultCount *= componentSuccessors.size();

      allComponentsSuccessors.add(componentSuccessors);
    }

    // create cartesian product of all elements we got
    return createProduct(allComponentsSuccessors, resultCount);
  }

  private Collection<List<AbstractState>> callStrengthen(
      final List<AbstractState> reachedState,
      final Composite2Precision compositePrecision, final CFAEdge cfaEdge)
          throws CPATransferException, InterruptedException {
    List<Collection<? extends AbstractState>> lStrengthenResults = new ArrayList<>(size);
    int resultCount = 1;

    for (int i = 0; i < size; i++) {

      TransferRelation lCurrentTransfer = transferRelations.get(i);
      AbstractState lCurrentElement = reachedState.get(i);
      Precision lCurrentPrecision = compositePrecision.get(i);

      Collection<? extends AbstractState> lResultsList = lCurrentTransfer.strengthen(lCurrentElement, reachedState, cfaEdge, lCurrentPrecision);

      if (lResultsList == null) {
        lStrengthenResults.add(Collections.singleton(lCurrentElement));
      } else {
        resultCount *= lResultsList.size();

        lStrengthenResults.add(lResultsList);
      }
    }

    // special case handling if we have predicate and assumption cpas
    // TODO remove as soon as we call strengthen in a fixpoint loop
    if (predicatesIndex >= 0 && assumptionIndex >= 0 && resultCount > 0) {
      AbstractState predElement = Iterables.getOnlyElement(lStrengthenResults.get(predicatesIndex));
      AbstractState assumptionElement = Iterables.getOnlyElement(lStrengthenResults.get(assumptionIndex));
      Precision predPrecision = compositePrecision.get(predicatesIndex);
      TransferRelation predTransfer = transferRelations.get(predicatesIndex);

      Collection<? extends AbstractState> predResult = predTransfer.strengthen(predElement, Collections.singletonList(assumptionElement), cfaEdge, predPrecision);
      resultCount *= predResult.size();

      lStrengthenResults.set(predicatesIndex, predResult);
    }

    // create cartesian product
    return createProduct(lStrengthenResults, resultCount);
  }

  protected Collection<List<AbstractState>> createProduct(
      List<Collection<? extends AbstractState>> allComponentsSuccessors, int expectedCartesianCount) {

    assert allComponentsSuccessors.size() == size;

    Collection<List<AbstractState>> result = Lists.newArrayList();
    for (Collection<? extends AbstractState> keepTargetIn: allComponentsSuccessors) {
      boolean fullSet = true;
      int newExpectedCartesianCount = 1;
      List<Collection<? extends AbstractState>> newComponentsSuccessors = Lists.newArrayList();
      for (Collection<? extends AbstractState> inner: allComponentsSuccessors) {
        if (inner != keepTargetIn) {
          List<AbstractState> newInner = Lists.newArrayList();
          for (AbstractState e: inner) {
            if (AbstractStates.isTargetState(e) && !(inner.size() == 1)) {
              fullSet = false;
            } else {
              newInner.add(e);
            }
          }
          assert newInner.size() > 0 || inner.size() == 0;
          newExpectedCartesianCount *= newInner.size();
          newComponentsSuccessors.add(newInner);
        } else {
          newExpectedCartesianCount *= inner.size();
          newComponentsSuccessors.add(inner);
        }
      }
      assert newComponentsSuccessors.size() == allComponentsSuccessors.size();
      result.addAll(createCartesianProduct(newComponentsSuccessors, newExpectedCartesianCount));
      if (fullSet) {
        break;
      }
    }
    return result;

  }

  protected Collection<List<AbstractState>> createCartesianProduct(
      List<Collection<? extends AbstractState>> allComponentsSuccessors, int resultCount) {

    Collection<List<AbstractState>> allResultingElements;
    switch (resultCount) {
    case 0:
      // at least one CPA decided that there is no successor
      allResultingElements = Collections.emptySet();
      break;

    case 1:
      List<AbstractState> resultingElements = new ArrayList<>(allComponentsSuccessors.size());
      for (Collection<? extends AbstractState> componentSuccessors : allComponentsSuccessors) {
        resultingElements.add(Iterables.getOnlyElement(componentSuccessors));
      }
      allResultingElements = Collections.singleton(resultingElements);
      break;

    default:
      // create cartesian product of all componentSuccessors and store the result in allResultingElements
      List<AbstractState> initialPrefix = Collections.emptyList();
      allResultingElements = new ArrayList<>(resultCount);
      createCartesianProduct0(allComponentsSuccessors, initialPrefix, allResultingElements);
    }

    return allResultingElements;
  }

  private void createCartesianProduct0(List<Collection<? extends AbstractState>> allComponentsSuccessors,
      List<AbstractState> prefix, Collection<List<AbstractState>> allResultingElements) {

    if (prefix.size() == allComponentsSuccessors.size()) {
      allResultingElements.add(prefix);

    } else {
      int depth = prefix.size();
      Collection<? extends AbstractState> myComponentsSuccessors = allComponentsSuccessors.get(depth);

      for (AbstractState currentComponent : myComponentsSuccessors) {
        List<AbstractState> newPrefix = new ArrayList<>(prefix);
        newPrefix.add(currentComponent);

        createCartesianProduct0(allComponentsSuccessors, newPrefix, allResultingElements);
      }
    }
  }

  @Override
  public Collection<? extends AbstractState> strengthen(AbstractState element,
      List<AbstractState> otherElements, CFAEdge cfaEdge,
      Precision precision) {
    // strengthen is only called by the composite CPA on its component CPAs
    return null;
  }

  boolean areAbstractSuccessors(AbstractState pElement, CFAEdge pCfaEdge, Collection<? extends AbstractState> pSuccessors, List<ConfigurableProgramAnalysis> cpas) throws CPATransferException, InterruptedException {
    Preconditions.checkNotNull(pCfaEdge);

    Composite2State compositeState = (Composite2State)pElement;

    int resultCount = 1;
    boolean result = true;
    for (int i = 0; i < size; ++i) {
      Set<AbstractState> componentSuccessors = new HashSet<>();
      for (AbstractState successor : pSuccessors) {
        Composite2State compositeSuccessor = (Composite2State)successor;
        if (compositeSuccessor.getNumberOfStates() != size) {
          return false;
        }
        componentSuccessors.add(compositeSuccessor.get(i));
      }
      resultCount *= componentSuccessors.size();
      ProofChecker componentProofChecker = (ProofChecker)cpas.get(i);
      if (!componentProofChecker.areAbstractSuccessors(compositeState.get(i), pCfaEdge, componentSuccessors)) {
        result = false; //if there are no successors it might be still ok if one of the other components is fine with the empty set
      } else {
        if (componentSuccessors.isEmpty()) {
          assert pSuccessors.isEmpty();
          return true; //another component is indeed fine with the empty set as set of successors; transition is ok
        }
      }
    }

    if (resultCount > pSuccessors.size()) { return false; }

    HashSet<List<? extends AbstractState>> states = new HashSet<>();
    for (AbstractState successor : pSuccessors) {
      states.add(((Composite2State) successor).getWrappedStates());
    }
    if (resultCount != states.size()) { return false; }

    return result;
  }
}