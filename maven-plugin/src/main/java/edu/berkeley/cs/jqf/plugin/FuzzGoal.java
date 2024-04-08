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
package edu.berkeley.cs.jqf.plugin;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URLClassLoader;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Random;

import de.hub.se.jqf.bedivfuzz.guidance.BeDivFuzzGuidance;
import edu.berkeley.cs.jqf.fuzz.ei.ExecutionIndexingGuidance;
import edu.berkeley.cs.jqf.fuzz.ei.ZestGuidance;
import edu.berkeley.cs.jqf.fuzz.guidance.Guidance;
import edu.berkeley.cs.jqf.fuzz.guidance.GuidanceException;
import edu.berkeley.cs.jqf.fuzz.junit.GuidedFuzzing;
import edu.berkeley.cs.jqf.instrument.InstrumentingClassLoader;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.junit.runner.Result;

import static edu.berkeley.cs.jqf.instrument.InstrumentingClassLoader.stringsToUrls;

/**
 * Maven plugin for feedback-directed fuzzing using JQF.
 *
 * <p>Performs code-coverage-guided generator-based fuzz testing
 * using a provided entry point.</p>
 *
 * @author Rohan Padhye
 */
@Mojo(name="fuzz",
        requiresDependencyResolution= ResolutionScope.TEST,
        defaultPhase=LifecyclePhase.VERIFY)
public class FuzzGoal extends AbstractMojo {

    @Parameter(defaultValue="${project}", required=true, readonly=true)
    MavenProject project;

    @Parameter(property="target", defaultValue="${project.build.directory}", readonly=true)
    private File target;

    /**
     * The fully-qualified name of the test class containing methods
     * to fuzz.
     *
     * <p>This class will be loaded using the Maven project's test
     * classpath. It must be annotated with {@code @RunWith(JQF.class)}</p>
     */
    @Parameter(property="class", required=true)
    private String testClassName;

    /**
     * The name of the method to fuzz.
     *
     * <p>This method must be annotated with {@code @Fuzz}, and take
     * one or more arguments (with optional junit-quickcheck
     * annotations) whose values will be fuzzed by JQF.</p>
     *
     * <p>If more than one method of this name exists in the
     * test class or if the method is not declared
     * {@code public void}, then the fuzzer will not launch.</p>
     */
    @Parameter(property="method", required=true)
    private String testMethod;

    /**
     * The instrumentation method.
     *
     * <p>One of 'janala' and 'fast'. Default is 'fast'.</p>
     */
    @Parameter(property="instrumentation", defaultValue="fast")
    private String instrumentation;

    /**
     * Comma-separated list of FQN prefixes to exclude from
     * coverage instrumentation.
     *
     * <p>Example: <code>org/mozilla/javascript/gen,org/slf4j/logger</code>,
     * will exclude classes auto-generated by Mozilla Rhino's CodeGen and
     * logging classes.</p>
     */
    @Parameter(property="excludes")
    private String excludes;

    /**
     * Comma-separated list of FQN prefixes to forcibly include,
     * even if they match an exclude.
     *
     * <p>Typically, these will be a longer prefix than a prefix
     * in the excludes clauses.</p>
     */
    @Parameter(property="includes")
    private String includes;

    /**
     * The duration of time for which to run fuzzing.
     *
     * <p>
     * If neither this property nor {@code trials} are provided, the fuzzing
     * session is run for an unlimited time until the process is terminated by the
     * user (e.g. via kill or CTRL+C).
     * </p>
     *
     * <p>
     * Valid time durations are non-empty strings in the format [Nh][Nm][Ns], such
     * as "60s" or "2h30m".
     * </p>
     */
    @Parameter(property="time")
    private String time;

    /**
     * The number of trials for which to run fuzzing.
     *
     * <p>
     * If neither this property nor {@code time} are provided, the fuzzing
     * session is run for an unlimited time until the process is terminated by the
     * user (e.g. via kill or CTRL+C).
     * </p>
     */ 
    @Parameter(property="trials")
    private Long trials;

    /**
     * A number to seed the source of randomness in the fuzzing algorithm.
     *
     * <p>
     * Setting this to any value will make the result of running the same fuzzer
     * with on the same input the same. This is useful for testing the fuzzer, but
     * shouldn't be used on code attempting to find real bugs. By default, the
     * seed is chosen randomly based on system state.
     * </p>
     */
    @Parameter(property="randomSeed")
    private Long randomSeed;
    
    /**
     * Whether to generate inputs blindly without taking into
     * account coverage feedback. Blind input generation is equivalent
     * to running QuickCheck.
     *
     * <p>If this property is set to <code>true</code>, then the fuzzing
     * algorithm does not maintain a queue. Every input is randomly
     * generated from scratch. The program under test is still instrumented
     * in order to provide coverage statistics. This mode is mainly useful
     * for comparing coverage-guided fuzzing with plain-old QuickCheck. </p>
     */
    @Parameter(property="blind")
    private boolean blind;

