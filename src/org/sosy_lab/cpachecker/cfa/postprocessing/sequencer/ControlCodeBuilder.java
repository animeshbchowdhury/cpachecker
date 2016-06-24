package org.sosy_lab.cpachecker.cfa.postprocessing.sequencer;

import java.math.BigInteger;
import java.util.List;

import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.CFACreationUtils;
import org.sosy_lab.cpachecker.cfa.MutableCFA;
import org.sosy_lab.cpachecker.cfa.ast.FileLocation;
import org.sosy_lab.cpachecker.cfa.ast.c.CArraySubscriptExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CBinaryExpression.BinaryOperator;
import org.sosy_lab.cpachecker.cfa.ast.c.CBinaryExpressionBuilder;
import org.sosy_lab.cpachecker.cfa.ast.c.CExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CExpressionAssignmentStatement;
import org.sosy_lab.cpachecker.cfa.ast.c.CFunctionCallExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CFunctionCallStatement;
import org.sosy_lab.cpachecker.cfa.ast.c.CFunctionDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.c.CIdExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CIntegerLiteralExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CParameterDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.c.CVariableDeclaration;
import org.sosy_lab.cpachecker.cfa.model.AssumeEdge;
import org.sosy_lab.cpachecker.cfa.model.BlankEdge;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFAEdgeType;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.cfa.model.ContextSwitchEdge;
import org.sosy_lab.cpachecker.cfa.model.ContextSwitchSummaryEdge;
import org.sosy_lab.cpachecker.cfa.model.FunctionEntryNode;
import org.sosy_lab.cpachecker.cfa.model.FunctionExitNode;
import org.sosy_lab.cpachecker.cfa.model.SummaryEdge;
import org.sosy_lab.cpachecker.cfa.model.ThreadScheduleEdge;
import org.sosy_lab.cpachecker.cfa.model.c.CAssumeEdge;
import org.sosy_lab.cpachecker.cfa.model.c.CFunctionEntryNode;
import org.sosy_lab.cpachecker.cfa.model.c.CStatementEdge;
import org.sosy_lab.cpachecker.cfa.postprocessing.sequencer.context.AThread;
import org.sosy_lab.cpachecker.cfa.postprocessing.sequencer.context.CThread;
import org.sosy_lab.cpachecker.cfa.postprocessing.sequencer.context.CThreadContainer;
import org.sosy_lab.cpachecker.cfa.postprocessing.sequencer.context.ContextSwitch;
import org.sosy_lab.cpachecker.cfa.postprocessing.sequencer.utils.CFAEdgeUtils;
import org.sosy_lab.cpachecker.cfa.postprocessing.sequencer.utils.CFAFunctionUtils;
import org.sosy_lab.cpachecker.cfa.postprocessing.sequencer.utils.CFASequenceBuilder;
import org.sosy_lab.cpachecker.cfa.postprocessing.sequencer.utils.ExpressionUtils;
import org.sosy_lab.cpachecker.cfa.types.c.CFunctionType;
import org.sosy_lab.cpachecker.cfa.types.c.CNumericTypes;
import org.sosy_lab.cpachecker.cfa.types.c.CPointerType;
import org.sosy_lab.cpachecker.cfa.types.c.CType;
import org.sosy_lab.cpachecker.cfa.types.c.CVoidType;
import org.sosy_lab.cpachecker.util.CFAUtils;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

public class ControlCodeBuilder {

  private LogManager logger;

  private MutableCFA cfa;

  private CThreadContainer threads;

  public static final String THREAD_SIMULATION_FUNCTION_NAME = "__schedulerSimulation";

  private final CBinaryExpressionBuilder BINARY_BUILDER;

  private ControlVariables controlVariables;

  private final int THREAD_COUNT;

  /**
   * The function entry node of the scheduler simulation function
   */
  private FunctionEntryNode schedulerSimulationFunctionEntry;

