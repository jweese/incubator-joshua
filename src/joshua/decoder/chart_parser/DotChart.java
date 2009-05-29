/* This file is part of the Joshua Machine Translation System.
 * 
 * Joshua is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1
 * of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free
 * Software Foundation, Inc., 59 Temple Place, Suite 330, Boston,
 * MA 02111-1307 USA
 */
package joshua.decoder.chart_parser;

import joshua.decoder.ff.tm.Grammar;
import joshua.decoder.ff.tm.Trie;
import joshua.lattice.Arc;
import joshua.lattice.Lattice;
import joshua.lattice.Node;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class implements:
 * (1) seeding
 * (2) extend the dot by accessing the TM grammar, and create and
 *     remember DotItems
 * 
 * Note: the purpose of this class: (1) do CKY parsing in an efficient
 * way (i.e., identify the applicable rules fastly); (2) binarization
 * on the fly; (3) remember the partial application of rules
 *
 * @author Zhifei Li, <zhifei.work@gmail.com>
 * @version $LastChangedDate$
 */
class DotChart {
	
//===============================================================
// Package-protected instance fields
//===============================================================
	/** 
	 * Two-dimensional chart of cells. Some cells might be null.
	 */
	DotBin[][] l_dot_bins;
	
	
//===============================================================
// Private instance fields (maybe could be protected instead)
//===============================================================
	
	/**
	 * CKY+ style parse chart in which completed span entries
	 * are stored.
	 */
	private Chart p_chart;
	
	/**
	 * Translation grammar which contains the translation rules.
	 */
	private Grammar p_grammar;
	
	
	/** Length of input sentence. */
	private final int sent_len;
	
	/** Represents the input sentence being translated. */
	private final Lattice<Integer> input;
	
	
//===============================================================
// Static fields
//===============================================================
	
	private static final Logger logger = 
		Logger.getLogger(DotChart.class.getName());
	
	
//===============================================================
// Constructors
//===============================================================

	// TODO: Maybe this should be a non-static inner class of Chart. That would give us implicit access to all the arguments of this constructor. Though we would need to take an argument, i, to know which Chart.this.grammars[i] to use.
	
	/**
	 * Constructs a new dot chart from a specified input lattice,
	 * a translation grammar, and a parse chart.
	 * 
	 * @param input   A lattice which represents an input
	 *                sentence.
	 * @param grammar A translation grammar.
	 * @param chart   A CKY+ style chart in which completed
	 *                span entries are stored.
	 */
	public DotChart(Lattice<Integer> input, Grammar grammar, Chart chart) {
		this.p_chart    = chart;
		this.p_grammar  = grammar;
		this.input      = input;
		this.sent_len   = input.size();
		this.l_dot_bins = new DotBin[sent_len][sent_len+1];
	}
	
	
	/**
	 * Constructs a new dot chart from a specified input sentence,
	 * a translation grammar, and a parse chart.
	 * 
	 * @param input   An array of integers which represents an
	 *                input sentence.
	 * @param grammar A translation grammar.
	 * @param chart   A CKY+ style chart in which completed
	 *                span entries are stored.
	 */
/*
	public DotChart(int[] inputSentence, Grammar grammar, Chart chart) {
		
		if (logger.isLoggable(Level.FINEST)) logger.finest("Constructing DotChart from input sentence: " + Arrays.toString(inputSentence));
		
		this.p_chart    = chart;
		this.p_grammar  = grammar;
		this.sent_len   = inputSentence.length;
		this.l_dot_bins = new DotBin[sent_len][sent_len+1];
		
		Integer[] input = new Integer[inputSentence.length];
		for (int i = 0; i < inputSentence.length; i++) {
			input[i] = inputSentence[i];
		}
		
		this.input = new Lattice<Integer>(input);
	}
*/
	
	
//===============================================================
// Package-protected methods
//===============================================================
	
	/**
	 * add intial dot items: dot-items pointer to the root of
	 * the grammar trie.
	 */
	void seed() {
		for (int j = 0; j <= sent_len - 1; j++) {
			if (p_grammar.hasRuleForSpan(j, j, sent_len)) {
				if (null == p_grammar.getTrieRoot()) {
					throw new RuntimeException("trie root is null");
				}
				addDotItem(p_grammar.getTrieRoot(), j, j, null, null, 0.0f);
			}
		}
	}
	
	
	/**
	 * two kinds of symbols in the foreign side: (1) non-terminal
	 * (e.g., X or NP); (2) CN-side terminal therefore, two
	 * ways to extend the dot postion.
	 */
	void expand_cell(int i, int j) {
		//if (logger.isLoggable(Level.FINE)) logger.fine("Expanding dot cell ("+i+","+j+")");
		
		// (1) if the dot is just to the left of a non-terminal variable, 
		//     looking for theorems or axioms in the Chart that may apply and 
		//     extend the dot pos
		for (int k = i + 1; k < j; k++) { //varying middle point k
			extendDotItemsWithProvedItems(i,k,j,false);
		}
		
		// (2) the dot-item is looking for a CN-side terminal symbol: 
		//     so we just need a CN-side terminal symbol to advance the dot in
		//     seeding case: j=i+1, therefore, it will look for l_dot_bins[i][i]
		Node<Integer> node = input.getNode(j-1);
		for (Arc<Integer> arc : node.getOutgoingArcs()) {
			
			int last_word = arc.getLabel();
			// Tail and Head are backward! FIX names!
			int arc_len = arc.getTail().getNumber() - arc.getHead().getNumber();
			float cost = (float)arc.getCost();
			if (logger.isLoggable(Level.FINEST)) logger.finest("last_word="+last_word+ " for node " +node.getNumber());
			
			//int last_word=foreign_sent[j-1]; // input.getNode(j-1).getNumber(); //	
			
			if (null != l_dot_bins[i][j-1]) {
				//dotitem in dot_bins[i][k]: looking for an item in the right to the dot
				for (DotItem dt : l_dot_bins[i][j-1].l_dot_items) {
					if (null == dt.tnode) {
						// We'll get one anyways in the else branch
						// TODO: better debugging.
						throw new NullPointerException(
							"DotChart.expand_cell(" + i + "," + j + "): "
							+ "Null tnode for DotItem");
						
					} else {
						// match the terminal
						Trie child_tnode = dt.tnode.matchOne(last_word);
						if (null != child_tnode) {
							// we do not have an ant for the terminal
							addDotItem(child_tnode, i, j - 1 + arc_len, dt.l_ant_super_items, null, dt.lattice_cost + cost);
						}
					}
				} // end foreach DotItem
			}
		}
	}
	
	
	/**
	 * note: (i,j) is a non-terminal, this cannot be a cn-side
	 * terminal, which have been handled in case2 of
	 * dotchart.expand_cell add dotitems that start with the
	 * complete super-items in cell(i,j)
	 */
	void start_dotitems(int i, int j) {
		extendDotItemsWithProvedItems(i,i,j,true);
	}
	
	
//===============================================================
// Private methods
//===============================================================
	