    /**
     * The fuzzing engine.
     *
     * <p>One of 'zest', 'zeal', and 'bedivfuzz'. Default is 'bedivfuzz'.</p>
     */
    @Parameter(property="engine", defaultValue="bedivfuzz")
    private String engine;


    /**
     * Whether to track coverage and behavioral diversity of semantic analysis classes.
     *
     * <p>The packages/classes that should be considered as part of
     * the semantic analysis stage need to be specified with the
     * -DsemanticAnalysisClasses parameter.</p>
     */
    @Parameter(property = "trackSemanticCoverage")
    private boolean trackSemanticCoverage;

    /**
     * Comma-separated list of FQN prefixes to consider as
     * semantic analysis classes. Can be specified as
     * regular expressions.
     *
     */
    @Parameter(property = "semanticAnalysisClasses")
    private String semanticAnalysisClasses;


    /**
     * Whether to disable code-coverage instrumentation.
     *
     * <p>Disabling instrumentation speeds up test case execution, but
     * provides no feedback about code coverage in the status screen and
     * to the fuzzing guidance.</p>
     *
     * <p>This setting only makes sense when used with {@code -Dblind}.</p>
     *
     */
    @Parameter(property="noCov")
    private boolean disableCoverage;

    /**
     * The name of the input directory containing seed files.
     *
     * <p>If not provided, then fuzzing starts with randomly generated
     * initial inputs.</p>
     */
    @Parameter(property="in")
    private String inputDirectory;

    /**
     * The name of the output directory where fuzzing results will
     * be stored.
     *
     * <p>The directory will be created inside the standard Maven
     * project build directory.</p>
     *
     * <p>If not provided, defaults to
     * <em>jqf-fuzz/${testClassName}/${$testMethod}</em>.</p>
     */
    @Parameter(property="out")
    private String outputDirectory;

    /**
     * Whether to save ALL inputs generated during fuzzing, even
     * the ones that do not have any unique code coverage.
     *
     * <p>This setting leads to a very large number of files being
     * created in the output directory, and could potentially
     * reduce the overall performance of fuzzing.</p>
     */
    @Parameter(property="saveAll")
    private boolean saveAll;

    /**
     * Whether to save the latest branch hit count map as part of the output.
     *
     * <p>This writes the branch hit count map to a 'branch_hit_counts' file that
     * can be deserialized into an IntIntHashMap using jackson-databind.</p>
     */
    @Parameter(property="saveBranchHitCounts")
    private boolean saveBranchHitCounts;

    /**
     *  The minimum amount of time (in millis) between two stats refreshes.
     */
    @Parameter(property="statsRefreshTimePeriod")
    private int statsRefreshTimePeriod;


    /**
     * Weather to use libFuzzer like output instead of AFL like stats
     * screen
     *
     * <p>If this property is set to <code>true</>, then output will look like libFuzzer output
     * https://llvm.org/docs/LibFuzzer.html#output
     * .</p>
     */
    @Parameter(property="libFuzzerCompatOutput")
    private String libFuzzerCompatOutput;

    /**
     * Whether to avoid printing fuzzing statistics progress in the console.
     *
     * <p>If not provided, defaults to {@code false}.</p>
     */
    @Parameter(property="quiet")
    private boolean quiet;
  
    /**
     * Whether to stop fuzzing once a crash is found.
     *
     * <p>If this property is set to <code>true</code>, then the fuzzing
     * will exit on first crash. Useful for continuous fuzzing when you dont wont to consume resource
     * once a crash is found. Also fuzzing will be more effective once the crash is fixed.</p>
     */
    @Parameter(property="exitOnCrash")
    private String exitOnCrash;

    /**
     * The timeout for each individual trial, in milliseconds.
     *
     * <p>If not provided, defaults to 0 (unlimited).</p>
     */
    @Parameter(property="runTimeout")
    private int runTimeout;

    /**
     * Whether to bound size of inputs being mutated by the fuzzer.
     *
     * <p>If this property is set to true, then the fuzzing engine
     * will treat inputs as fixed-size arrays of bytes
     * rather than as an infinite stream of pseudo-random choices. This option
     * is appropriate when fuzzing test methods that take a single
     * argument of type {@link java.io.InputStream} and that also provide a
     * set of seed inputs via the `in' property.</p>
     *
     * <p>If not provided, defaults to {@code false}.</p>
     */
    @Parameter(property="fixedSize")
    private boolean fixedSizeInputs;


    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        ClassLoader loader;
        ZestGuidance guidance;
        Log log = getLog();
        PrintStream out = log.isDebugEnabled() ? System.out : null;
        Result result;

        // Configure instrumentation
        switch (instrumentation) {
            case "fast":
                System.setProperty("useFastNonCollidingCoverageInstrumentation", String.valueOf(true));
                break;
            case "janala":
                System.setProperty("useFastNonCollidingCoverageInstrumentation", String.valueOf(false));
                break;
            default:
                throw new MojoExecutionException("Unknown instrumentation method: " + instrumentation);
        }

        // Configure classes to instrument
        if (excludes != null) {
            System.setProperty("janala.excludes", excludes);
        }
        if (includes != null) {
            System.setProperty("janala.includes", includes);
        }

