package applygray.mattermanipulator.building;

import applygray.mattermanipulator.planning.CopyPlan;

/** Outcome of one atomic move transaction. */
public record MoveBuildResult(CopyPlan plan, BuildTransaction.Result transaction) {}
