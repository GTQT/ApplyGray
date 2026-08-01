package applygray.integration.ae2;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CycleRecoveryTrackerTest {

    @Test
    void repeatedObservationReportsNoProgress() {
        CycleRecoveryTracker<String> tracker = new CycleRecoveryTracker<>();
        Set<String> members = Set.of("chrome", "dustChrome");

        int first = tracker.reject("chrome", "extractor:one", members, true);
        int repeated = tracker.reject("chrome", "extractor:one", members, true);

        assertTrue(first > 0);
        assertEquals(0, repeated);
        assertTrue(tracker.rejectsRecipeOrUnknown("chrome", "extractor:one"));
    }

    @Test
    void equivalentEdgeRequiresAMandatoryCycleInput() {
        Set<String> members = Set.of("chrome", "dustChrome");

        assertTrue(CycleRecoveryTracker.requiresCycleMember(
                List.of(List.of("dustChrome"), List.of("externalOre")), members));
        assertFalse(CycleRecoveryTracker.requiresCycleMember(
                List.of(List.of("dustChrome", "externalOre")), members));
    }

    @Test
    void safetyUnknownAndRejectionsStayInsideOneTracker() {
        CycleRecoveryTracker<String> firstCalculation = new CycleRecoveryTracker<>();
        CycleRecoveryTracker<String> nextCalculation = new CycleRecoveryTracker<>();

        assertTrue(firstCalculation.markSafetyUnknown("chrome", "SCC_TIME_LIMIT", false));
        assertFalse(firstCalculation.markSafetyUnknown("chrome", "SCC_TIME_LIMIT", false));
        assertFalse(firstCalculation.rejectsRecipeOrUnknown("chrome", "extractor:any"));
        assertFalse(nextCalculation.rejectsRecipeOrUnknown("chrome", "extractor:any"));
    }

    @Test
    void fallbackPolicyWithholdsSafetyUnknownTarget() {
        CycleRecoveryTracker<String> tracker = new CycleRecoveryTracker<>();

        assertTrue(tracker.markSafetyUnknown("chrome", "SCC_TIME_LIMIT", true));
        assertTrue(tracker.rejectsRecipeOrUnknown("chrome", "extractor:any"));
    }
}
