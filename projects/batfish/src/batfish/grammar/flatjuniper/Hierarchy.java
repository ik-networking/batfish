package batfish.grammar.flatjuniper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.antlr.v4.runtime.tree.TerminalNodeImpl;

import batfish.grammar.flatjuniper.FlatJuniperGrammarParser.Flat_juniper_configurationContext;
import batfish.grammar.flatjuniper.FlatJuniperGrammarParser.Set_lineContext;
import batfish.grammar.flatjuniper.FlatJuniperGrammarParser.Set_line_tailContext;
import batfish.grammar.flatjuniper.FlatJuniperGrammarParser.StatementContext;
import batfish.grammar.flatjuniper.Hierarchy.HierarchyTree.HierarchyPath;
import batfish.main.BatfishException;
import batfish.main.PedanticBatfishException;

public class Hierarchy {

   public static class HierarchyTree {

      private enum AddPathResult {
         BLACKLISTED,
         MODIFIED,
         UNMODIFIED
      }

      private static abstract class HierarchyChildNode extends HierarchyNode {

         private Set_lineContext _line;
         protected String _sourceGroup;
         protected String _text;
         public List<String> _sourceWildcards;

         private HierarchyChildNode(String text) {
            _text = text;
         }

         public abstract HierarchyChildNode copy();

         public abstract boolean isMatchedBy(HierarchyLiteralNode node);

         public abstract boolean isMatchedBy(HierarchyWildcardNode node);

         public abstract boolean matches(HierarchyChildNode node);

      }

      private static final class HierarchyLiteralNode extends
            HierarchyChildNode {

         private HierarchyLiteralNode(String text) {
            super(text);
         }

         @Override
         public HierarchyChildNode copy() {
            return new HierarchyLiteralNode(_text);
         }

         public boolean isMatchedBy(HierarchyLiteralNode node) {
            return _text.equals(node._text);
         }

         @Override
         public boolean isMatchedBy(HierarchyWildcardNode node) {
            String regex = node._wildcard.replaceAll("\\*", ".*");
            return _text.matches(regex);
         }

         @Override
         public boolean matches(HierarchyChildNode node) {
            return node.isMatchedBy(this);
         }

         @Override
         public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Literal(" + _text);
            if (_sourceGroup != null) {
               sb.append(", srcGroup:\"" + _sourceGroup + "\"");
            }
            if (_sourceWildcards != null) {
               sb.append(", srcWildcard:\"" + _sourceWildcards + "\"");
            }
            sb.append(")");
            return sb.toString();
         }

      }

      private static abstract class HierarchyNode {

         protected Set<String> _blacklistedGroups;
         private Map<String, HierarchyChildNode> _children;

         public HierarchyNode() {
            _children = new LinkedHashMap<String, HierarchyChildNode>();
            _blacklistedGroups = new HashSet<String>();
         }

         public void addBlacklistedGroup(String groupName) {
            _blacklistedGroups.add(groupName);
         }

         public void addChildNode(HierarchyChildNode node) {
            _children.put(node._text, node);
         }

         public HierarchyChildNode getChildNode(String text) {
            return _children.get(text);
         }

         public Map<String, HierarchyChildNode> getChildren() {
            return _children;
         }

         public HierarchyChildNode getFirstMatchingChildNode(
               HierarchyChildNode node) {
            for (HierarchyChildNode child : _children.values()) {
               if (child.matches(node)) {
                  return child;
               }
            }
            return null;
         }

         public boolean isWildcard() {
            return false;
         }

      }

      public static final class HierarchyPath {

         private boolean _containsWildcard;
         private List<HierarchyChildNode> _nodes;
         private StatementContext _statement;

         public HierarchyPath() {
            _nodes = new ArrayList<HierarchyChildNode>();
         }

         public void addNode(String text) {
            HierarchyChildNode newNode = new HierarchyLiteralNode(text);
            _nodes.add(newNode);
         }

