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
package org.sosy_lab.cpachecker.cpa.composite;

import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.core.interfaces.AbstractDomain;
import org.sosy_lab.cpachecker.core.interfaces.AbstractState;
import org.sosy_lab.cpachecker.core.interfaces.ConfigurableProgramAnalysis;
import org.sosy_lab.cpachecker.core.interfaces.Precision;
import org.sosy_lab.cpachecker.core.interfaces.StopOperator;
import org.sosy_lab.cpachecker.cpa.summary.blocks.Block;
import org.sosy_lab.cpachecker.cpa.summary.interfaces.Summary;
import org.sosy_lab.cpachecker.cpa.summary.interfaces.SummaryManager;
import org.sosy_lab.cpachecker.cpa.summary.interfaces.UseSummaryCPA;
import org.sosy_lab.cpachecker.exceptions.CPAException;

/**
 * Summary manager for the {@link CompositeCPA}.
 */
public class CompositeSummaryManager implements SummaryManager {

  private final List<SummaryManager> managers;
  private final List<AbstractDomain> domains;
  private final List<StopOperator> stopOperators;

  CompositeSummaryManager(List<ConfigurableProgramAnalysis> pCpas) {
    ImmutableList<UseSummaryCPA> summaryCPAs =
        FluentIterable.from(pCpas).filter(UseSummaryCPA.class)
            .toList();
    Preconditions.checkArgument(summaryCPAs.size() == pCpas.size(),
        "Not all CPAs implement UseSummaryCPA,"
            + " the offending element is " + pCpas.stream().filter(
                s -> !(s instanceof UseSummaryCPA)).findAny());
    managers = summaryCPAs.stream().map(
        cpa -> cpa.getSummaryManager()
    ).collect(Collectors.toList());
    domains = summaryCPAs.stream().map(
        cpa -> cpa.getAbstractDomain()
    ).collect(Collectors.toList());
    stopOperators = summaryCPAs.stream().map(
        cpa -> cpa.getStopOperator()
    ).collect(Collectors.toList());
  }

  @Override
  public AbstractState getAbstractSuccessorForSummary(
      AbstractState state, Precision precision, List<Summary> pSummary, Block pBlock)
      throws CPAException, InterruptedException {

    CompositePrecision cPrecision = (CompositePrecision) precision;
    CompositeState cState = (CompositeState) state;
    Preconditions.checkState(cState.getNumberOfStates() == managers.size());

    List<AbstractState> contained = new ArrayList<>(managers.size());
    for (int i=0; i<managers.size(); i++) {
      int idx = i;
      List<Summary> projectedSummaries = FluentIterable.from(pSummary)
          .filter(CompositeSummary.class)
          .transform(c -> c.get(idx)).toList();
      AbstractState successor = managers.get(idx).getAbstractSuccessorForSummary(
          cState.get(idx), cPrecision.get(idx), projectedSummaries, pBlock
      );
      contained.add(successor);
    }
    return new CompositeState(contained);
  }

  @Override
  public AbstractState getWeakenedCallState(
      AbstractState pState, Precision pPrecision, Block pBlock) {
    CompositeState cState = (CompositeState) pState;
    CompositePrecision cPrecision = (CompositePrecision) pPrecision;
    List<AbstractState> weakened = IntStream.range(0, managers.size())
        .mapToObj(i ->
            managers.get(i).getWeakenedCallState(
                cState.get(i), cPrecision.get(i), pBlock
            )).collect(Collectors.toList());
    return new CompositeState(weakened);
  }

  @Override
  public CompositeState projectToCallsite(Summary pSummary) {
    CompositeSummary cSummary = (CompositeSummary) pSummary;
    return new CompositeState(
        IntStream.range(0, managers.size()).mapToObj(
            i -> managers.get(i).projectToCallsite(
                cSummary.get(i)
            )).collect(Collectors.toList())
    );
  }

  @Override
  public CompositeState projectToPostcondition(Summary pSummary) {
    CompositeSummary cSummary = (CompositeSummary) pSummary;
    return new CompositeState(
        IntStream.range(0, managers.size()).mapToObj(
            i -> managers.get(i).projectToPostcondition(
                cSummary.get(i)
            )).collect(Collectors.toList())
        );
  }

