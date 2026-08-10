package applygray.mattermanipulator.building;

import applygray.mattermanipulator.planning.BoundCopyPlan;

/** Result for one bounded copy batch. */
public record CopyBuildResult(BoundCopyPlan plan, int startIndex, int nextOperationIndex,
                              BuildTransaction.Result transaction) {

    public boolean complete() {
        return nextOperationIndex >= plan.operations().size();
    }
}
