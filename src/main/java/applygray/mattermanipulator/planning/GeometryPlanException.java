package applygray.mattermanipulator.planning;

/** A rejected geometry request has no partial plan and therefore cannot mutate a world. */
public final class GeometryPlanException extends IllegalArgumentException {

    private final Reason reason;

    public GeometryPlanException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        MISSING_SELECTION,
        CROSS_DIMENSION_SELECTION,
        INVALID_CYLINDER,
        INVALID_COPY_TRANSFORM,
        OVERLAPPING_MOVE,
        OPERATION_LIMIT_EXCEEDED
    }
}
