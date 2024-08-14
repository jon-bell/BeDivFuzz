package edu.berkeley.cs.jqf.examples.chocopy;

import chocopy.common.astnodes.Program;
import chocopy.reference.RefAnalysis;
import chocopy.reference.RefParser;
import com.pholser.junit.quickcheck.From;
import de.hub.se.jqf.bedivfuzz.BeDivFuzz;
import de.hub.se.jqf.bedivfuzz.examples.chocopy.SplitChocoPySemanticGenerator;
import edu.berkeley.cs.jqf.fuzz.Fuzz;
import org.junit.runner.RunWith;

import static org.junit.Assume.assumeTrue;

@RunWith(BeDivFuzz.class)
public class SemanticAnalysisTest {

    /** Entry point for fuzzing reference ChocoPy semantic analysis with ChocoPy code generator */
    @Fuzz
    public void testWithGenerator(@From(ChocoPySemanticGenerator.class) String code) {
        Program program = RefParser.process(code, false);
        assumeTrue(!program.hasErrors());
        RefAnalysis.process(program);
    }

    /** Entry point for fuzzing reference ChocoPy semantic analysis with ChocoPy code generator */
    @Fuzz
    public void testWithSplitGenerator(@From(SplitChocoPySemanticGenerator.class) String code) {
        Program program = RefParser.process(code, false);
        assumeTrue(!program.hasErrors());
        RefAnalysis.process(program);
    }
}
