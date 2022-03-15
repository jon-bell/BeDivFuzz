package de.hub.se.jqf.fuzz.div;

import edu.berkeley.cs.jqf.fuzz.util.Counter;
import edu.berkeley.cs.jqf.fuzz.util.Coverage;
import edu.berkeley.cs.jqf.fuzz.util.ICoverage;
import edu.berkeley.cs.jqf.fuzz.util.NonZeroCachingCounter;

import java.util.*;
import java.util.stream.Collectors;

import org.eclipse.collections.api.iterator.IntIterator;
import org.eclipse.collections.api.list.primitive.IntList;

public class DivMetricsCounter {
    /** The last time since the metrics have been computed (to reduce overhead). */
    private Date lastRefreshTime;

    /** The stat refresh period in ms */
    protected final long STATS_REFRESH_TIME_PERIOD = 5000; // Every 5 seconds

    /** The size of the coverage map. */
    private final int COVERAGE_MAP_SIZE = (1 << 16) - 1; // Minus one to reduce collisions

    /** The coverage counts for each edge. */
    private final Counter counter = new NonZeroCachingCounter(COVERAGE_MAP_SIZE);

    /** Total number of branches executed*/
    private long totalBranchHitCount;

    /** Total number of valid executions */
    private long numExecutions;

    /** Cached values for diversity indices. */
    // H_0, H_1, H_2
    private double[] cachedMetrics;

    public DivMetricsCounter() {
        lastRefreshTime = new Date();
        totalBranchHitCount = 0;
        numExecutions = 0;
        cachedMetrics = new double[]{0, 0, 0};
    }

    public void incrementBranchCounts(ICoverage runCoverage) {
        // First fix the covered branches
        IntList coveredProbes = runCoverage.getCovered();
        IntIterator iter = coveredProbes.intIterator();
        while(iter.hasNext()){
            int idx = iter.next();
            counter.increment(idx);
            totalBranchHitCount++;
        }
        numExecutions += 1;
    }

    public double[] getCachedMetrics(Date now) {

        // Update cached values once every while
        if (now.getTime() - lastRefreshTime.getTime() >= STATS_REFRESH_TIME_PERIOD) {
            updateMetrics();
            lastRefreshTime = now;
        }
        return cachedMetrics;
    }

    public void updateMetrics() {
        IntList nonZeroCoverageValues = counter.getNonZeroValues();
        double shannon = 0;
        double h_0 = 0;
        double h_2 = 0;

        IntIterator iter = nonZeroCoverageValues.intIterator();
        while(iter.hasNext()){
            int hit_count = iter.next();

            double  p_i = ((double) hit_count) / totalBranchHitCount;
            shannon += p_i * Math.log(p_i);

            h_0 += Math.pow(p_i, 0);
            h_2 += Math.pow(p_i, 2);

        }
        cachedMetrics[0] = Math.pow(h_0, 1); // Hill-Number of order 0
        cachedMetrics[1] = Math.exp(-shannon); // Hill-number of order 1 (= exp(shannon_index))
        cachedMetrics[2] = Math.pow(h_2, 1/(1-2)); // Hill-number of order 2

    }

    public double shannonIndex() {
        double sum = 0;
        IntList nonZeroCoverageValues = counter.getNonZeroValues(); 
        IntIterator iter = nonZeroCoverageValues.intIterator();
        while(iter.hasNext()){
            int hit_count = iter.next();
            double  p_i = ((double) hit_count) / totalBranchHitCount;
            sum += p_i * Math.log(p_i);
        }
        return - sum;
    }

    public double behavioral_diversity(int order) {
        if (order == 1) {
            return Math.exp(shannonIndex());
        }
        double sum = 0;
        IntList nonZeroCoverageValues = counter.getNonZeroValues(); 
        IntIterator iter = nonZeroCoverageValues.intIterator();
        while(iter.hasNext()){
            int hit_count = iter.next();
            double  p_i = ((double)hit_count)/totalBranchHitCount;
            sum += Math.pow(p_i, order);
        }
        return Math.pow(sum, 1/(1-order));
    }
}
