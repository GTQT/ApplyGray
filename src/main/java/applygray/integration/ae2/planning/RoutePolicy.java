package applygray.integration.ae2.planning;

/** Owns route-cost ordering so local scoring does not embed material or provider policy. */
@FunctionalInterface
public interface RoutePolicy {

    int compare(RouteModel.Cost left, RouteModel.Cost right);

    static RoutePolicy deterministic() {
        return (left, right) -> {
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
        };
    }
}