	/**
	 * Attempt to combine an item in the dot chart
	 * with an item in the chart to create a new item
	 * in the dot chart.
	 * <p>
	 * In other words, this method looks 
	 * for (proved) theorems or axioms in the completed chart
	 * that may apply and extend the dot position.
	 * 
	 * @param i Start index of a dot chart item
	 * @param k End index of a dot chart item; 
	 *          start index of a completed chart item
	 * @param j End index of a completed chart item
	 * @param startDotItems 
	 */
	private void extendDotItemsWithProvedItems(
		int i, int k, int j,
		boolean startDotItems)
	{
		if (this.l_dot_bins[i][k] == null || this.p_chart.bins[k][j] == null) {
			return;
		}
		
		// complete super-items
		List<SuperItem> t_ArrayList = new ArrayList<SuperItem>(this.
				p_chart.bins[k][j].get_sorted_super_items().values());
		
		// dotitem in dot_bins[i][k]: looking for an item in the right to the dot
		for (DotItem dt : l_dot_bins[i][k].l_dot_items) {
			// see if it matches what the dotitem is looking for
			for (SuperItem s_t : t_ArrayList) {
				Trie child_tnode = dt.tnode.matchOne(s_t.lhs);
				if (null != child_tnode) {
					if (true == startDotItems && !child_tnode.hasExtensions()) {
						continue; //TODO
					}
					if (logger.isLoggable(Level.FINEST)) {
						logger.finest(String.format("Add a dotitem with superitem.lhs: %s", s_t.lhs));
					}
					addDotItem(child_tnode, i, j, dt.l_ant_super_items, s_t, dt.lattice_cost);
				}
			}
		}
	}
	
	
	/**
	 * Creates a dot item and adds it into the cell(i,j) of
	 * this dot chart.
	 * 
	 * @param tnode
	 * @param i
	 * @param j
	 * @param ant_s_items_in
	 * @param cur_s_item
	 */
	private void addDotItem(Trie tnode, int i, int j,
			ArrayList<SuperItem> ant_s_items_in, SuperItem cur_s_item,
			float lattice_cost)
	{
		ArrayList<SuperItem> ant_s_items = new ArrayList<SuperItem>();
		if (ant_s_items_in != null) {
			ant_s_items.addAll(ant_s_items_in);
		}
		if (cur_s_item != null) {
			ant_s_items.add(cur_s_item);
		}
		
		DotItem item = new DotItem(i, j, tnode, ant_s_items, lattice_cost);
		if (l_dot_bins[i][j] == null) {
			l_dot_bins[i][j] = new DotBin();
		}
		l_dot_bins[i][j].add_dot_item(item);
		p_chart.n_dotitem_added++;
		
		if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("Add a dotitem in cell (%d, %d), n_dotitem=%d", i, j, p_chart.n_dotitem_added));
	}
	
	
//===============================================================
// Package-protected classes
//===============================================================
	
	/**
	 * Bin is a cell in parsing terminology
	 */
	static class DotBin {
		
		// Package-protected fields
		ArrayList<DotItem> l_dot_items = new ArrayList<DotItem>();
		
		private void add_dot_item(DotItem dt) {
			/*if(l_dot_items==null)
				l_dot_items= new ArrayList<DotItem>();*/
			l_dot_items.add(dt);
		}
	}
	
	
	/**
	 * remember the dot position in which a rule has been applied
	 * so far, and remember the old complete items.
	 */
	static class DotItem {
		
		//=======================================================
		// Package-protected instance fields
		//=======================================================
		
		//int i, j; //start and end position in the chart
		Trie tnode = null; // dot_position, point to grammar trie node, this is the only place that the DotChart points to the grammar
		ArrayList<SuperItem> l_ant_super_items = null; //pointer to SuperItem in Chart
		float lattice_cost;
		
		
		public DotItem(int i_in, int j_in, Trie tnode_in, ArrayList<SuperItem> ant_super_items_in, float lattice_cost) {
			//i = i_in;
			//j = j_in;
			this.tnode = tnode_in;
			this.l_ant_super_items = ant_super_items_in;
			this.lattice_cost = lattice_cost;
		}
	}

}