         public void addWildcardNode(String text) {
            _containsWildcard = true;
            HierarchyChildNode newNode = new HierarchyWildcardNode(text);
            _nodes.add(newNode);
         }

         public boolean containsWildcard() {
            return _containsWildcard;
         }

         public void setStatement(StatementContext statement) {
            _statement = statement;
         }

         @Override
         public String toString() {
            return "Path(Statement:" + _statement + "," + _nodes + ")";
         }

      }

      private static final class HierarchyRootNode extends HierarchyNode {
      }

      private static final class HierarchyWildcardNode extends
            HierarchyChildNode {

         private String _wildcard;

         private HierarchyWildcardNode(String text) {
            super(text);
            if (text.charAt(0) != '<' || text.charAt(text.length() - 1) != '>') {
               throw new BatfishException("Improperly-formatted wildcard");
            }
            _wildcard = text.substring(1, text.length() - 1);
         }

         @Override
         public boolean isWildcard() {
            return true;
         }

         @Override
         public HierarchyChildNode copy() {
            return new HierarchyWildcardNode(_text);
         }

         @Override
         public boolean isMatchedBy(HierarchyLiteralNode node) {
            return false;
         }

         @Override
         public boolean isMatchedBy(HierarchyWildcardNode node) {
            // TODO: check whether this is the only way to match two wildcards
            return _text.equals(node._text);
         }

         @Override
         public boolean matches(HierarchyChildNode node) {
            return node.isMatchedBy(this);
         }

         @Override
         public String toString() {
            return "Wildcard(" + _text + ")";
         }

      }

      private String _groupName;
      private HierarchyRootNode _root;

      private HierarchyTree(String groupName) {
         _groupName = groupName;
         _root = new HierarchyRootNode();
      }

      private void addGroupPaths(HierarchyChildNode currentGroupNode,
            HierarchyTree masterTree, HierarchyPath path,
            List<ParseTree> lines,
            Flat_juniper_configurationContext configurationContext) {
         Set_lineContext groupLine = currentGroupNode._line;
         if (groupLine != null) {
            Set_lineContext setLine = new Set_lineContext(configurationContext,
                  -1);
            if (masterTree.addPath(path, setLine, _groupName) == AddPathResult.BLACKLISTED) {
               return;
            }
            StringBuilder sb = new StringBuilder();
            for (HierarchyChildNode pathNode : path._nodes) {
               sb.append(pathNode._text + " ");
            }
            String newStatementText = sb.toString();
            TerminalNode set = new TerminalNodeImpl(new CommonToken(
                  FlatJuniperGrammarLexer.SET, "set"));
            Set_line_tailContext setLineTail = new Set_line_tailContext(
                  setLine, -1);
            TerminalNode newline = new TerminalNodeImpl(new CommonToken(
                  FlatJuniperGrammarLexer.NEWLINE, "\n"));
            setLine.children = new ArrayList<ParseTree>();
            setLine.children.add(set);
            setLine.children.add(setLineTail);
            setLine.children.add(newline);

            FlatJuniperGrammarCombinedParser parser = new FlatJuniperGrammarCombinedParser(
                  newStatementText, true, true);
            StatementContext newStatement = parser.getParser().statement();
            newStatement.parent = setLineTail;

            setLineTail.children = new ArrayList<ParseTree>();
            setLineTail.children.add(newStatement);
            lines.add(setLine);
         }
         for (HierarchyChildNode childNode : currentGroupNode.getChildren()
               .values()) {
            HierarchyChildNode newPathNode = childNode.copy();
            path._nodes.add(newPathNode);
            addGroupPaths(childNode, masterTree, path, lines,
                  configurationContext);
            path._nodes.remove(path._nodes.size() - 1);
         }
      }