  /**
   *
   * @param cfa
   * @param threads - A container which wraps informations about the threads used in the original c code
   * @param logger
   */
  public ControlCodeBuilder(ControlVariables controlVariables, MutableCFA cfa, CThreadContainer threads, LogManager logger) {
    this.controlVariables = controlVariables;
    this.BINARY_BUILDER = new CBinaryExpressionBuilder(cfa.getMachineModel(), logger);
    this.THREAD_COUNT = threads.getThreadCount();
    this.cfa = cfa;
    this.threads = threads;
    this.logger = logger;
  }

  public void buildControlVariableDeclaration() {

    FunctionEntryNode mainFunctionEntry = cfa.getMainFunction();

    assert cfa.getMainFunction().getNumLeavingEdges() == 1;
    CFAEdge initGlobalVarsEdge = mainFunctionEntry.getLeavingEdge(0);

    assert initGlobalVarsEdge.getEdgeType().equals(CFAEdgeType.BlankEdge);
    assert CFAFunctionUtils.INIT_GLOBAL_VARS.equals(initGlobalVarsEdge
        .getDescription());

    CFANode startSequence = new CFANode(cfa.getMainFunction().getFunctionName());
    CFANode injectionPoint = initGlobalVarsEdge.getSuccessor();

    CFASequenceBuilder globalVarBuilder = new CFASequenceBuilder(startSequence,
        cfa);

    globalVarBuilder.addChainLink(controlVariables
        .getDummyThreadCreationArgumentsArrayDeclarationEdge());
    globalVarBuilder.addChainLink(controlVariables
        .getDummyIsThreadActiveArrayDeclarationEdge());
    globalVarBuilder.addChainLink(controlVariables
        .getDummyIsThreadFinishedDeclarationEdge());

    CFANode endSequence = globalVarBuilder.lockSequenceBuilder();

    CFAEdgeUtils.injectInBetween(injectionPoint, startSequence, endSequence,
        cfa);
  }

  /**
   * Builds the scheduler simulation function including the context switch edges in the whole cfa.
   * @return the function entry node of the scheduler simulation function
   */
  public FunctionEntryNode buildScheduleSimulationFunction() {

    schedulerSimulationFunctionEntry = buildSchedulerSimulationEntry(cfa);

    injectOutGoingContextSwitchEdges();


    CFASequenceBuilder sequenceBuilder = new CFASequenceBuilder(schedulerSimulationFunctionEntry, cfa);


    for(CThread thread : threads.getAllThreads()) {
      CFASequenceBuilder threadBuilder = appendThreadSchedule(sequenceBuilder, thread);

      CFASequenceBuilder isThreadActivePath = createThreadUseableAssume(threadBuilder, thread);

      if(threads.getMainThread().equals(thread)) {
        createThreadAttendant(isThreadActivePath, thread);
      } else {
        CFASequenceBuilder threadStartPath = isThreadActivePath.addMultipleEdge(getDummyThreadStartStatement(thread));

        CFAEdge edge = getDummyAssingFinishedStatement(thread);
        threadStartPath.addChainLink(edge, schedulerSimulationFunctionEntry);

        FunctionExitNode contextSwitchExit = new FunctionExitNode(THREAD_SIMULATION_FUNCTION_NAME);
        contextSwitchExit.setEntryNode(schedulerSimulationFunctionEntry);
        CFASequenceBuilder threadContextSwitchPath = isThreadActivePath.addMultipleEdge(new BlankEdge("", FileLocation.DUMMY, CFASequenceBuilder.DUMMY_NODE, CFASequenceBuilder.DUMMY_NODE, "context switch"), contextSwitchExit);

        createThreadAttendant(threadContextSwitchPath, thread);
      }
      isThreadActivePath.lockSequenceBuilder();
    }

    return schedulerSimulationFunctionEntry;
  }

