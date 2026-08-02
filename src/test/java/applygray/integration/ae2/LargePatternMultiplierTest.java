package applygray.integration.ae2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LargePatternMultiplierTest {

    @Test
    void leavesSmallPlansUnchanged() {
        assertEquals(1, LargePatternMultiplier.chooseMultiplier(1, Integer.MAX_VALUE));
    }

    @Test
    void batchesAThousandRunsIntoOnePlanningExecution() {
        int multiplier = LargePatternMultiplier.chooseMultiplier(1_000, Integer.MAX_VALUE);

        assertEquals(1_000, multiplier);
        assertEquals(1, LargePatternMultiplier.getPlannedRuns(1_000, multiplier));
    }

    @Test
    void batchesLargeRequestsToOneExecutionWhenThePatternStackAllowsIt() {
        int multiplier = LargePatternMultiplier.chooseMultiplier(100_000, Integer.MAX_VALUE);

        assertEquals(100_000, multiplier);
        assertEquals(1, LargePatternMultiplier.getPlannedRuns(100_000, multiplier));
    }

    @Test
    void respectsThePatternStackCapacity() {
        int multiplier = LargePatternMultiplier.chooseMultiplier(100_000, 17);

        assertEquals(17, multiplier);
        assertEquals(5_883, LargePatternMultiplier.getPlannedRuns(100_000, multiplier));
    }

    @Test
    void refusesBatchingWhenThePatternCannotBeScaled() {
        assertEquals(1, LargePatternMultiplier.chooseMultiplier(100_000, 1));
    }
}
