package de.hub.se.jqf.bedivfuzz.junit.quickcheck;

import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import de.hub.se.jqf.bedivfuzz.junit.quickcheck.tracking.SplitTrackingSourceOfRandomness;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;

public class SplitTrackingSourceOfRandomnessTest {

    private static Random r;
    private LinearTestInput input;
    private SplitTrackingSourceOfRandomness trackingRandom;

    @Before
    public void setupSourceOfRandomness() {
        r = new Random(24);
        input = new LinearTestInput();
        trackingRandom = new SplitTrackingSourceOfRandomness(createParameterStream());
    }

    public static class LinearTestInput {
        int requested = 0;

        public int getOrGenerateFresh(Integer key, Random random) {
            requested++;
            return random.nextInt();
        }
    }

    protected InputStream createParameterStream() {
        return new InputStream() {
            int bytesRead = 0;

            @Override
            public int read() throws IOException {
                return input.getOrGenerateFresh(bytesRead++, r);
            }
        };
    }

    @Test
    public void testStructuralParameterRequests() {
        trackingRandom.nextStructureInt();
        trackingRandom.nextStructureBoolean();
        trackingRandom.nextStructureDouble();
        trackingRandom.chooseStructure(List.of(1, 2, 3));
        trackingRandom.chooseStructure(List.of(1));
        assertEquals(trackingRandom.getCurrentChoiceOffset(), input.requested);
    }

    @Test
    public void testValueParameterRequests() {
        trackingRandom.nextValueBoolean();
        trackingRandom.nextValueBytes(10);
        trackingRandom.nextValueLong();
        trackingRandom.chooseValue(List.of(1, 2, 3).toArray());
        trackingRandom.chooseValue(List.of(1).toArray());
        assertEquals(trackingRandom.getCurrentChoiceOffset(), input.requested);
    }

    @Test
    public void testSplitParameterRequests() {
        trackingRandom.nextStructureInt();
        trackingRandom.nextStructureInt(10);
        trackingRandom.nextValueBoolean();
        trackingRandom.nextStructureDouble();
        assertEquals(trackingRandom.getCurrentChoiceOffset(), input.requested);
    }

    @Test
    public void testDelegateRequests() {
        trackingRandom.nextValueBoolean();
        SourceOfRandomness structuralDelegate = trackingRandom.getStructureDelegate();
        structuralDelegate.nextBoolean();
        structuralDelegate.nextDouble();
        trackingRandom.getValueDelegate().nextDouble();
        trackingRandom.nextStructureInt(10);
        assertEquals(trackingRandom.getCurrentChoiceOffset(), input.requested);
    }

}
