/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker. 
 *
 *  Copyright (C) 2007-2009  Dirk Beyer and Erkan Keremoglu.
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
package programtesting;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * @author Andreas Holzer <holzer@forsyte.de>
 *
 */
public class ParametricAbstractReachabilityTree<TreeElement> {

  private TreeElement mRoot;
  private Map<TreeElement, Collection<TreeElement>> mChildren;
  private Map<TreeElement, TreeElement> mParents;
  private boolean mUpdatedFlag;

  public ParametricAbstractReachabilityTree() {
    mRoot = null;
    mChildren = new HashMap<TreeElement, Collection<TreeElement>>();
    mParents = new HashMap<TreeElement, TreeElement>();
    unsetUpdatedFlag();
  }
  
  public void unsetUpdatedFlag() {
    mUpdatedFlag = false;
  }
  
  public boolean hasBeenUpdated() {
    return mUpdatedFlag;
  }

  public void clear() {
    mChildren.clear();
    mRoot = null;
    mUpdatedFlag = true;
  }

  public void setRoot(TreeElement pRoot) {
    assert (pRoot != null);
    assert (mRoot == null);

    mRoot = pRoot;

    createEntry(mRoot);
  }

  public TreeElement getRoot() {
    assert (mRoot != null);

    return mRoot;
  }

  public boolean hasRoot() {
    return (mRoot != null);
  }

  private void createEntry(TreeElement pElement) {
    assert (pElement != null);
    assert (!contains(pElement));

    mChildren.put(pElement, new HashSet<TreeElement>());
    
    mUpdatedFlag = true;
  }

  public void add(TreeElement pParent, TreeElement pChild) {
    assert (pParent != null);
    assert (pChild != null);

    // pChild has to be a new element in the tree
    assert (!contains(pChild));
    // pParent has to be an element in the tree
    assert (contains(pParent));

    //System.out.println(pParent + " ---> " + pChild);
    
    Collection<TreeElement> lParentEntry = getChildren(pParent);
    lParentEntry.add(pChild);

    createEntry(pChild);
    
    mParents.put(pChild, pParent);
  }
  
  public boolean hasParent(TreeElement pElement) {
    assert(pElement != null);
    
    return mParents.containsKey(pElement);
  }
  
  public TreeElement getParent(TreeElement pElement) {
    assert(pElement != null);
    
    return mParents.get(pElement);
  }

  public boolean contains(TreeElement pElement) {
    assert (pElement != null);

    return mChildren.containsKey(pElement);
  }

  public Collection<TreeElement> getChildren(TreeElement pElement) {
    assert (pElement != null);
    assert (contains(pElement));

    return mChildren.get(pElement);
  }
  
  public int size() {
    return mChildren.keySet().size();
  }
  
  public void removeSubtree(TreeElement lElement) {
    assert(lElement != null);

    Collection<TreeElement> lChildren = new HashSet<TreeElement>(getChildren(lElement));
    
    //for (TreeElement lChildElement : getChildren(lElement)) {
    for (TreeElement lChildElement : lChildren) {
      removeSubtree(lChildElement);
    }
    
    // TODO remove lElement from parent element
    mChildren.remove(lElement);
    
    if (hasParent(lElement)) {
      TreeElement lParent = getParent(lElement);
      
      Collection<TreeElement> lParentsChildren = getChildren(lParent);
      
      lParentsChildren.remove(lElement);
    }
    
    mUpdatedFlag = true;
  }
  
  public String toDot() {
    String lResultString = "digraph ART {\n" +
            "size=\"6,10\";\n";
    
    int lUniqueId = 0;
    
    Map<TreeElement, Integer> lIdMap = new HashMap<TreeElement, Integer>();
    
    for (TreeElement lElement : mChildren.keySet()) {
      lIdMap.put(lElement, lUniqueId);
      lResultString += "node [label = \"" + lElement.toString() + "\"]; " + (lUniqueId++) + ";\n";
    }
    
    for (Map.Entry<TreeElement, Collection<TreeElement>> lEntry : mChildren.entrySet()) {
      for (TreeElement lChild : lEntry.getValue()) {
        lResultString += lIdMap.get(lEntry.getKey()) + " -> " + lIdMap.get(lChild) + ";\n";
      }
    }
    
    lResultString += "}";
    
    return lResultString;
  }
  
  public String toDot(Collection<TreeElement> lSpecialElements) {
    assert(lSpecialElements != null);
    
    String lResultString = "digraph ART {\n" +
            "size=\"6,10\";\n";
    
    int lUniqueId = 0;
    
    Map<TreeElement, Integer> lIdMap = new HashMap<TreeElement, Integer>();
    
    for (TreeElement lElement : mChildren.keySet()) {
      lIdMap.put(lElement, lUniqueId);
      
      String lShapeString = "shape=";
      
      if (lSpecialElements.contains(lElement)) {
        lShapeString += "diamond, fillcolor=yellow, style=filled";
      }
      else {
        lShapeString += "box, fillcolor=white";
      }
      
      lResultString += "node [label = \"" + lElement.toString() + "\", " + lShapeString + "]; " + (lUniqueId++) + ";\n";
    }
    
    for (Map.Entry<TreeElement, Collection<TreeElement>> lEntry : mChildren.entrySet()) {
      for (TreeElement lChild : lEntry.getValue()) {
        lResultString += lIdMap.get(lEntry.getKey()) + " -> " + lIdMap.get(lChild) + ";\n";
      }
    }
    
    lResultString += "}\n";
    
    return lResultString;
  }
}
