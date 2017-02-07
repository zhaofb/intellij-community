/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.plugins.groovy.lang.psi.controlFlow;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.util.ArrayUtil;
import com.intellij.util.ArrayUtilRt;
import com.intellij.util.containers.IntArrayList;
import com.intellij.util.containers.IntStack;
import com.intellij.util.containers.ObjectIntHashMap;
import gnu.trove.TObjectIntHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.groovy.lang.lexer.GroovyTokenTypes;
import org.jetbrains.plugins.groovy.lang.psi.GroovyFile;
import org.jetbrains.plugins.groovy.lang.psi.GroovyFileBase;
import org.jetbrains.plugins.groovy.lang.psi.api.GroovyResolveResult;
import org.jetbrains.plugins.groovy.lang.psi.api.formatter.GrControlStatement;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.*;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrClosableBlock;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrOpenBlock;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.branch.GrBreakStatement;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.clauses.GrCaseSection;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.*;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrMethod;
import org.jetbrains.plugins.groovy.lang.psi.api.toplevel.imports.GrImportStatement;
import org.jetbrains.plugins.groovy.lang.psi.dataFlow.DFAEngine;
import org.jetbrains.plugins.groovy.lang.psi.dataFlow.readWrite.ReadBeforeWriteInstance;
import org.jetbrains.plugins.groovy.lang.psi.dataFlow.readWrite.ReadBeforeWriteSemilattice;
import org.jetbrains.plugins.groovy.lang.psi.dataFlow.readWrite.ReadBeforeWriteState;
import org.jetbrains.plugins.groovy.lang.resolve.processors.PropertyResolverProcessor;
import org.jetbrains.plugins.groovy.lang.resolve.processors.ResolverProcessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import static org.jetbrains.plugins.groovy.lang.psi.util.PsiTreeUtilKt.treeWalkUp;

/**
 * @author ven
 */
public class ControlFlowBuilderUtil {
  private static final Logger LOG = Logger.getInstance("org.jetbrains.plugins.groovy.lang.psi.controlFlow.ControlFlowBuilderUtil");

  private ControlFlowBuilderUtil() {
  }

  /**
   * @return array of instruction numbers in topological order
   */
  @NotNull
  public static int[] reversePostorder(@NotNull Instruction[] flow) {
    return ArrayUtil.reverseArray(postorder(flow));
  }

  @NotNull
  public static int[] postorder(@NotNull Instruction[] flow) {
    final int N = flow.length;
    final boolean[] visited = new boolean[N];
    Arrays.fill(visited, false);

    final IntArrayList result = new IntArrayList(N);
    final IntStack stack = new IntStack();
    for (int i = 0; i < N; i++) { // graph might have multiple entry points
      if (visited[i]) continue;
      stack.push(i);
      visited[i] = true;

      dfs:
      while (!stack.empty()) {
        int current = stack.peek();

        for (Instruction successorInst : flow[current].allSuccessors()) {
          int successor = successorInst.num();
          if (visited[successor]) continue;
          stack.push(successor);
          visited[successor] = true;  // discover successor
          continue dfs;               // if new successor is discovered go discover its successors
        }

        result.add(current); // mark black if all successors are discovered, i.e. previous for-cycle was not interrupted
        stack.pop();
      }
    }

    LOG.assertTrue(result.size() == N);
    return result.toArray();
  }

  public static ReadWriteVariableInstruction[] getReadsWithoutPriorWrites(Instruction[] flow, boolean onlyFirstRead) {
    DFAEngine<ReadBeforeWriteState> engine = new DFAEngine<>(
      flow,
      new ReadBeforeWriteInstance(buildNamesIndex(flow), onlyFirstRead),
      ReadBeforeWriteSemilattice.INSTANCE
    );
    List<ReadBeforeWriteState> dfaResult = engine.performDFAWithTimeout();
    if (dfaResult == null) {
      return ReadWriteVariableInstruction.EMPTY_ARRAY;
    }
    List<ReadWriteVariableInstruction> result = new ArrayList<>();
    BitSet reads = dfaResult.get(dfaResult.size() - 1).getReads();
    for (int i = reads.nextSetBit(0); i >= 0; i = reads.nextSetBit(i + 1)) {
      if (i == Integer.MAX_VALUE) break;
      result.add((ReadWriteVariableInstruction)flow[i]);
    }
    return result.toArray(ReadWriteVariableInstruction.EMPTY_ARRAY);
  }

  private static TObjectIntHashMap<String> buildNamesIndex(Instruction[] flow) {
    TObjectIntHashMap<String> namesIndex = new ObjectIntHashMap<>();
    int idx = 0;
    for (Instruction instruction : flow) {
      if (instruction instanceof ReadWriteVariableInstruction) {
        String name = ((ReadWriteVariableInstruction)instruction).getVariableName();
        if (!namesIndex.contains(name)) {
          namesIndex.put(name, idx++);
        }
      }
    }
    return namesIndex;
  }

