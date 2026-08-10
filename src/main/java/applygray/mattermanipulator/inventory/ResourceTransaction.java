package applygray.mattermanipulator.inventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import applygray.mattermanipulator.building.BlockSpec;

/**
 * Two-phase material reservation shared by player, AE, and uplink sources.
 *
 * <p>The first phase is simulation-only. If a source delivers less than reserved during commit, completed extractions
 * are inserted back into their original source in reverse order and the caller receives a failed result.</p>
 */
public final class ResourceTransaction {

    private final List<Reservation> reservations;
    private List<Reservation> committedReservations = List.of();
    private State state = State.PREPARED;

    private ResourceTransaction(List<Reservation> reservations) {
        this.reservations = List.copyOf(reservations);
    }

    public static ResourceTransaction prepare(List<? extends MaterialSource> sources, ResourceRequirements requirements) {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(requirements, "requirements");
        if (sources.isEmpty() && !requirements.isEmpty()) {
            ResourceRequirement first = requirements.entries().getFirst();
            throw new InsufficientResourcesException(first.specification(), first.amount());
        }

        Map<MaterialSource, Map<BlockSpec, Long>> allocations = new LinkedHashMap<>();
        for (ResourceRequirement requirement : requirements.entries()) {
            long remaining = requirement.amount();
            for (MaterialSource source : sources) {
                if (remaining == 0) break;
                long extracted = checkedAmount(source.extract(requirement.specification(), remaining, true), remaining);
                if (extracted == 0) continue;
                allocations.computeIfAbsent(source, ignored -> new LinkedHashMap<>())
                        .merge(requirement.specification(), extracted, Math::addExact);
                remaining -= extracted;
            }
            if (remaining != 0) throw new InsufficientResourcesException(requirement.specification(), remaining);
        }

        List<Reservation> reservations = new ArrayList<>();
        allocations.forEach((source, requirementsBySpec) -> requirementsBySpec.forEach(
                (specification, amount) -> reservations.add(new Reservation(source, specification, amount))));
        return new ResourceTransaction(reservations);
    }

    public Result commit() {
        ensurePrepared();
        List<Reservation> completed = new ArrayList<>();
        for (Reservation reservation : reservations) {
            long extracted = checkedAmount(reservation.source.extract(reservation.specification, reservation.amount, false),
                    reservation.amount);
            if (extracted != reservation.amount) {
                boolean rollbackSucceeded = extracted == 0 ||
                        reservation.source.insert(reservation.specification, extracted, false) == extracted;
                rollbackSucceeded &= rollback(completed);
                state = State.FAILED;
                return new Result(false, rollbackSucceeded, reservation.source.id(), reservation.specification,
                        reservation.amount - extracted);
            }
            completed.add(reservation);
        }
        state = State.COMMITTED;
        committedReservations = List.copyOf(completed);
        return new Result(true, true, "", null, 0L);
    }

    /**
     * Returns every committed reservation to its original source after a later world mutation fails.
     *
     * <p>This can be called exactly once after a successful {@link #commit()}. It intentionally does not retry a
     * partial compensation: callers must surface that exceptional state instead of risking duplicate material.</p>
     */
    public boolean compensateCommitted() {
        if (state != State.COMMITTED) return false;

        boolean successful = rollback(committedReservations);
        state = successful ? State.COMPENSATED : State.COMPENSATION_FAILED;
        return successful;
    }

    public boolean rollback() {
        if (state == State.COMMITTED) return false;
        if (state == State.FAILED) return false;
        state = State.ROLLED_BACK;
        return true;
    }

    public State state() {
        return state;
    }

    private boolean rollback(List<Reservation> completed) {
        boolean successful = true;
        for (int index = completed.size() - 1; index >= 0; index--) {
            Reservation reservation = completed.get(index);
            if (reservation.source.insert(reservation.specification, reservation.amount, false) != reservation.amount) {
                successful = false;
            }
        }
        return successful;
    }

    private void ensurePrepared() {
        if (state != State.PREPARED) throw new IllegalStateException("Resource transaction is " + state);
    }

    private static long checkedAmount(long amount, long maximum) {
        if (amount < 0 || amount > maximum) {
            throw new IllegalStateException("Material source returned an invalid transfer amount: " + amount);
        }
        return amount;
    }

    private record Reservation(MaterialSource source, BlockSpec specification, long amount) {}

    public enum State {
        PREPARED,
        COMMITTED,
        COMPENSATED,
        COMPENSATION_FAILED,
        ROLLED_BACK,
        FAILED
    }

    public record Result(boolean committed, boolean rollbackSucceeded, String failedSource, BlockSpec missingSpecification,
                         long missingAmount) {}
}