  @Override
  public List<? extends Summary> generateSummaries(
      AbstractState pCallState,
      Precision pEntryPrecision,
      List<? extends AbstractState> pReturnStates,
      List<Precision> pReturnPrecisions,
      CFANode pEntryNode,
      Block pBlock) {
    CompositeState cEntryState = (CompositeState) pCallState;
    CompositePrecision cEntryPrecision = (CompositePrecision) pEntryPrecision;

    FluentIterable<CompositeState> cReturnStates = FluentIterable.from(pReturnStates)
        .filter(CompositeState.class);
    FluentIterable<CompositePrecision> cReturnPrecisions = FluentIterable.from(pReturnPrecisions)
        .filter(CompositePrecision.class);

    List<List<? extends Summary>> computed = new ArrayList<>(managers.size());
    for (int i=0; i<managers.size(); i++) {
      final int idx = i;
      computed.add(managers.get(i).generateSummaries(
            cEntryState.get(i),
            cEntryPrecision.get(i),
            cReturnStates.transform(s -> s.get(idx)).toList(),
            cReturnPrecisions.transform(s -> s.get(idx)).toList(),
            pEntryNode,
            pBlock
        ));
    }

    List<List<Summary>> product = Lists.cartesianProduct(computed);
    return product.stream().map(l -> new CompositeSummary(l)).collect(Collectors.toList());
  }


  @Override
  public boolean isDescribedBy(Summary pSummary1,
                               Summary pSummary2,
                               AbstractDomain pAbstractDomain) throws CPAException,
                                                                      InterruptedException {
    CompositeSummary cSummary1 = (CompositeSummary) pSummary1;
    CompositeSummary cSummary2 = (CompositeSummary) pSummary2;
    for (int i=0; i<managers.size(); i++) {
      if (!domains.get(i).isLessOrEqual(
          managers.get(i).projectToCallsite(cSummary1.get(i)),
          managers.get(i).projectToCallsite(cSummary2.get(i))
      ) && domains.get(i).isLessOrEqual(
          managers.get(i).projectToPostcondition(cSummary1.get(i)),
          managers.get(i).projectToPostcondition(cSummary2.get(i))
      )) {
        return false;
      }
    }
    return true;
  }

  @Override
  public CompositeSummary merge(
      Summary pSummary1, Summary pSummary2) throws CPAException, InterruptedException {

    CompositeSummary cSummary1 = (CompositeSummary) pSummary1;
    CompositeSummary cSummary2 = (CompositeSummary) pSummary2;

    boolean identicalStates = true;
    List<Summary> mergedSummaries = new ArrayList<>(managers.size());
    for (int i = 0; i < managers.size(); i++) {
      SummaryManager mgr = managers.get(i);
      AbstractDomain domain = domains.get(i);
      Summary s1 = cSummary1.get(i);
      Summary s2 = cSummary2.get(i);
      Summary merged = mgr.merge(s1, s2);

      if (!mgr.isDescribedBy(s1, merged, domain)) {

        // The result does not cover s1 => might as well perform splitting on the
        // entire state-space.
        return cSummary2;
      }

      if (merged != s2) {
        identicalStates = false;
      }
      mergedSummaries.add(merged);
    }
    if (identicalStates) {
      return cSummary2;
    }
    return new CompositeSummary(mergedSummaries);
  }

  private static class CompositeSummary implements Summary {

    private final List<Summary> summaries;

    private CompositeSummary(Collection<? extends Summary> pSummaries) {
      summaries = ImmutableList.copyOf(pSummaries);
    }

    public Summary get(int idx) {
      return summaries.get(idx);
    }

    @Override
    public boolean equals(@Nullable Object pO) {
      if (this == pO) {
        return true;
      }
      if (pO == null || getClass() != pO.getClass()) {
        return false;
      }
      CompositeSummary that = (CompositeSummary) pO;
      return Objects.equals(summaries, that.summaries);
    }

    @Override
    public int hashCode() {
      return summaries.hashCode();
    }

    @Override
    public String toString() {
      return "CompositeSummary{" +
          "summaries=" + summaries + '}';
    }
  }
}