/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.joshua.decoder.chart_parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.joshua.decoder.ff.tm.Grammar;
import org.apache.joshua.decoder.ff.tm.Rule;
import org.apache.joshua.decoder.ff.tm.RuleCollection;
import org.apache.joshua.decoder.ff.tm.Trie;
import org.apache.joshua.decoder.ff.tm.hash_based.MemoryBasedTrie;
import org.apache.joshua.decoder.segment_file.Token;
import org.apache.joshua.lattice.Arc;
import org.apache.joshua.lattice.Lattice;
import org.apache.joshua.lattice.Node;
import org.apache.joshua.util.ChartSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The DotChart handles Earley-style implicit binarization of translation rules.
 *
 * The {@link DotNode} object represents the (possibly partial) application of a synchronous rule.
 * The implicit binarization is maintained with a pointer to the {@link Trie} node in the grammar,
 * for easy retrieval of the next symbol to be matched. At every span (i,j) of the input sentence,
 * every incomplete DotNode is examined to see whether it (a) needs a terminal and matches against
 * the final terminal of the span or (b) needs a nonterminal and matches against a completed
 * nonterminal in the main chart at some split point (k,j).
 *
 * Once a rule is completed, it is entered into the {@link DotChart}. 
 *
 * There is a separate DotChart for every grammar.
 *
 * @author Zhifei Li, <zhifei.work@gmail.com>
 * @author Matt Post <post@cs.jhu.edu>
 * @author Kristy Hollingshead Seitz
 */
class DotChart {

  // ===============================================================
  // Static fields
  // ===============================================================

  private static final Logger LOG = LoggerFactory.getLogger(DotChart.class);


  // ===============================================================
  // Package-protected instance fields
  // ===============================================================
  /**
   * Two-dimensional chart of cells. Some cells might be null. This could definitely be represented
   * more efficiently, since only the upper half of this triangle is every used.
   */
  private final ChartSpan<ArrayList<DotNode>> dotcells;

  public List<DotNode> getDotCell(int i, int j) {
    return dotcells.get(i, j);
  }

  // ===============================================================
  // Private instance fields (maybe could be protected instead)
  // ===============================================================

  /**
   * CKY+ style parse chart in which completed span entries are stored.
   */
  private final Chart dotChart;

  /**
   * Translation grammar which contains the translation rules.
   */
  private final Grammar pGrammar;

  /* Length of input sentence. */
  private final int sentLen;

  /* Represents the input sentence being translated. */
  private final Lattice<Token> input;

  // ===============================================================
  // Constructors
  // ===============================================================

  // TODO: Maybe this should be a non-static inner class of Chart. That would give us implicit
  // access to all the arguments of this constructor. Though we would need to take an argument, i,
  // to know which Chart.this.grammars[i] to use.

  /**
   * Constructs a new dot chart from a specified input lattice, a translation grammar, and a parse
   * chart.
   *
   * @param input A lattice which represents an input sentence.
   * @param grammar A translation grammar.
   * @param chart A CKY+ style chart in which completed span entries are stored.
   */
  public DotChart(Lattice<Token> input, Grammar grammar, Chart chart) {

    this.dotChart = chart;
    this.pGrammar = grammar;
    this.input = input;
    this.sentLen = input.size();
    this.dotcells = new ChartSpan<>(sentLen, null);

    seed();
  }

  /**
   * Add initial dot items: dot-items pointer to the root of the grammar trie.
   */
  void seed() {
    for (int j = 0; j <= sentLen - 1; j++) {
      if (pGrammar.hasRuleForSpan(j, j, input.distance(j, j))) {
        if (null == pGrammar.getTrieRoot()) {
          throw new RuntimeException("trie root is null");
        }
        addDotItem(pGrammar.getTrieRoot(), j, j, null, null, new SourcePath());
      }
    }
  }

