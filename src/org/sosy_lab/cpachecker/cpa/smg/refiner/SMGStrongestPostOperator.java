/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2015  Dirk Beyer
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
package org.sosy_lab.cpachecker.cpa.smg.refiner;

import java.util.Collection;
import java.util.Deque;

import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.core.interfaces.AbstractState;
import org.sosy_lab.cpachecker.core.interfaces.Precision;
import org.sosy_lab.cpachecker.cpa.arg.ARGPath;
import org.sosy_lab.cpachecker.cpa.smg.SMGPredicateManager;
import org.sosy_lab.cpachecker.cpa.smg.SMGState;
import org.sosy_lab.cpachecker.cpa.smg.SMGTransferRelation;
import org.sosy_lab.cpachecker.exceptions.CPAException;
import org.sosy_lab.cpachecker.util.refinement.StrongestPostOperator;

import com.google.common.base.Optional;
import com.google.common.collect.Iterables;


public class SMGStrongestPostOperator implements StrongestPostOperator<SMGState> {

  private final SMGTransferRelation transfer;

  public SMGStrongestPostOperator(LogManager pLogger, Configuration pBuild, CFA pCfa,
                                  SMGPredicateManager pSMGPredicateManager)
      throws InvalidConfigurationException {
    transfer = SMGTransferRelation.createTransferRelationForRefinement(pBuild, pLogger, pCfa
        .getMachineModel(), pSMGPredicateManager);
  }

  @Override
  public Optional<SMGState> getStrongestPost(SMGState pOrigin, Precision pPrecision, CFAEdge pOperation)
      throws CPAException, InterruptedException {


    final Collection<? extends AbstractState> successors =
        transfer.getAbstractSuccessorsForEdge(pOrigin, pPrecision, pOperation);

    if (successors.isEmpty()) {
      return Optional.absent();

    } else {
      return Optional.of((SMGState) Iterables.getOnlyElement(successors));
    }
  }

  @Override
  public SMGState handleFunctionCall(SMGState pState, CFAEdge pEdge, Deque<SMGState> pCallstack) {
    return pState;
  }

  @Override
  public SMGState handleFunctionReturn(SMGState pNext, CFAEdge pEdge, Deque<SMGState> pCallstack) {
    // TODO investigate scoping?
    return pNext;
  }

  @Override
  public SMGState performAbstraction(SMGState pNext, CFANode pCurrNode, ARGPath pErrorPath, Precision pPrecision) {
    // TODO Investigate abstraction
    return pNext;
  }

}
