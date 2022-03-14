/*
 * Copyright (c) 2017-2018 The Regents of the University of California
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
package edu.berkeley.cs.jqf.fuzz.guidance;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;
import java.util.function.Supplier;

import edu.berkeley.cs.jqf.fuzz.junit.quickcheck.FuzzStatement;
import edu.berkeley.cs.jqf.instrument.tracing.events.TraceEvent;

/**
 * A front-end for guided fuzzing.
 *
 * <p>Before each fuzzing trial, the front-end is
 * queried for input to be used as a source
 * of random numbers by junit-quickcheck's generators.
 *
 * <p>During a fuzzing trial, trace events are generated, and the
 * front-end provides callbacks to handle these trace events
 * (e.g. to collect branch coverage).
 *
 * <p>At the end of a fuzzing trial, the front-end is notified of
 * the result of the trial.
 *
 * <p>The standard sequence of method invocations on a Guidance
 * instance is as follows (in pseudo-code):
 * <pre>
 *     while (guidance.hasInput()) {
 *         Random rng = new StreamBackedRandom(guidance.getInput());
 *         Object[] args = generateInput(rng);
 *         try {
 *             runTest(args); // generates many trace events
 *             guidance.handleResult(SUCCESS, null);
 *         } catch (AssumptionViolatedException e) {
 *             guidance.handleResult(INVALID, e);
 *         } catch (Throwable t) {
 *             if (isExpected(e)) {
 *                 guidance.handleResult(SUCCESS, e);
 *             } else {
 *                 guidance.handleResult(FAILURE, e);
 *             }
 *         }
 *     }
 * </pre>
 *
 * See the implementation of {@link FuzzStatement} for the real loop.
 */
public interface Guidance {

    /**
     * Returns a reference to a stream of values
     * return from the pseudo-random number generator.
     *
     * <p>This method is guaranteed to be invoked by JQF at most
     * once after each invocation of {@link #hasInput()} that
     * has returned <tt>true</tt>.
     *
     * <p>If {@link #hasInput()} returns <tt>false</tt> or has not
     * been invoked since the last call to {@link #getInput()},
     * then invoking this method may throw an IllegalStateException.
     *
     * @return  a stream of bytes to be used by the input generator(s)
     * @throws IllegalStateException if the last {@link #hasInput()}
     *                  returned <tt>false</tt>
     * @throws GuidanceException if there was an I/O or other error
     *                  in generating the input stream
     */
    InputStream getInput() throws IllegalStateException, GuidanceException;

    /**
     * Returns whether an input is ready for a new trial
     * to be executed.
     *
     * <p>This method may block until a new
     * input stream is made ready. If this method returns
     * <tt>false</tt>, then JQF stops fuzzing and this
     * guidance will not be used further.
     *
     * @return whether a new trial should be executed
     */
    boolean hasInput();


    default void observeGenerated(Object[] args) {
        // do nothing;
    }

    /**
     * Handles the end of a fuzzing trial.
     *
     * <p>This method is guaranteed to be invoked by JQF
     * exactly once after each invocation of {@link #getInput()}.
     * Therefore, it is safe to open resources such as files
     * in a call to {@link #getInput()} and only close them
     * inside this method.
     *
     * <p>If <tt>result</tt> is <tt>SUCCESS</tt>, then
     * <tt>error</tt> is either <tt>null</tt> or it is
     * an instance of a throwable that is declared by the
     * test method in its <tt>throws</tt> clause.
     *
     * <p>If <tt>result</tt> is <tt>INVALID</tt>,
     * then <tt>error</tt> is either an
     * <tt>AssumptionViolatedException</tt>, if the argument
     * of an <tt>assume()</tt> statement was <tt>false</tt>,
     * or it is a <tt>GuidanceException</tt>, indicating that
     * fuzzing was interrupted during the execution of this
     * trial (and will not continue further).
     *
     * <p>If <tt>result</tt> is <tt>FAILURE</tt>, then
     * <tt>error</tt> is some other throwable that was thrown by
     * the test execution but was not listed in its <tt>throws</tt>
     * clause. This is the only way to detect test errors. Assertion
     * errors will typically fall into this category. So will other
     * unlisted RuntimeExceptions such as NPE.
     *
     * @param result   the result of the fuzzing trial
     * @param error    the error thrown during the trial, or <tt>null</tt>
     * @throws GuidanceException if there was an I/O or other error
     *                  in handling the result
     */
    void handleResult(Result result, Throwable error) throws GuidanceException;

    /**
     * Returns a callback generator for a thread's event trace.
     *
     * <p>The application under test is instrumented such that each
     * thread generates a sequence of {@link TraceEvent}s
     * that may be handled by a separate callback method
     * (though it may also be the same callback).
     *
     * <p>The callback provided by this method will typlically be used
     * for collection execution information such as branch coverage,
     * which in turn is used for constructing the next input stream.
     *
     * <p>This method is a supplier of event consumers. It is invoked
     * once per new application thread spawned during fuzzing.
     *
     * @param thread  the thread whose events to handle
     * @return            a callback that handles trace events generated by
     *                    that thread
     */
    Consumer<TraceEvent> generateCallBack(Thread thread);


    static InputStream createInputStream(Supplier<Integer> inputByteSource) {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                int val = inputByteSource.get();
                if (val < -1 || val > 255) {
                    throw new IOException("inputByteSource should return a byte or -1 on EOF");
                }
                return val;
            }
        };
    }



}