  /**
   * This function computes all possible expansions of all rules over the provided span (i,j). By
   * expansions, we mean the moving of the dot forward (from left to right) over a nonterminal or
   * terminal symbol on the rule's source side.
   *
   * There are two kinds of expansions:
   *
   * <ol>
   * <li>Expansion over a nonterminal symbol. For this kind of expansion, a rule has a dot
   * immediately prior to a source-side nonterminal. The main Chart is consulted to see whether
   * there exists a completed nonterminal with the same label. If so, the dot is advanced.
   *
   * Discovering nonterminal expansions is a matter of enumerating all split points k such that i <
   * k and k < j. The nonterminal symbol must exist in the main Chart over (k,j).
   *
   * <li>Expansion over a terminal symbol. In this case, expansion is a simple matter of determing
   * whether the input symbol at position j (the end of the span) matches the next symbol in the
   * rule. This is equivalent to choosing a split point k = j - 1 and looking for terminal symbols
   * over (k,j). Note that phrases in the input rule are handled one-by-one as we consider longer
   * spans.
   * </ol>
   */
  void expandDotCell(int i, int j) {
    if (LOG.isDebugEnabled())
      LOG.debug("Expanding dot cell ({}, {})", i, j);

    /*
     * (1) If the dot is just to the left of a non-terminal variable, we look for theorems or axioms
     * in the Chart that may apply and extend the dot position. We look for existing axioms over all
     * spans (k,j), i < k < j.
     */
    for (int k = i + 1; k < j; k++) {
      extendDotItemsWithProvedItems(i, k, j, false);
    }

    /*
     * (2) If the the dot-item is looking for a source-side terminal symbol, we simply match against
     * the input sentence and advance the dot.
     */
    Node<Token> node = input.getNode(j - 1);
    for (Arc<Token> arc : node.getOutgoingArcs()) {

      int last_word = arc.getLabel().getWord();
      int arc_len = arc.getHead().getNumber() - arc.getTail().getNumber();

      // int last_word=foreign_sent[j-1]; // input.getNode(j-1).getNumber(); //

      if (null != dotcells.get(i, j - 1)) {
        // dotitem in dot_bins[i][k]: looking for an item in the right to the dot


        for (DotNode dotNode : dotcells.get(i, j - 1)) {

          // String arcWord = Vocabulary.word(last_word);
          // Assert.assertFalse(arcWord.endsWith("]"));
          // Assert.assertFalse(arcWord.startsWith("["));
          // logger.info("DotChart.expandDotCell: " + arcWord);

          // List<Trie> child_tnodes = ruleMatcher.produceMatchingChildTNodesTerminalevel(dotNode,
          // last_word);

          Trie child_node = dotNode.trieNode.match(last_word);
          if (null != child_node) {
            addDotItem(child_node, i, j - 1 + arc_len, dotNode.antSuperNodes, null,
                dotNode.srcPath.extend(arc));
          }
        }
      }
    }
  }

  /**
   * note: (i,j) is a non-terminal, this cannot be a cn-side terminal, which have been handled in
   * case2 of dotchart.expand_cell add dotitems that start with the complete super-items in
   * cell(i,j)
   */
  void startDotItems(int i, int j) {
    extendDotItemsWithProvedItems(i, i, j, true);
  }

  // ===============================================================
  // Private methods
  // ===============================================================

  /**
   * Attempt to combine an item in the dot chart with an item in the main chart to create a new item
   * in the dot chart. The DotChart item is a {@link DotNode} begun at position i with the dot
   * currently at position k, that is, a partially-applied rule.
   *
   * In other words, this method looks for (proved) theorems or axioms in the completed chart that
   * may apply and extend the dot position.
   *
   * @param i Start index of a dot chart item
   * @param k End index of a dot chart item; start index of a completed chart item
   * @param j End index of a completed chart item
   * @param skipUnary if true, don't extend unary rules
   */
  private void extendDotItemsWithProvedItems(int i, int k, int j, boolean skipUnary) {
    List<DotNode> dc = dotcells.get(i, k);
    if (dc == null || dc.isEmpty()) {
      return;
    }

    Cell cell = dotChart.getCell(k, j);
    if (cell == null || cell.getSortedSuperItems().isEmpty()) {
      return;
    }

    // complete super-items (items over the same span with different LHSs)
    List<SuperNode> superNodes =
        new ArrayList<>(cell.getSortedSuperItems().values());

    /* For every partially complete item over (i,k) */
    for (DotNode dotNode : dc) {
      Trie dot_trie = dotNode.getTrieNode();
      if (dot_trie.getChildren() == null) {
        continue;
      }
      List<Integer> nts;
      if (dot_trie instanceof MemoryBasedTrie) {
        nts = ((MemoryBasedTrie) dot_trie).getSortedNTs();
      } else {
        Set<Integer> children = dot_trie.getChildren().keySet();
        nts = new ArrayList<Integer>(children.size());
        for (int nt : children) {
          if (nt < 0) {
            nts.add(nt);
          }
        }
        Collections.sort(nts);
      }
      if (nts.isEmpty()) {
        continue;
      }
      /* For every completed nonterminal in the main chart */
      for (SuperNode superNode : superNodes) {
        if (Collections.binarySearch(nts, superNode.lhs) < 0) {
          continue;
        }

        // String arcWord = Vocabulary.word(superNode.lhs);
        // logger.info("DotChart.extendDotItemsWithProvedItems: " + arcWord);
        // Assert.assertTrue(arcWord.endsWith("]"));
        // Assert.assertTrue(arcWord.startsWith("["));

        /*
         * Regular Expression matching allows for a regular-expression style rules in the grammar,
         * which allows for a very primitive treatment of morphology. This is an advanced,
         * undocumented feature that introduces a complexity, in that the next "word" in the grammar
         * rule might match more than one outgoing arc in the grammar trie.
         */
        Trie child_node = dot_trie.match(superNode.lhs);
        if (child_node != null) {
          if ((!skipUnary) || (child_node.hasExtensions())) {
            addDotItem(child_node, i, j, dotNode.getAntSuperNodes(), superNode, dotNode
                .getSourcePath().extendNonTerminal());
          }
        }
      }
    }
  }

