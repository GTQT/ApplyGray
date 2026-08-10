package applygray.mattermanipulator.building;

import applygray.mattermanipulator.planning.BoundGeometryPlan;

/** Result for one prepared geometry batch; callers schedule the next index on a later server tick. */
public record GeometryBuildResult(BoundGeometryPlan plan, int startIndex, int nextOperationIndex,
                                  BuildTransaction.Result transaction) {

    public boolean complete() {
        return nextOperationIndex >= plan.operations().size();
    }
}