  public static boolean isInstanceOfBinary(GrBinaryExpression binary) {
    if (binary.getOperationTokenType() == GroovyTokenTypes.kIN) {
      GrExpression left = binary.getLeftOperand();
      GrExpression right = binary.getRightOperand();
      if (left instanceof GrReferenceExpression && ((GrReferenceExpression)left).getQualifier() == null &&
          right instanceof GrReferenceExpression && findClassByText((GrReferenceExpression)right)) {
        return true;
      }
    }
    return false;
  }

  private static boolean findClassByText(GrReferenceExpression ref) {
    final String text = ref.getText();
    final int i = text.indexOf('<');
    String className = i == -1 ? text : text.substring(0, i);

    PsiClass[] names = PsiShortNamesCache.getInstance(ref.getProject()).getClassesByName(className, ref.getResolveScope());
    if (names.length > 0) return true;

    PsiFile file = ref.getContainingFile();
    if (file instanceof GroovyFile) {
      GrImportStatement[] imports = ((GroovyFile)file).getImportStatements();
      for (GrImportStatement anImport : imports) {
        if (className.equals(anImport.getImportedName())) return true;
      }
    }

    return false;
  }

  /**
   * check whether statement is return (the statement which provides return value) statement of method or closure.
   *
   * @param st
   * @return
   */
  public static boolean isCertainlyReturnStatement(GrStatement st) {
    final PsiElement parent = st.getParent();
    if (parent instanceof GrOpenBlock) {
      if (st != ArrayUtil.getLastElement(((GrOpenBlock)parent).getStatements())) return false;

      PsiElement pparent = parent.getParent();
      if (pparent instanceof GrMethod) {
        return true;
      }

      if (pparent instanceof GrBlockStatement || pparent instanceof GrCatchClause || pparent instanceof GrLabeledStatement) {
        pparent = pparent.getParent();
      }
      if (pparent instanceof GrIfStatement || pparent instanceof GrControlStatement || pparent instanceof GrTryCatchStatement) {
        return isCertainlyReturnStatement((GrStatement)pparent);
      }
    }

    else if (parent instanceof GrClosableBlock) {
      return st == ArrayUtil.getLastElement(((GrClosableBlock)parent).getStatements());
    }

    else if (parent instanceof GroovyFileBase) {
      return st == ArrayUtil.getLastElement(((GroovyFileBase)parent).getStatements());
    }

    else if (parent instanceof GrForStatement ||
             parent instanceof GrIfStatement && st != ((GrIfStatement)parent).getCondition() ||
             parent instanceof GrSynchronizedStatement && st != ((GrSynchronizedStatement)parent).getMonitor() ||
             parent instanceof GrWhileStatement && st != ((GrWhileStatement)parent).getCondition() ||
             parent instanceof GrConditionalExpression && st != ((GrConditionalExpression)parent).getCondition() ||
             parent instanceof GrElvisExpression) {
      return isCertainlyReturnStatement((GrStatement)parent);
    }

    else if (parent instanceof GrCaseSection) {
      final GrStatement[] statements = ((GrCaseSection)parent).getStatements();
      final GrStatement last = ArrayUtil.getLastElement(statements);
      final GrSwitchStatement switchStatement = (GrSwitchStatement)parent.getParent();

      if (last instanceof GrBreakStatement && statements.length > 1 && statements[statements.length - 2] == st) {
        return isCertainlyReturnStatement(switchStatement);
      }
      else if (st == last) {
        if (st instanceof GrBreakStatement || isLastStatementInCaseSection((GrCaseSection)parent, switchStatement)) {
          return isCertainlyReturnStatement(switchStatement);
        }
      }
    }
    return false;
  }

  private static boolean isLastStatementInCaseSection(GrCaseSection caseSection, GrSwitchStatement switchStatement) {
    final GrCaseSection[] sections = switchStatement.getCaseSections();
    final int i = ArrayUtilRt.find(sections, caseSection);
    if (i == sections.length - 1) {
      return true;
    }

    for (int j = i + 1; j < sections.length; j++) {
      GrCaseSection section = sections[j];
      for (GrStatement statement : section.getStatements()) {
        if (!(statement instanceof GrBreakStatement)) {
          return false;
        }
      }
    }
    return true;
  }

  @NotNull
  public static GroovyResolveResult[] resolveNonQualifiedRefWithoutFlow(@NotNull GrReferenceExpression ref) {
    LOG.assertTrue(!ref.isQualified());

    final String referenceName = ref.getReferenceName();
    final ResolverProcessor processor = new PropertyResolverProcessor(referenceName, ref);

    treeWalkUp(ref, processor);
    final GroovyResolveResult[] candidates = processor.getCandidates();
    if (candidates.length != 0) {
      return candidates;
    }

    return GroovyResolveResult.EMPTY_ARRAY;
  }
}
