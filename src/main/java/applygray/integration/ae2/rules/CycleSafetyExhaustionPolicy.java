package applygray.integration.ae2.rules;

/** Behavior used when bounded cycle analysis cannot prove a dynamic route safe. */
public enum CycleSafetyExhaustionPolicy {
    /** Withhold safety-unknown dynamic routes so mounted normal patterns remain available as the fallback. */
    FALLBACK_NORMAL,
    /** Keep planning and let the task-scoped, bounded cycle recovery reject only routes that actually recurse. */
    RUNTIME_RECOVERY
}