  private static CFunctionEntryNode buildSchedulerSimulationEntry(MutableCFA cfa) {
    FunctionExitNode schedulerSimulationExit = new FunctionExitNode(THREAD_SIMULATION_FUNCTION_NAME);
    CFunctionType schedulerFunctionType = new CFunctionType(false, false, CVoidType.VOID, Lists.<CType>newArrayList(), false);
    CFunctionDeclaration schedulerFunctionDeclaration = new CFunctionDeclaration(FileLocation.DUMMY, schedulerFunctionType,
        THREAD_SIMULATION_FUNCTION_NAME, Lists.<CParameterDeclaration>newArrayList());
    CFunctionEntryNode schedulerSimulationFunctionEntry = new CFunctionEntryNode(FileLocation.DUMMY, schedulerFunctionDeclaration,
        schedulerSimulationExit, Lists.<String>newArrayList(), Optional.<CVariableDeclaration> absent());
    schedulerSimulationExit.setEntryNode(schedulerSimulationFunctionEntry);

    cfa.addFunction(schedulerSimulationFunctionEntry);

    return schedulerSimulationFunctionEntry;
  }

  /**
   * Appends edges to the builder which determines the current thread the thread
   * is running
   *
   * @param builder
   *          the builder where the threads will be appended
   * @param threads
   *          the threads which will be accessible with by thread delegation
   *          edges
   * @return a map of threads mapped to a builder which can be used to append
   *         {@link #CFAEdge}s to the current thread context
   */
  private CFASequenceBuilder appendThreadSchedule(CFASequenceBuilder builder, CThread thread) {

    CFAEdge dummyThreadEdge = new ThreadScheduleEdge(CFASequenceBuilder.DUMMY_NODE, CFASequenceBuilder.DUMMY_NODE, thread);
    return builder.addMultipleEdge(dummyThreadEdge);
  }

  private AssumeEdge getAnyThreadNotFinishedAssume() {
    CExpression threadIsNotFinished = getConstantSubscriptExpression(0, controlVariables.getIsThreadFinishedDeclaration(), CNumericTypes.BOOL);

    threadIsNotFinished = BINARY_BUILDER.buildBinaryExpressionUnchecked(threadIsNotFinished, CIntegerLiteralExpression.ONE, BinaryOperator.EQUALS);
    for(int i = 1; i <THREAD_COUNT; i++) {
      threadIsNotFinished = BINARY_BUILDER.buildBinaryExpressionUnchecked(threadIsNotFinished, getConstantSubscriptExpression(i, controlVariables.getIsThreadFinishedDeclaration(), CNumericTypes.BOOL), BinaryOperator.BINARY_AND);
      threadIsNotFinished = BINARY_BUILDER.buildBinaryExpressionUnchecked(threadIsNotFinished, CIntegerLiteralExpression.ONE, BinaryOperator.EQUALS);
    }
    return new CAssumeEdge(threadIsNotFinished.toString(), FileLocation.DUMMY, CFASequenceBuilder.DUMMY_NODE, CFASequenceBuilder.DUMMY_NODE, threadIsNotFinished, true);
  }


  private AssumeEdge getIsAnyThreadActive() {
    CExpression exp1 = getConstantSubscriptExpression(0, controlVariables.getIsThreadActiveArrayDeclaration(), CNumericTypes.BOOL);
    for(int i = 1; i <THREAD_COUNT; i++) {
      exp1 = BINARY_BUILDER.buildBinaryExpressionUnchecked(exp1, getConstantSubscriptExpression(i, controlVariables.getIsThreadActiveArrayDeclaration(), CNumericTypes.BOOL), BinaryOperator.BINARY_OR);
    }
    return new CAssumeEdge(exp1.toString(), FileLocation.DUMMY, CFASequenceBuilder.DUMMY_NODE, CFASequenceBuilder.DUMMY_NODE, exp1, true);
  }