      public AddPathResult addPath(HierarchyPath path, Set_lineContext ctx,
            String group) {
         AddPathResult result = AddPathResult.UNMODIFIED;
         HierarchyNode currentNode = _root;
         HierarchyChildNode matchNode = null;
         for (HierarchyChildNode currentPathNode : path._nodes) {
            matchNode = currentNode.getChildNode(currentPathNode._text);
            if (matchNode == null) {
               result = AddPathResult.MODIFIED;
               matchNode = currentPathNode.copy();
               currentNode.addChildNode(matchNode);
            }
            if (matchNode._blacklistedGroups.contains(group)) {
               return AddPathResult.BLACKLISTED;
            }
            currentNode = matchNode;
         }
         matchNode._line = ctx;
         matchNode._sourceGroup = group;
         return result;
      }

      public List<ParseTree> applyWildcardPath(HierarchyPath path, Set_lineContext ctx) {
         HierarchyChildNode wildcardNode = findExactPathMatchNode(path);
         String sourceGroup = wildcardNode._sourceGroup;
         int remainingWildcards = 0;
         for (HierarchyChildNode node : path._nodes) {
            if (node.isWildcard()) {
               remainingWildcards++;
            }
         }
         List<String> appliedWildcards = new ArrayList<String>();
         HierarchyPath newPath = new HierarchyPath();
         List<ParseTree> lines = new ArrayList<ParseTree>();
         applyWildcardPath(path, ctx, sourceGroup, _root, 0,
               remainingWildcards, appliedWildcards, newPath, lines);
         return lines;
      }

      private void applyWildcardPath(HierarchyPath path, Set_lineContext ctx,
            String sourceGroup, HierarchyNode destinationTreeRoot,
            int startingIndex, int remainingWildcards,
            List<String> appliedWildcards, HierarchyPath newPath, List<ParseTree> lines) {
         if (destinationTreeRoot._blacklistedGroups.contains(sourceGroup)) {
            return;
         }
         HierarchyChildNode currentPathNode = path._nodes.get(startingIndex);
         if (!currentPathNode.isWildcard()) {
            String currentPathNodeText = currentPathNode._text;
            HierarchyChildNode newDestinationTreeRoot = destinationTreeRoot
                  .getChildNode(currentPathNodeText);
            if (newDestinationTreeRoot == null) {
               // If literal node does not exist, but there are still more
               // wildcards to match, we abort.
               // Else, we create node and continue recursing
               if (remainingWildcards > 0) {
                  return;
               }
               newDestinationTreeRoot = currentPathNode.copy();
               destinationTreeRoot._children.put(newDestinationTreeRoot._text,
                     newDestinationTreeRoot);
            }
            newPath._nodes.add(newDestinationTreeRoot);
            if (startingIndex == path._nodes.size() - 1) {
               newDestinationTreeRoot._sourceWildcards = new ArrayList<String>();
               newDestinationTreeRoot._sourceWildcards.addAll(appliedWildcards);
               newDestinationTreeRoot._line = generateSetLine(newPath, ctx);
               lines.add(newDestinationTreeRoot._line);
            }
            else {
               applyWildcardPath(path, ctx, sourceGroup,
                     newDestinationTreeRoot, startingIndex + 1,
                     remainingWildcards, appliedWildcards, newPath, lines);
            }
            newPath._nodes.remove(newPath._nodes.size() - 1);
         }
         else {
            appliedWildcards.add(currentPathNode._text);
            for (HierarchyChildNode destinationTreeNode : destinationTreeRoot._children
                  .values()) {
               // if there are no matching children, then we recurse no further
               if (!destinationTreeNode.isWildcard()
                     && currentPathNode.matches(destinationTreeNode)) {
                  newPath._nodes.add(destinationTreeNode);
                  applyWildcardPath(path, ctx, sourceGroup,
                        destinationTreeNode, startingIndex + 1,
                        remainingWildcards - 1, appliedWildcards, newPath, lines);
                  newPath._nodes.remove(newPath._nodes.size() - 1);
               }
            }
            appliedWildcards.remove(appliedWildcards.size() - 1);
         }
      }

