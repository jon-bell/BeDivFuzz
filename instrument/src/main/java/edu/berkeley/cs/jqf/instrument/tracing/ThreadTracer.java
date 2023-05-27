/*
 * Copyright (c) 2017-2018 The Regents of the University of California
 * Copyright (c) 2020-2021 Rohan Padhye
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * 1. Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package edu.berkeley.cs.jqf.instrument.tracing;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

import edu.berkeley.cs.jqf.instrument.InstrumentationException;
import edu.berkeley.cs.jqf.instrument.tracing.events.AllocEvent;
import edu.berkeley.cs.jqf.instrument.tracing.events.BranchEvent;
import edu.berkeley.cs.jqf.instrument.tracing.events.CallEvent;
import edu.berkeley.cs.jqf.instrument.tracing.events.ReadEvent;
import edu.berkeley.cs.jqf.instrument.tracing.events.ReturnEvent;
import edu.berkeley.cs.jqf.instrument.tracing.events.TraceEvent;
import janala.logger.inst.*;

/**
 * This class is responsible for tracing for an instruction stream
 * generated by a single thread in the application.
 *
 * <p>A ThreadTracer instance processes low-level bytecode instructions
 * instrumented by JQF/Janala and converts them into appropriate
 * {@link TraceEvent} instances, which are then emitted to be processed
 * by the guidance-provided callback.</p>
 *
 * @author Rohan Padhye
 */
public class ThreadTracer {
    protected final Thread tracee;
    protected final String entryPointClass;
    protected final String entryPointMethod;
    protected final Consumer<TraceEvent> callback;
    private final Deque<IVisitor> handlers = new ArrayDeque<>();

    // Values set by GETVALUE_* instructions inserted by Janala
    private final Values values = new Values();

    // Whether to instrument generators
    // Set this to TRUE when computing execution indexes for generators
    private final boolean traceGenerators = Boolean.getBoolean("jqf.tracing.TRACE_GENERATORS");;

    // Whether to check if caller and callee have the same method name/desc when tracing
    // Set this to TRUE if instrumenting JDK classes, in order to skip JVM classloading activity
    // Also set this to TRUE when using execution indexes, to ensure that every call site has exactly one push/pop
    private final boolean MATCH_CALLEE_NAMES = Boolean.getBoolean("jqf.tracing.MATCH_CALLEE_NAMES");


    /**
     * Creates a new tracer that will process instructions executed by an application
     * thread.
     *
     * @param tracee the thread to trace
     * @param entryPoint the outermost method call to trace (formatted as fq-class#method)
     * @param callback the callback to invoke whenever a trace event is emitted
     */
    protected ThreadTracer(Thread tracee, String entryPoint, Consumer<TraceEvent> callback) {
        this.tracee = tracee;
        if (entryPoint != null) {
            int separator = entryPoint.indexOf('#');
            if (separator <= 0 || separator == entryPoint.length() - 1) {
                throw new IllegalArgumentException("Invalid entry point: " + entryPoint);
            }
            this.entryPointClass = entryPoint.substring(0, separator).replace('.', '/');
            this.entryPointMethod = entryPoint.substring(separator + 1);
        } else {
            this.entryPointClass = null;
            this.entryPointMethod = null;
        }
        this.callback = callback;
        this.handlers.push(new BaseHandler());
    }

    /**
     * Spawns a thread tracer for the given thread.
     *
     * @param thread the thread to trace
     * @return a tracer for the given thread
     */
    protected static ThreadTracer spawn(Thread thread) {
        String entryPoint = SingleSnoop.entryPoints.get(thread);
        Consumer<TraceEvent> callback = SingleSnoop.callbackGenerator.apply(thread);
        ThreadTracer t =
                new ThreadTracer(thread, entryPoint, callback);
        return t;
    }

    protected RuntimeException callBackException = null;

    /**
     * Emits a trace event to be consumed by the registered callback.
     *
     * @param e the event to emit
     */
    protected final void emit(TraceEvent e) {
        try {
            callback.accept(e);
        } catch (RuntimeException ex) {
            callBackException = ex;
        }
    }

    /**
     * Handles tracing of a single bytecode instruction.
     *
     * @param ins the instruction to process
     */
    protected final void consume(Instruction ins) {
        // Apply the visitor at the top of the stack
        ins.visit(handlers.peek());
        if (callBackException != null) {
            RuntimeException e = callBackException;
            callBackException = null;
            throw e;
        }
    }


    private static boolean isReturnOrMethodThrow(Instruction inst) {
        return  inst instanceof ARETURN ||
                inst instanceof LRETURN ||
                inst instanceof DRETURN ||
                inst instanceof FRETURN ||
                inst instanceof IRETURN ||
                inst instanceof RETURN  ||
                inst instanceof METHOD_THROW;
    }


