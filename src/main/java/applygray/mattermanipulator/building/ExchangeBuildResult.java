package applygray.mattermanipulator.building;

import applygray.mattermanipulator.planning.BoundExchangePlan;

/** Result for one tier-bounded exchange batch. */
public record ExchangeBuildResult(BoundExchangePlan plan, int startIndex, int nextOperationIndex,
                                  BuildTransaction.Result transaction) {

    public boolean complete() {
        return nextOperationIndex >= plan.operations().size();
    }
}
