package applygray.integration.ae2.rules;

/** Route objective selected for one planning request. */
public enum PlanningMode {
    STOCK_FIRST,
    RESOURCE_FIRST,
    THROUGHPUT_FIRST,
    SAFE_FIRST,
    PINNED
}
