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
package org.sosy_lab.cpachecker.cpa.automaton;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.core.defaults.SingleEdgeTransferRelation;
import org.sosy_lab.cpachecker.core.interfaces.AbstractState;
import org.sosy_lab.cpachecker.core.interfaces.Precision;
import org.sosy_lab.cpachecker.exceptions.CPATransferException;
import org.sosy_lab.cpachecker.util.Cartesian;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

@Options
public final class PowersetAutomatonTransferRelation extends SingleEdgeTransferRelation {

  public static enum TransferProductMode {
    CARTESIAN, JOIN, JOIN_SEP_TARGETS;
  }
  @Option(secure=true, description="Mode of the automaton powerset transfer relation.")
  private TransferProductMode transferMode = TransferProductMode.JOIN_SEP_TARGETS;

  private final AutomatonTransferRelation componentTransfer;
  private final PowersetAutomatonDomain domain;

  public PowersetAutomatonTransferRelation(Configuration pConfig,
      AutomatonTransferRelation pComponentTransferRelation, PowersetAutomatonDomain pDomain)
      throws InvalidConfigurationException {

    Preconditions.checkNotNull(pConfig);
    pConfig.inject(this);

    componentTransfer = Preconditions.checkNotNull(pComponentTransferRelation);
    domain = Preconditions.checkNotNull(pDomain);
  }

  @Override
  public Collection<PowersetAutomatonState> getAbstractSuccessorsForEdge(
      AbstractState pElement, Precision pPrecision, CFAEdge pCfaEdge)
        throws CPATransferException, InterruptedException {

    switch (transferMode) {
      case JOIN: return joiningProductTransfer(pElement, pPrecision, pCfaEdge);
      case JOIN_SEP_TARGETS: return joiningButSepTargetsProductTransfer(pElement, pPrecision, pCfaEdge);
      default: return cartesianProductTransfer(pElement, pPrecision, pCfaEdge);
    }
  }

  private PowersetAutomatonState buildSuccessorState(PowersetAutomatonState pPred,
      Set<AutomatonState> successorElements) {

    PowersetAutomatonState candidateSuccessor = new PowersetAutomatonState(successorElements);
    if (candidateSuccessor.equals(pPred)) {
      return pPred;
    }

    return candidateSuccessor;
  }

  private Collection<PowersetAutomatonState> joiningButSepTargetsProductTransfer(AbstractState pElement,
      Precision pPrecision, CFAEdge pCfaEdge) throws CPATransferException {

    final PowersetAutomatonState element = (PowersetAutomatonState) pElement;
    Set<AutomatonState> nonTargetSuccessors = Sets.newLinkedHashSet();
    Set<AutomatonState> targetSuccessors = Sets.newLinkedHashSet();

    // Given a composite automaton state e = [q1, q2]
    //  Successors of the states:
    //    succ(q1) = [q3]
    //    succ(q2) = [q4,q5]
    //
    //  This should result in ONE composite state:
    //    e'  = [q3, q4, q5]
    //
    //    (which is the join of [q3] and [q4,q5]
    //
    // BUT... each target state should be provided in
    //    a separate successor state!

    for (AutomatonState comp: element) {
      Collection<AutomatonState> succOfComp = componentTransfer.getAbstractSuccessorsForEdge(comp, pPrecision, pCfaEdge);
      for (AutomatonState succ: succOfComp) {
        if (succ.isTarget()) {
          targetSuccessors.add(succ);
        } else {
          nonTargetSuccessors.add(succ);
        }
      }
    }

    Builder<PowersetAutomatonState> result = ImmutableSet.<PowersetAutomatonState>builder();
    for (AutomatonState e: targetSuccessors) {
      result.add(new PowersetAutomatonState(ImmutableSet.of(e)));
    }
    if (nonTargetSuccessors.size() > 0) {
      result.add(buildSuccessorState(element, nonTargetSuccessors));
    }
    return result.build();
  }

  private Collection<PowersetAutomatonState> joiningProductTransfer(AbstractState pElement,
      Precision pPrecision, CFAEdge pCfaEdge) throws CPATransferException {

    final PowersetAutomatonState element = (PowersetAutomatonState) pElement;
    Set<AutomatonState> successors = Sets.newLinkedHashSet();

    // Given a composite automaton state e = [q1, q2]
    //  Successors of the states:
    //    succ(q1) = [q3]
    //    succ(q2) = [q4,q5]
    //
    //  This should result in ONE composite state:
    //    e'  = [q3, q4, q5]
    //
    //    (which is the join of [q3] and [q4,q5]

    for (AutomatonState comp: element) {
      Collection<AutomatonState> succOfComp = componentTransfer.getAbstractSuccessorsForEdge(comp, pPrecision, pCfaEdge);
      successors.addAll(succOfComp);
    }

    return ImmutableSet.of(buildSuccessorState(element, successors));
  }

  private Collection<PowersetAutomatonState> cartesianProductTransfer(AbstractState pElement,
      Precision pPrecision, CFAEdge pCfaEdge) throws CPATransferException {

    PowersetAutomatonState compositeState = (PowersetAutomatonState) pElement;
    Collection<Collection<AutomatonState>> componentSuccessors = Lists.newArrayList();

    // Given a composite automaton state e = [q1, q2]
    //  Successors of the states:
    //    succ(q1) = [q3]
    //    succ(q2) = [q4,q5]
    //
    //  This should result in two composite states:
    //    e'  = [q3, q4]
    //    e'' = [q3, q5]
    //    (which is the cartesian product: [q3] x [q4,q5])

    for (AutomatonState comp: compositeState) {
      Collection<AutomatonState> succOfComp = componentTransfer.getAbstractSuccessorsForEdge(comp, pPrecision, pCfaEdge);

      // Splits (several successors for one automaton state)
      //  are possible, and necessary to represent disjunctions in assumptions!
      //
      // Since assumptions are handled in the strengthening of the transfer relation
      //  a merge is possible after the strengthening has been performed!!!

      componentSuccessors.add(succOfComp);
    }

    return buildCartesianProduct(componentSuccessors);
  }

  @Override
  public Collection<PowersetAutomatonState> strengthen(AbstractState pState, List<AbstractState> pOtherStates,
      CFAEdge pCfaEdge, Precision pPrecision) throws CPATransferException, InterruptedException {

    Preconditions.checkArgument(pState instanceof PowersetAutomatonState);

    final PowersetAutomatonState state = (PowersetAutomatonState) pState;
    Collection<Collection<AutomatonState>> componentSuccessors = Lists.newArrayList();

    for (AutomatonState comp: state) {
      @Nullable Collection<AutomatonState> strengthenedComp = componentTransfer.strengthen(
          comp, pOtherStates, pCfaEdge, pPrecision);

      if (strengthenedComp == null) { // no change
        componentSuccessors.add(ImmutableSet.of(comp));
      } else {
        componentSuccessors.add(strengthenedComp);
      }
    }

    return buildCartesianProduct(componentSuccessors);
  }

  private Collection<PowersetAutomatonState> buildCartesianProduct(
      Collection<Collection<AutomatonState>> componentSuccessors) {

    Preconditions.checkNotNull(componentSuccessors);

    Builder<PowersetAutomatonState> result = ImmutableSet.<PowersetAutomatonState>builder();

    // The result is based on computing the CARTESIAN PRODUCT!
    for (Collection<AutomatonState> c: Cartesian.product(componentSuccessors)) {
      PowersetAutomatonState ca = new PowersetAutomatonState(c);
      result.add(ca);
    }

    return result.build();
  }

}
