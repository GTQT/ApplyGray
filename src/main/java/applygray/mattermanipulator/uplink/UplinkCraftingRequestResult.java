package applygray.mattermanipulator.uplink;

/** Outcome of a direct Quantum Uplink crafting-request submission. */
public record UplinkCraftingRequestResult(Status status, int jobCount) {

    public static UplinkCraftingRequestResult accepted(int jobCount) {
        return new UplinkCraftingRequestResult(Status.ACCEPTED, jobCount);
    }

    public static UplinkCraftingRequestResult rejected(Status status) {
        if (status == Status.ACCEPTED) throw new IllegalArgumentException("Accepted requests require a job count");
        return new UplinkCraftingRequestResult(status, 0);
    }

    public boolean accepted() {
        return status == Status.ACCEPTED;
    }

    public enum Status {

        ACCEPTED,
        EMPTY,
        OFFLINE,
        AE_OFFLINE,
        NO_PLASMA,
        QUEUE_FULL,
        INVALID_REQUIREMENTS
    }
}