    private static boolean isInvoke(Instruction inst) {
        return  inst instanceof InvokeInstruction;
    }

    private static boolean isIfJmp(Instruction inst) {
        return  inst instanceof ConditionalBranch;
    }


    private static class Values {
        private boolean booleanValue;
        private byte byteValue;
        private char charValue;
        private double doubleValue;
        private float floatValue;
        private int intValue;
        private long longValue;
        private Object objectValue;
        private short shortValue;
    }
    
    private static boolean sameNameDesc(MemberRef m1, MemberRef m2) {
        // Bypass checks for all function calls from java/util/function
        // which are used by lambda function calls.
        if ((m2 != null && m2.getOwner().contains("java/util/function")) ||
                (m1 != null && m1.getName().startsWith("lambda$")) ||
                (m2 != null && m2.getOwner().startsWith("java/util/stream"))
        ) {
            return true;
        }
        return m1 != null && m2 != null &&
                m1.getName().equals(m2.getName()) &&
                m1.getDesc().equals(m2.getDesc());
    }



    class BaseHandler extends ControlFlowInstructionVisitor {
        @Override
        public void visitMETHOD_BEGIN(METHOD_BEGIN begin) {
            // Try to match the top-level call with the entry point
            String clazz = begin.getOwner();
            String method = begin.getName();
            if (MATCH_CALLEE_NAMES == false || (clazz.equals(entryPointClass) && method.equals(entryPointMethod)) ||
                    (traceGenerators && clazz.endsWith("Generator") && method.equals("generate")) ) {
                emit(new CallEvent(0, null, 0, begin));
                handlers.push(new TraceEventGeneratingHandler(begin, 0));
            } else {
                // Ignore all top-level calls that are not the entry point
                handlers.push(new MatchingNullHandler());
            }
        }
    }

    class TraceEventGeneratingHandler extends ControlFlowInstructionVisitor {

        private final int depth;
        private final MemberRef method;
        TraceEventGeneratingHandler(METHOD_BEGIN begin, int depth) {
            this.depth = depth;
            this.method = begin;
            //logger.log(tabs() + begin);
        }

        private String tabs() {
            StringBuffer sb = new StringBuffer(depth);
            for (int i = 0; i < depth; i++) {
                sb.append("  ");
            }
            return sb.toString();
        }

        private MemberRef invokeTarget = null;
        private boolean invokingSuperOrThis = false;

        @Override
        public void visitMETHOD_BEGIN(METHOD_BEGIN begin) {
            if ((MATCH_CALLEE_NAMES == false && begin.name.equals("<clinit>") == false) || sameNameDesc(begin, this.invokeTarget)) {
                // Trace continues with callee
                int invokerIid = invokeTarget != null ? ((Instruction) invokeTarget).iid : -1;
                int invokerMid = invokeTarget != null ? ((Instruction) invokeTarget).mid : -1;
                emit(new CallEvent(invokerIid, this.method, invokerMid, begin, begin.getObject()));
                handlers.push(new TraceEventGeneratingHandler(begin, depth+1));
            } else {
                // Class loading or static initializer
                handlers.push(new MatchingNullHandler());
            }

            super.visitMETHOD_BEGIN(begin);
        }

        @Override
        public void visitINVOKEMETHOD_EXCEPTION(INVOKEMETHOD_EXCEPTION ins) {
            if (this.invokeTarget == null) {
                throw new InstrumentationException("Unexpected INVOKEMETHOD_EXCEPTION", ins.getException());
            } else {
                // Unset the invocation target for the rest of the instruction stream
                this.invokeTarget = null;
                // Handle end of super() or this() call
                if (invokingSuperOrThis) {
                    while (true) { // will break when outer caller of <init> found
                        emit(new ReturnEvent(-1, this.method, -1));
                        handlers.pop();
                        IVisitor handler = handlers.peek();
                        // We should not reach the BaseHandler without finding
                        // the TraceEventGeneratingHandler who called the outer <init>().
                        assert (handler instanceof TraceEventGeneratingHandler);
                        TraceEventGeneratingHandler traceEventGeneratingHandler = (TraceEventGeneratingHandler) handler;
                        if (traceEventGeneratingHandler.invokingSuperOrThis) {
                            // Go down the stack further
                            continue;
                        } else {
                            // Found caller of new()
                            assert(traceEventGeneratingHandler.invokeTarget.getName().startsWith("<init>"));
                            // Let this handler (now top-of-stack) process the instruction
                            ins.visit(traceEventGeneratingHandler);
                            break;
                        }
                    }
                }
            }

            super.visitINVOKEMETHOD_EXCEPTION(ins);
        }

