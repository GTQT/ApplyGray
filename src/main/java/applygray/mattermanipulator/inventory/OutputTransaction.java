package applygray.mattermanipulator.inventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import applygray.mattermanipulator.building.BlockSpec;

/**
 * Two-phase insertion reservation for block drops and exchange outputs.
 *
 * <p>Output capacity is checked before any block is mutated. A later world failure extracts the exact inserted
 * resources again, so a rejected operation cannot leave duplicated drops behind.</p>
 */
public final class OutputTransaction {

    private final List<Reservation> reservations;
    private List<Reservation> committedReservations = List.of();
    private State state = State.PREPARED;

    private OutputTransaction(List<Reservation> reservations) {
        this.reservations = List.copyOf(reservations);
    }

    public static OutputTransaction prepare(List<? extends MaterialSource> destinations,
                                            ResourceRequirements requirements) {
        Objects.requireNonNull(destinations, "destinations");
        Objects.requireNonNull(requirements, "requirements");
        if (destinations.isEmpty() && !requirements.isEmpty()) {
            ResourceRequirement first = requirements.entries().getFirst();
            throw new InsufficientOutputCapacityException(first.specification(), first.amount());
        }

        Map<MaterialSource, Map<BlockSpec, Long>> allocations = new LinkedHashMap<>();
        for (ResourceRequirement requirement : requirements.entries()) {
            long remaining = requirement.amount();
            for (MaterialSource destination : destinations) {
                if (remaining == 0) break;
                long accepted = checkedAmount(destination.insert(requirement.specification(), remaining, true), remaining);
                if (accepted == 0) continue;
                allocations.computeIfAbsent(destination, ignored -> new LinkedHashMap<>())
                        .merge(requirement.specification(), accepted, Math::addExact);
                remaining -= accepted;
            }
            if (remaining != 0) throw new InsufficientOutputCapacityException(requirement.specification(), remaining);
        }

        List<Reservation> reservations = new ArrayList<>();
        allocations.forEach((destination, bySpec) -> bySpec.forEach(
                (specification, amount) -> reservations.add(new Reservation(destination, specification, amount))));
        return new OutputTransaction(reservations);
    }

    public Result commit() {
        ensurePrepared();
        List<Reservation> completed = new ArrayList<>();
        for (Reservation reservation : reservations) {
            long inserted = checkedAmount(reservation.destination.insert(reservation.specification, reservation.amount,
                    false), reservation.amount);
            if (inserted != reservation.amount) {
                boolean rollbackSucceeded = inserted == 0 ||
                        reservation.destination.extract(reservation.specification, inserted, false) == inserted;
                rollbackSucceeded &= rollback(completed);
                state = State.FAILED;
                return new Result(false, rollbackSucceeded, reservation.destination.id(), reservation.specification,
                        reservation.amount - inserted);
            }
            completed.add(reservation);
        }
        state = State.COMMITTED;
        committedReservations = List.copyOf(completed);
        return new Result(true, true, "", null, 0L);
    }

    public boolean compensateCommitted() {
        if (state != State.COMMITTED) return false;
        boolean successful = rollback(committedReservations);
        state = successful ? State.COMPENSATED : State.COMPENSATION_FAILED;
        return successful;
    }

    public State state() {
        return state;
    }

    private boolean rollback(List<Reservation> completed) {
        boolean successful = true;
        for (int index = completed.size() - 1; index >= 0; index--) {
            Reservation reservation = completed.get(index);
            if (reservation.destination.extract(reservation.specification, reservation.amount, false)
                    != reservation.amount) {
                successful = false;
            }
        }
        return successful;
    }

    private void ensurePrepared() {
        if (state != State.PREPARED) throw new IllegalStateException("Output transaction is " + state);
    }

    private static long checkedAmount(long amount, long maximum) {
        if (amount < 0 || amount > maximum) {
            throw new IllegalStateException("Material destination returned an invalid transfer amount: " + amount);
        }
        return amount;
    }

    private record Reservation(MaterialSource destination, BlockSpec specification, long amount) {}

    public enum State {
        PREPARED,
        COMMITTED,
        COMPENSATED,
        COMPENSATION_FAILED,
        FAILED
    }

    public record Result(boolean committed, boolean rollbackSucceeded, String failedDestination,
                         BlockSpec missingSpecification, long missingAmount) {}
}