  private static CExpression getConstantSubscriptExpression(int i, CVariableDeclaration dec, CType arrayType) {
    CArraySubscriptExpression subscript = new CArraySubscriptExpression(FileLocation.DUMMY, arrayType,
        new CIdExpression(FileLocation.DUMMY, dec), new CIntegerLiteralExpression(FileLocation.DUMMY, CNumericTypes.INT, BigInteger.valueOf(i)));

    return subscript;
  }

  private CFASequenceBuilder createThreadUseableAssume(CFASequenceBuilder schedulerBuilder, CThread thread) {
    CFANode infeasableLoc = schedulerSimulationFunctionEntry.getExitNode();
    int threadNumber = thread.getThreadNumber();
    CExpression isThreadActiveExpression = createThreadActiveAssumeExpression(threadNumber);

    // Add thread finished expression. This helps the value analysis to prune
    // away infeasible context switch and don't stuck at a infinite program
    // counter iteration
    CExpression threadNotFinishedExpression = createThreadNotFinishedAssumeExpression(thread);

    AssumeEdge assumeEdge = new CAssumeEdge("", FileLocation.DUMMY, CFASequenceBuilder.DUMMY_NODE, CFASequenceBuilder.DUMMY_NODE, isThreadActiveExpression, true);
    AssumeEdge threadNotFinishedAssume = new CAssumeEdge("", FileLocation.DUMMY, CFASequenceBuilder.DUMMY_NODE, CFASequenceBuilder.DUMMY_NODE, threadNotFinishedExpression, true);

    CFANode newCFANode = new CFANode(schedulerBuilder.getFunctionName());
    CFANode threadNotFinishedNode = new CFANode(schedulerBuilder.getFunctionName());

    CFASequenceBuilder isNotFinishedBranch = schedulerBuilder.addAssumeEdge(threadNotFinishedAssume, threadNotFinishedNode, infeasableLoc);
    CFASequenceBuilder isActiveBranch = isNotFinishedBranch.addAssumeEdge(assumeEdge, newCFANode, infeasableLoc);

    isNotFinishedBranch.lockSequenceBuilder();

    return isActiveBranch;
  }

  private CExpression createThreadActiveAssumeExpression(int threadNumber) {
      // e.g. for threadNumber=1  ->  isThreadActive[1]
      CArraySubscriptExpression isThreadWithNumberActiveExpression = new CArraySubscriptExpression(FileLocation.DUMMY, CNumericTypes.BOOL,
          new CIdExpression(FileLocation.DUMMY, controlVariables.getIsThreadActiveArrayDeclaration()), new CIntegerLiteralExpression(FileLocation.DUMMY, CNumericTypes.INT, BigInteger.valueOf(threadNumber)));
      CExpression isActiveExpression = BINARY_BUILDER.buildBinaryExpressionUnchecked(isThreadWithNumberActiveExpression, CIntegerLiteralExpression.ONE, BinaryOperator.EQUALS);

      return isActiveExpression;
    }

  private CExpression createThreadNotFinishedAssumeExpression(AThread thread) {
    CExpression isThreadWithNumber = getConstantSubscriptExpression(
        thread.getThreadNumber(), controlVariables.getIsThreadFinishedDeclaration(), CNumericTypes.BOOL);

    CExpression notFinished = BINARY_BUILDER.buildBinaryExpressionUnchecked(
        isThreadWithNumber, new CIntegerLiteralExpression(FileLocation.DUMMY,
            CNumericTypes.BOOL, BigInteger.valueOf(0)), BinaryOperator.EQUALS);

    return notFinished;
  }

 /**
  * This edge displays the return to the function head at next
  * context-switch. Besides no return edge is needed because all "function
  * calls" will behave like one single function call. Actually the function
  * will never be called by an functionCallEdge but an context-switch edge
  */
  private void createThreadAttendant(
      CFASequenceBuilder nextProgramLocation, CThread targetThread) {

    for (ContextSwitch contextSwitch : targetThread.getContextSwitchPoints()) {
      assert contextSwitch.getContextSwitchReturnNode() != null;

      CFAEdge contextSwitchEdge = new ContextSwitchEdge(contextSwitch, "",
          FileLocation.DUMMY, CFASequenceBuilder.DUMMY_NODE,
          CFASequenceBuilder.DUMMY_NODE, false);

      nextProgramLocation.addMultipleEdge(contextSwitchEdge,
          contextSwitch.getContextSwitchReturnNode());
    }

  }