        @Override
        public void visitINVOKEMETHOD_END(INVOKEMETHOD_END ins) {
            if (this.invokeTarget == null) {
                throw new InstrumentationException("Unexpected INVOKEMETHOD_END");
            } else {
                // Unset the invocation target for the rest of the instruction stream
                this.invokeTarget = null;
                // Handle end of super() or this() call
                if (invokingSuperOrThis) {
                    // For normal end, simply unset the flag
                    this.invokingSuperOrThis = false;
                }
            }

            super.visitINVOKEMETHOD_END(ins);
        }

        @Override
        public void visitSPECIAL(SPECIAL special) {
            // Handle marker that says calling super() or this()
            if (special.i == SPECIAL.CALLING_SUPER_OR_THIS) {
                this.invokingSuperOrThis = true;
            }
            return; // Do not process SPECIAL instructions further
        }

        @Override
        public void visitInvokeInstruction(InvokeInstruction ins) {
            // Remember invocation target until METHOD_BEGIN or INVOKEMETHOD_END/INVOKEMETHOD_EXCEPTION
            this.invokeTarget = ins;

            super.visitInvokeInstruction(ins);
        }

        @Override
        public void visitGETVALUE_int(GETVALUE_int gv) {
            values.intValue = gv.v;

            super.visitGETVALUE_int(gv);
        }

        @Override
        public void visitGETVALUE_boolean(GETVALUE_boolean gv) {
            values.booleanValue = gv.v;

            super.visitGETVALUE_boolean(gv);
        }

        @Override
        public void visitConditionalBranch(Instruction ins) {
            int iid = ins.iid;
            int lineNum = ins.mid;
            // The branch taken-or-not would have been set by a previous
            // GETVALUE instruction
            boolean taken = values.booleanValue;
            emit(new BranchEvent(iid, this.method, lineNum, taken ? 1 : 0));

            super.visitConditionalBranch(ins);
        }

        @Override
        public void visitTABLESWITCH(TABLESWITCH tableSwitch) {
            int iid = tableSwitch.iid;
            int lineNum = tableSwitch.mid;
            int value = values.intValue;
            int numCases = tableSwitch.labels.length;
            // Compute arm index or else default
            int arm = -1;
            if (value >= 0 && value < numCases) {
                arm = value;
            }
            // Emit a branch instruction corresponding to the arm
            emit(new BranchEvent(iid, this.method, lineNum, arm));

            super.visitTABLESWITCH(tableSwitch);
        }

        @Override
        public void visitLOOKUPSWITCH(LOOKUPSWITCH lookupSwitch) {
            int iid = lookupSwitch.iid;
            int lineNum = lookupSwitch.mid;
            int value = values.intValue;
            int[] cases = lookupSwitch.keys;
            // Compute arm index or else default
            int arm = -1;
            for (int i = 0; i < cases.length; i++) {
                if (value == cases[i]) {
                    arm = i;
                    break;
                }
            }
            // Emit a branch instruction corresponding to the arm
            emit(new BranchEvent(iid, this.method, lineNum, arm));

            super.visitLOOKUPSWITCH(lookupSwitch);
        }

        @Override
        public void visitHEAPLOAD(HEAPLOAD heapload) {
            int iid = heapload.iid;
            int lineNum = heapload.mid;
            int objectId = heapload.objectId;
            String field = heapload.field;
            // Log the object access (unless it was a NPE)
            if (objectId != 0) {
                emit(new ReadEvent(iid, this.method, lineNum, objectId, field));
            }

            super.visitHEAPLOAD(heapload);
        }

        @Override
        public void visitNEW(NEW newInst) {
            int iid = newInst.iid;
            int lineNum = newInst.mid;
            emit(new AllocEvent(iid, this.method, lineNum, 1));

            super.visitNEW(newInst);
        }

        @Override
        public void visitNEWARRAY(NEWARRAY newArray) {
            int iid = newArray.iid;
            int lineNum = newArray.mid;
            int size = values.intValue;
            emit(new AllocEvent(iid, this.method, lineNum, size));

            super.visitNEWARRAY(newArray);
        }

        @Override
        public void visitReturnOrMethodThrow(Instruction ins) {
            emit(new ReturnEvent(ins.iid, this.method, ins.mid));
            handlers.pop();

            super.visitReturnOrMethodThrow(ins);
        }

    }

    class MatchingNullHandler extends ControlFlowInstructionVisitor {

        @Override
        public void visitMETHOD_BEGIN(METHOD_BEGIN begin) {
            handlers.push(new MatchingNullHandler());
        }

        @Override
        public void visitReturnOrMethodThrow(Instruction ins) {
            handlers.pop();
        }
    }
}
