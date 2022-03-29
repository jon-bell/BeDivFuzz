package de.hub.se.jqf.fuzz.div;

import edu.berkeley.cs.jqf.fuzz.util.Counter;
import edu.berkeley.cs.jqf.fuzz.util.Coverage;
import edu.berkeley.cs.jqf.fuzz.util.NonZeroCachingCounter;

import java.util.*;
import java.util.stream.Collectors;

public class DivMetricsCounter {
    public DivMetricsCounter() {
    }

    public double[] metricsFromCoverage(Coverage totalCoverage) {
        Counter counter = totalCoverage.counter;
        double[] cachedMetrics = new double[3];
        HashSet<Integer> coveredBranches = new HashSet<>(counter.getNonZeroIndices());
        double shannon = 0;
        double h_0 = 0;
        double h_2 = 0;
        double totalBranchHitCount = 0;
        for (Integer idx : coveredBranches) {
            int hit_count = counter.getAtIndex(idx);
            totalBranchHitCount += hit_count;
        }

        for (Integer idx : coveredBranches) {
            int hit_count = counter.getAtIndex(idx);

            double  p_i = ((double) hit_count) / totalBranchHitCount;
            shannon += p_i * Math.log(p_i);

            h_0 += Math.pow(p_i, 0);
            h_2 += Math.pow(p_i, 2);

        }
        cachedMetrics[0] = Math.pow(h_0, 1); // Hill-Number of order 0
        cachedMetrics[1] = Math.exp(-shannon); // Hill-number of order 1 (= exp(shannon_index))
        cachedMetrics[2] = Math.pow(h_2, 1/(1-2)); // Hill-number of order 2
        return cachedMetrics;
    }
}