  /**
   * A special function call statement
   */
  private CStatementEdge getDummyThreadStartStatement(CThread targetThread) {
    List<CParameterDeclaration> param = targetThread.getThreadFunction().getFunctionDefinition().getParameters();
    List<CExpression> parameter;

    // the thread start function can have either one or none parameters
    if(param.size() == 0) {
      parameter = ImmutableList.of();
    } else {
      assert param.size() == 1;
      CArraySubscriptExpression threadCreationArgument = new CArraySubscriptExpression(FileLocation.DUMMY, new CPointerType(false, false, CVoidType.VOID), new CIdExpression(FileLocation.DUMMY, controlVariables.getThreadCreationArgumentsArrayDeclaration()), ExpressionUtils.getThreadNumberNumberExpression(targetThread));
      parameter = ImmutableList.<CExpression>of(threadCreationArgument);
    }
    return threadCreationStatementEdge(targetThread, parameter);
  }

  private CFAEdge getDummyAssingFinishedStatement(CThread thread) {
    return getThreadsBooleanStateStatementEdge(thread, controlVariables.getIsThreadFinishedDeclaration(), true);
  }

  private CFAEdge getThreadsBooleanStateStatementEdge(CThread thread, CVariableDeclaration variableDeclaraion, boolean value) {
    CIdExpression isThreadFinishedArray = new CIdExpression(FileLocation.DUMMY,
        variableDeclaraion);
    CIntegerLiteralExpression currentThreadNumber = ExpressionUtils.getThreadNumberNumberExpression(thread);
    CArraySubscriptExpression threadFinishedIdentificator = new CArraySubscriptExpression(
        FileLocation.DUMMY, CNumericTypes.BOOL, isThreadFinishedArray,
        currentThreadNumber);

    CExpressionAssignmentStatement assignement = new CExpressionAssignmentStatement(
        FileLocation.DUMMY, threadFinishedIdentificator,
        new CIntegerLiteralExpression(FileLocation.DUMMY, CNumericTypes.BOOL,
            BigInteger.valueOf(1)));

    return new CStatementEdge(assignement.toString(), assignement,
        FileLocation.DUMMY, CFASequenceBuilder.DUMMY_NODE,
        CFASequenceBuilder.DUMMY_NODE);
  }



  private CFAEdge getThreadToCurrentThreadStatement(CThread currentThread) {
    int threadNumber = currentThread.getThreadNumber();
    CIdExpression currentThreadNumber = new CIdExpression(FileLocation.DUMMY, controlVariables.getCurrentThreadDeclaration());

    CExpressionAssignmentStatement assignement = new CExpressionAssignmentStatement(FileLocation.DUMMY, currentThreadNumber, ExpressionUtils.getThreadNumberNumberExpression(currentThread));
    return new CStatementEdge("currentThread = " + threadNumber, assignement, FileLocation.DUMMY, CFASequenceBuilder.DUMMY_NODE, CFASequenceBuilder.DUMMY_NODE);
  }


