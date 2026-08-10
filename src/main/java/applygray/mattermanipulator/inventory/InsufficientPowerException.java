package applygray.mattermanipulator.inventory;

/** Thrown before material extraction when the active tool cannot cover a planned batch's energy cost. */
public final class InsufficientPowerException extends RuntimeException {

    private final String sourceId;
    private final long missingAmount;

    public InsufficientPowerException(String sourceId, long missingAmount) {
        super("Insufficient power from " + sourceId + ": missing " + missingAmount + " EU");
        this.sourceId = sourceId;
        this.missingAmount = missingAmount;
    }

    public String sourceId() {
        return sourceId;
    }

    public long missingAmount() {
        return missingAmount;
    }
}