      private Set_lineContext generateSetLine(HierarchyPath path,
            Set_lineContext generatingLine) {
         Flat_juniper_configurationContext configurationContext = (Flat_juniper_configurationContext) (generatingLine
               .getParent());
         Set_lineContext setLine = new Set_lineContext(configurationContext, -1);
         StringBuilder sb = new StringBuilder();
         for (HierarchyChildNode pathNode : path._nodes) {
            sb.append(pathNode._text + " ");
         }
         String newStatementText = sb.toString();
         TerminalNode set = new TerminalNodeImpl(new CommonToken(
               FlatJuniperGrammarLexer.SET, "set"));
         Set_line_tailContext setLineTail = new Set_line_tailContext(setLine,
               -1);
         TerminalNode newline = new TerminalNodeImpl(new CommonToken(
               FlatJuniperGrammarLexer.NEWLINE, "\n"));
         setLine.children = new ArrayList<ParseTree>();
         setLine.children.add(set);
         setLine.children.add(setLineTail);
         setLine.children.add(newline);

         FlatJuniperGrammarCombinedParser parser = new FlatJuniperGrammarCombinedParser(
               newStatementText, true, true);
         StatementContext newStatement = parser.getParser().statement();
         newStatement.parent = setLineTail;

         setLineTail.children = new ArrayList<ParseTree>();
         setLineTail.children.add(newStatement);

         return setLine;
      }

      private HierarchyChildNode findExactPathMatchNode(HierarchyPath path) {
         HierarchyNode currentGroupNode = _root;
         HierarchyChildNode matchNode = null;
         for (HierarchyChildNode currentPathNode : path._nodes) {
            matchNode = currentGroupNode.getChildNode(currentPathNode._text);
            currentGroupNode = matchNode;
         }
         return matchNode;
      }

      public List<ParseTree> getApplyGroupsLines(HierarchyPath path,
            Flat_juniper_configurationContext configurationContext,
            HierarchyTree masterTree) {
         List<ParseTree> lines = new ArrayList<ParseTree>();
         HierarchyNode currentGroupNode = _root;
         HierarchyChildNode matchNode = null;
         for (HierarchyChildNode currentPathNode : path._nodes) {
            matchNode = currentGroupNode
                  .getFirstMatchingChildNode(currentPathNode);
            if (matchNode == null) {
               throw new PedanticBatfishException(
                     "Apply-groups invocation without matching path");
            }
            currentGroupNode = matchNode;
         }

         // at this point, matchNode is the node in the group tree whose
         // children must be added to the main tree with substitutions applied
         // according to the supplied path
         addGroupPaths(matchNode, masterTree, path, lines, configurationContext);
         return lines;
      }

      public String getGroupName() {
         return _groupName;
      }

      public void setApplyGroupsExcept(HierarchyPath path, String groupName) {
         HierarchyChildNode node = findExactPathMatchNode(path);
         node.addBlacklistedGroup(groupName);
      }

   }

   private HierarchyTree _masterTree;

   private Map<String, HierarchyTree> _trees;

   public Hierarchy() {
      _trees = new HashMap<String, HierarchyTree>();
      _masterTree = new HierarchyTree(null);
   }

   public void addMasterPath(HierarchyPath path, Set_lineContext ctx) {
      _masterTree.addPath(path, ctx, null);
   }

   public List<ParseTree> getApplyGroupsLines(String groupName,
         HierarchyPath path,
         Flat_juniper_configurationContext configurationContext) {
      HierarchyTree tree = _trees.get(groupName);
      return tree.getApplyGroupsLines(path, configurationContext, _masterTree);
   }

   public HierarchyTree getMasterTree() {
      return _masterTree;
   }

   public HierarchyTree getTree(String groupName) {
      return _trees.get(groupName);
   }

   public HierarchyTree newTree(String groupName) {
      HierarchyTree newTree = new HierarchyTree(groupName);
      _trees.put(groupName, newTree);
      return newTree;
   }

   public void setApplyGroupsExcept(HierarchyPath path, String groupName) {
      _masterTree.setApplyGroupsExcept(path, groupName);

   }

}
