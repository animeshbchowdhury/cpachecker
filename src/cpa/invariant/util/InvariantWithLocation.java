/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker. 
 *
 *  Copyright (C) 2007-2008  Dirk Beyer and Erkan Keremoglu.
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
 *    http://www.cs.sfu.ca/~dbeyer/CPAchecker/
 */
package cpa.invariant.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import cfa.objectmodel.CFANode;

import symbpredabstraction.interfaces.SymbolicFormula;

/**
 * Representation of an invariant of the form \land_i. pc = l_i ==> \phi_i
 * 
 * @author g.theoduloz
 */
public class InvariantWithLocation {
  
  // map from location to (conjunctive) list of invariants
  private final Map<CFANode, List<SymbolicFormula>> map;
  private final MathsatInvariantSymbolicFormulaManager manager;
  
  public InvariantWithLocation() {
    map = new HashMap<CFANode, List<SymbolicFormula>>();
    manager = MathsatInvariantSymbolicFormulaManager.getInstance();
  }
  
  /**
   * Return the invariants for a given node as a list
   */
  public List<SymbolicFormula> getInvariants(CFANode node)
  {
    List<SymbolicFormula> result = map.get(node);
    if (result == null)
      return Collections.emptyList();
    else
      return result;
  }
  
  /**
   * Return the invariant as a formula for a given node
   */
  public SymbolicFormula getInvariant(CFANode node)
  {
    SymbolicFormula result = manager.makeTrue();
    List<SymbolicFormula> invariants = getInvariants(node);
    for (SymbolicFormula f : invariants)
    {
      result = manager.makeAnd(result, f);
    }
    return result;
  }

  /**
   * Add an invariant at the given location
   */
  public void addInvariant(CFANode node, SymbolicFormula invariant)
  {
    List<SymbolicFormula> old = map.get(node.getNodeNumber());
    if (old == null)
      old = new LinkedList<SymbolicFormula>();
    for (SymbolicFormula other : old) {
      if (invariant.equals(other))
        return; // already in the list
    }
    old.add(invariant);
  }
  
}
