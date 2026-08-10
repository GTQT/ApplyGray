package applygray.mattermanipulator.inventory;

import java.util.Objects;

/** Simulates, commits, and compensates the energy cost of one atomic build batch. */
public final class EnergyTransaction {

    private final PowerSource source;
    private final long amount;
    private State state;

    private EnergyTransaction(PowerSource source, long amount, State state) {
        this.source = source;
        this.amount = amount;
        this.state = state;
    }

    public static EnergyTransaction none() {
        return new EnergyTransaction(null, 0L, State.COMMITTED);
    }

    public static EnergyTransaction prepare(PowerSource source, long amount) {
        Objects.requireNonNull(source, "source");
        if (amount < 0L) throw new IllegalArgumentException("amount must not be negative");
        if (amount == 0L) return none();

        long available = checkedAmount(source.extract(amount, true), amount);
        if (available != amount) throw new InsufficientPowerException(source.id(), amount - available);
        return new EnergyTransaction(source, amount, State.PREPARED);
    }

    public boolean commit() {
        if (state == State.COMMITTED) return true;
        ensurePrepared();

        long extracted = checkedAmount(source.extract(amount, false), amount);
        if (extracted != amount) {
            if (extracted > 0L) source.insert(extracted, false);
            state = State.FAILED;
            return false;
        }
        state = State.COMMITTED;
        return true;
    }

    public boolean compensateCommitted() {
        if (state == State.COMMITTED && amount == 0L) return true;
        if (state != State.COMMITTED) return false;

        boolean successful = source.insert(amount, false) == amount;
        state = successful ? State.COMPENSATED : State.COMPENSATION_FAILED;
        return successful;
    }

    public State state() {
        return state;
    }

    private void ensurePrepared() {
        if (state != State.PREPARED) throw new IllegalStateException("Energy transaction is " + state);
    }

    private static long checkedAmount(long amount, long maximum) {
        if (amount < 0L || amount > maximum) {
            throw new IllegalStateException("Power source returned an invalid transfer amount: " + amount);
        }
        return amount;
    }

    public enum State {
        PREPARED,
        COMMITTED,
        COMPENSATED,
        COMPENSATION_FAILED,
        FAILED
    }
}
