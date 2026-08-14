package applygray.integration.ae2.planning;

/** Owns route-cost ordering so local scoring does not embed material or provider policy. */
@FunctionalInterface
public interface RoutePolicy {

    int compare(RouteModel.Cost left, RouteModel.Cost right);

    /** True only when adding non-negative downstream costs cannot let the lower bound beat the incumbent. */
    default boolean incumbentStrictlyDominates(RouteModel.Cost incumbent, RouteModel.Cost lowerBound) {
        return false;
    }

    static RoutePolicy deterministic() {
        return new RoutePolicy() {

            @Override
            public int compare(RouteModel.Cost left, RouteModel.Cost right) {
                int result = Integer.compare(left.boundedFallbacks(), right.boundedFallbacks());
                if (result != 0) return result;
                result = Integer.compare(left.unresolvedIntermediates(), right.unresolvedIntermediates());
                if (result != 0) return result;
                result = Long.compare(left.cycleRisk(), right.cycleRisk());
                if (result != 0) return result;
                result = Long.compare(left.materialFormConversions(), right.materialFormConversions());
                if (result != 0) return result;
                result = Long.compare(left.missingMaterials(), right.missingMaterials());
                if (result != 0) return result;
                result = Integer.compare(left.maxDepth(), right.maxDepth());
                if (result != 0) return result;
                result = Long.compare(left.executions(), right.executions());
                if (result != 0) return result;
                return Long.compare(left.consumedStockMaterials(), right.consumedStockMaterials());
            }

            @Override
            public boolean incumbentStrictlyDominates(RouteModel.Cost incumbent, RouteModel.Cost lowerBound) {
                return compare(incumbent, lowerBound) < 0;
            }
        };
    }
}
