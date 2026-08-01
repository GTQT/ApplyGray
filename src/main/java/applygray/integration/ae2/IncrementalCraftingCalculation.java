package applygray.integration.ae2;

import ae2.api.stacks.AEKey;

import java.util.Set;

/** Allows an ordinary calculation to rebuild only branches whose dynamic patterns changed. */
public interface IncrementalCraftingCalculation {

    void applygray$prepareIncrementalRetry(Set<AEKey> changedTargets);
}