  public void injectOutGoingContextSwitchEdges() {
    assert SequencePreparator.checkContextSwitchConsistency(threads.getAllThreads());

    for (CThread thread : threads.getAllThreads()) {
      for (ContextSwitch contextSwitch : thread.getContextSwitchPoints()) {
        CFANode contextSwitchPosition = contextSwitch.getContextSwitchReturnNode();

        // across the new node the context switch will be "done"
        CFANode newNode = new CFANode(contextSwitchPosition.getFunctionName());
        cfa.addNode(newNode);

        // entering edges which could cause a context switch will point to
        // the new created node
        for(CFAEdge edge: contextSwitch.getContextStatementCause()) {
          CFAEdgeUtils.bypassCEdgeNodes(edge, edge.getPredecessor(), newNode);
        }

        SummaryEdge summaryEdge = new ContextSwitchSummaryEdge("", FileLocation.DUMMY, newNode, contextSwitchPosition, contextSwitch);
        summaryEdge.getPredecessor().addLeavingSummaryEdge(summaryEdge);
        summaryEdge.getSuccessor().addEnteringSummaryEdge(summaryEdge);

        ContextSwitchEdge contextSwitchEdge = new ContextSwitchEdge(contextSwitch, "", FileLocation.DUMMY, newNode,
            schedulerSimulationFunctionEntry, true);
        CFACreationUtils.addEdgeUnconditionallyToCFA(contextSwitchEdge);



        assert summaryEdge.getSuccessor() != null;
        assert summaryEdge.getPredecessor() != null;
        assert CFAUtils.leavingEdges(summaryEdge.getPredecessor()).size() == 1;
        assert CFAUtils.leavingEdges(summaryEdge.getPredecessor()).get(0) instanceof ContextSwitchEdge;
      }
    }

    assert isContextSwitchConsistency();

  }

  private boolean isContextSwitchConsistency() {
    for(CThread thread : threads.getAllThreads()) {
      for (ContextSwitch contextSwitch : thread.getContextSwitchPoints()) {
        assert CFAEdgeUtils.isEdgeForbiddenEdge(contextSwitch
            .getContextStatementCause()) : "The edge "
            + contextSwitch.getContextStatementCause()
            + " was found in contextswitch which was replaced by the cfa building tools!";
        return true;
      }
    }
    return true;
  }

  public AssumeEdge createIsCurrentThreadAssume(CThread thread) {
    CExpression currentThreadValue = new CIdExpression(FileLocation.DUMMY, controlVariables.getCurrentThreadDeclaration());
    CExpression IthreadNumber = ExpressionUtils.getThreadNumberNumberExpression(thread);
    CExpression isThisCurrentThreadAssume = null;
    isThisCurrentThreadAssume = BINARY_BUILDER.buildBinaryExpressionUnchecked(currentThreadValue, IthreadNumber, BinaryOperator.EQUALS);

    return new CAssumeEdge("", FileLocation.DUMMY, CFASequenceBuilder.DUMMY_NODE, CFASequenceBuilder.DUMMY_NODE, isThisCurrentThreadAssume, true);
  }


  private CFAEdge getThreadActiveExpression(CThread thread) {
    return getThreadsBooleanStateStatementEdge(thread, controlVariables.getIsThreadActiveArrayDeclaration(), true);
  }

  public CStatementEdge threadCreationStatementEdge(CThread thread, List<CExpression> param) {
    assert thread.getThreadCreationStatement().isPresent();

    CFunctionEntryNode threadEntryNode = thread.getThreadFunction();
    CFunctionDeclaration threadDeclaration = threadEntryNode.getFunctionDefinition();

    assert threadDeclaration.getParameters().size() == param.size();

    CFunctionCallExpression functionCallExpression = new CFunctionCallExpression(FileLocation.DUMMY, threadDeclaration.getType(),
        new CIdExpression(FileLocation.DUMMY, threadDeclaration), param, threadDeclaration);

    CFunctionCallStatement threadStartExpression = new CFunctionCallStatement(FileLocation.DUMMY, functionCallExpression);

    return new CStatementEdge("", threadStartExpression, FileLocation.DUMMY, CFASequenceBuilder.DUMMY_NODE, CFASequenceBuilder.DUMMY_NODE);
  }

  public CExpression getCurrentThreadExpression() {
    return new CIdExpression(FileLocation.DUMMY, controlVariables.getCurrentThreadDeclaration());
  }



}