        // Configure Zest Guidance
        if (saveAll) {
            System.setProperty("jqf.ei.SAVE_ALL_INPUTS", "true");
        }
        if (saveBranchHitCounts) {
            System.setProperty("jqf.guidance.SERIALIZE_BRANCH_HIT_COUNTS", "true");
        }
        if (statsRefreshTimePeriod > 0) {
            System.setProperty("jqf.guidance.STATS_REFRESH_TIME_PERIOD", String.valueOf(statsRefreshTimePeriod));
        }
        if (libFuzzerCompatOutput != null) {
            System.setProperty("jqf.ei.LIBFUZZER_COMPAT_OUTPUT", libFuzzerCompatOutput);
        }
        if (quiet) {
            System.setProperty("jqf.ei.QUIET_MODE", "true");
        }
        if (exitOnCrash != null) {
            System.setProperty("jqf.ei.EXIT_ON_CRASH", exitOnCrash);
        }
        if (runTimeout > 0) {
            System.setProperty("jqf.ei.TIMEOUT", String.valueOf(runTimeout));
        }
        if (fixedSizeInputs) {
            System.setProperty("jqf.ei.GENERATE_EOF_WHEN_OUT", String.valueOf(true));
        }

        Duration duration = null;
        if (time != null && !time.isEmpty()) {
            try {
                duration = Duration.parse("PT"+time);
            } catch (DateTimeParseException e) {
                throw new MojoExecutionException("Invalid time duration: " + time);
            }
        }

        if (trackSemanticCoverage) {
            if (!instrumentation.equals("fast")) {
                throw new MojoExecutionException(
                        "Semantic coverage can only be tracked using fast instrumentation, currently using: " + instrumentation);
            }

            System.setProperty("jqf.guidance.TRACK_SEMANTIC_COVERAGE", String.valueOf(true));
            if (semanticAnalysisClasses != null) {
                System.setProperty("janala.semanticAnalysisClasses", semanticAnalysisClasses);
            } else {
                throw new MojoExecutionException(
                        "No semantic analysis classes specified. Specify with -DsemanticAnalysisClasses");
            }
        }

        if (outputDirectory == null || outputDirectory.isEmpty()) {
            outputDirectory = "fuzz-results" + File.separator + testClassName + File.separator + testMethod;
        }

        try {
            List<String> classpathElements = project.getTestClasspathElements();

            if (disableCoverage) {
                loader = new URLClassLoader(
                        stringsToUrls(classpathElements.toArray(new String[0])),
                        getClass().getClassLoader());

            } else {
                loader = new InstrumentingClassLoader(
                        classpathElements.toArray(new String[0]),
                        getClass().getClassLoader());
            }
        } catch (DependencyResolutionRequiredException|MalformedURLException e) {
            throw new MojoExecutionException("Could not get project classpath", e);
        }

        File resultsDir = new File(target, outputDirectory);
        String targetName = testClassName + "#" + testMethod;
        File seedsDir = inputDirectory == null ? null : new File(inputDirectory);
        Random rnd = randomSeed != null ? new Random(randomSeed) : new Random();
        try {
            switch (engine) {
                case "bedivfuzz":
                    guidance = new BeDivFuzzGuidance(targetName, duration, trials, resultsDir, seedsDir, rnd);
                    break;
                case "zest":
                    guidance = new ZestGuidance(targetName, duration, trials, resultsDir, seedsDir, rnd);
                    break;
                case "zeal":
                    System.setProperty("jqf.tracing.TRACE_GENERATORS", "true");
                    System.setProperty("jqf.tracing.MATCH_CALLEE_NAMES", "true");
                    guidance = new ExecutionIndexingGuidance(targetName, duration, trials, resultsDir, seedsDir, rnd);
                    break;
                default:
                    throw new MojoExecutionException("Unknown fuzzing engine: " + engine);
            }
        } catch (FileNotFoundException e) {
            throw new MojoExecutionException("File not found", e);
        } catch (IOException e) {
            throw new MojoExecutionException("I/O error", e);
        }
        guidance.setBlind(blind);

        try {
            result = GuidedFuzzing.run(testClassName, testMethod, loader, guidance, out);
        } catch (ClassNotFoundException e) {
            throw new MojoExecutionException("Could not load test class", e);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException("Bad request", e);
        } catch (RuntimeException e) {
            throw new MojoExecutionException("Internal error", e);
        }

        if (!result.wasSuccessful()) {
            Throwable e = result.getFailures().get(0).getException();
            if (result.getFailureCount() == 1) {
                if (e instanceof GuidanceException) {
                    throw new MojoExecutionException("Internal error", e);
                }
            }
            throw new MojoFailureException(String.format("Fuzzing resulted in the test failing on " +
                    "%d input(s). Possible bugs found. " +
                    "Use mvn jqf:repro to reproduce failing test cases from %s/failures. ",
                    result.getFailureCount(), resultsDir) +
                    "Sample exception included with this message.", e);
        }
    }
}