  /**
   * Creates a {@link DotNode} and adds it into the {@link DotChart} at the correct place. These
   * are (possibly incomplete) rule applications.
   *
   * @param tnode the trie node pointing to the location ("dot") in the grammar trie
   * @param i
   * @param j
   * @param antSuperNodesIn the supernodes representing the rule's tail nodes
   * @param curSuperNode the lefthand side of the rule being created
   * @param srcPath the path taken through the input lattice
   */
  private void addDotItem(Trie tnode, int i, int j, ArrayList<SuperNode> antSuperNodesIn,
      SuperNode curSuperNode, SourcePath srcPath) {
    ArrayList<SuperNode> antSuperNodes = new ArrayList<>();
    if (antSuperNodesIn != null) {
      antSuperNodes.addAll(antSuperNodesIn);
    }
    if (curSuperNode != null) {
      antSuperNodes.add(curSuperNode);
    }

    DotNode item = new DotNode(i, j, tnode, antSuperNodes, srcPath);
    ArrayList<DotNode> cell = dotcells.get(i, j);
    if (cell == null) {
      cell = new ArrayList<DotNode>();
      cell.add(item);
      dotcells.set(i, j, cell);
    } else {
      cell.add(item);
    }
    dotChart.nDotitemAdded++;
  }

  // ===============================================================
  // Package-protected classes
  // ===============================================================

  /**
   * A DotNode represents the partial application of a rule rooted to a particular span (i,j). It
   * maintains a pointer to the trie node in the grammar for efficient mapping.
   */
  static class DotNode {

    private final int i;
    private final int j;
    private Trie trieNode = null;

    /* A list of grounded (over a span) nonterminals that have been crossed in traversing the rule */
    private ArrayList<SuperNode> antSuperNodes = null;

    /* The source lattice cost of applying the rule */
    private final SourcePath srcPath;

    @Override
    public String toString() {
      int size = 0;
      if (trieNode != null && trieNode.getRuleCollection() != null)
        size = trieNode.getRuleCollection().getRules().size();
      return String.format("DOTNODE i=%d j=%d #rules=%d #tails=%d", i, j, size, antSuperNodes.size());
    }

    /**
     * Initialize a dot node with the span, grammar trie node, list of supernode tail pointers, and
     * the lattice sourcepath.
     *
     * @param i
     * @param j
     * @param trieNode
     * @param antSuperNodes
     * @param srcPath
     */
    public DotNode(int i, int j, Trie trieNode, ArrayList<SuperNode> antSuperNodes, SourcePath srcPath) {
      this.i = i;
      this.j = j;
      this.trieNode = trieNode;
      this.antSuperNodes = antSuperNodes;
      this.srcPath = srcPath;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == null)
        return false;
      if (!this.getClass().equals(obj.getClass()))
        return false;
      DotNode state = (DotNode) obj;

      /*
       * Technically, we should be comparing the span inforamtion as well, but that would require us
       * to store it, increasing memory requirements, and we should be able to guarantee that we
       * won't be comparing DotNodes across spans.
       */
      // if (this.i != state.i || this.j != state.j)
      // return false;

      return this.trieNode == state.trieNode;

    }

    /**
     * Technically the hash should include the span (i,j), but since DotNodes are grouped by span,
     * this isn't necessary, and we gain something by not having to store the span.
     */
    @Override
    public int hashCode() {
      return this.trieNode.hashCode();
    }

    // convenience function
    public boolean hasRules() {
      return getTrieNode().getRuleCollection() != null && getTrieNode().getRuleCollection().getRules().size() != 0;
    }

    public RuleCollection getRuleCollection() {
      return getTrieNode().getRuleCollection();
    }

    public Trie getTrieNode() {
      return trieNode;
    }

    public SourcePath getSourcePath() {
      return srcPath;
    }

    public ArrayList<SuperNode> getAntSuperNodes() {
      return antSuperNodes;
    }

    public int begin() {
      return i;
    }

    public int end() {
      return j;
    }
  }
}
