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
    private final List<FluidReservation> fluidReservations;
    private List<Reservation> committedReservations = List.of();
    private List<FluidReservation> committedFluidReservations = List.of();
    private State state = State.PREPARED;

    private OutputTransaction(List<Reservation> reservations, List<FluidReservation> fluidReservations) {
        this.reservations = List.copyOf(reservations);
        this.fluidReservations = List.copyOf(fluidReservations);
    }

    public static OutputTransaction prepare(List<? extends MaterialSource> destinations,
                                            ResourceRequirements requirements) {
        Objects.requireNonNull(destinations, "destinations");
        Objects.requireNonNull(requirements, "requirements");
        if (destinations.isEmpty() && !requirements.isEmpty()) {
            if (!requirements.entries().isEmpty()) {
                ResourceRequirement first = requirements.entries().getFirst();
                throw new InsufficientOutputCapacityException(first.specification(), first.amount());
            }
            FluidRequirement first = requirements.fluidEntries().getFirst();
            throw new InsufficientOutputCapacityException(first, first.amount());
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
        List<FluidReservation> fluidReservations = new ArrayList<>();
        for (FluidRequirement requirement : requirements.fluidEntries()) {
            long remaining = requirement.amount();
            for (MaterialSource destination : destinations) {
                if (remaining == 0) break;
                long accepted = checkedAmount(destination.insert(requirement.stack((int) Math.min(remaining, Integer.MAX_VALUE)), remaining, true), remaining);
                if (accepted == 0) continue;
                fluidReservations.add(new FluidReservation(destination, requirement, accepted));
                remaining -= accepted;
            }
            if (remaining != 0) throw new InsufficientOutputCapacityException(requirement, remaining);
        }
        return new OutputTransaction(reservations, fluidReservations);
    }

    public static OutputTransaction prepareFluids(List<? extends MaterialSource> destinations,
                                                  ResourceRequirements requirements) {
        if (destinations.isEmpty() && !requirements.fluidEntries().isEmpty()) {
            FluidRequirement first = requirements.fluidEntries().getFirst();
            throw new InsufficientOutputCapacityException(first, first.amount());
        }
        Map<MaterialSource, Map<FluidRequirement, Long>> allocations = new LinkedHashMap<>();
        for (FluidRequirement requirement : requirements.fluidEntries()) {
            long remaining = requirement.amount();
            for (MaterialSource destination : destinations) {
                if (remaining == 0) break;
                long accepted = checkedAmount(destination.insert(requirement.stack((int) Math.min(remaining, Integer.MAX_VALUE)), remaining, true), remaining);
                if (accepted == 0) continue;
                allocations.computeIfAbsent(destination, ignored -> new LinkedHashMap<>()).merge(requirement, accepted, Math::addExact);
                remaining -= accepted;
            }
            if (remaining != 0) throw new InsufficientOutputCapacityException(requirement, remaining);
        }
        List<FluidReservation> reservations = new ArrayList<>();
        allocations.forEach((destination, bySpec) -> bySpec.forEach((requirement, amount) ->
                reservations.add(new FluidReservation(destination, requirement, amount))));
        return new OutputTransaction(List.of(), reservations);
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
        List<FluidReservation> completedFluids = new ArrayList<>();
        for (FluidReservation reservation : fluidReservations) {
            long inserted = checkedAmount(reservation.destination.insert(reservation.requirement.stack((int) Math.min(reservation.amount, Integer.MAX_VALUE)),
                    reservation.amount, false), reservation.amount);
            if (inserted != reservation.amount) {
                boolean rollbackSucceeded = inserted == 0 || reservation.destination.extract(
                        reservation.requirement.stack((int) inserted), inserted, false) == inserted;
                rollbackSucceeded &= rollback(completed) && rollbackFluids(completedFluids);
                state = State.FAILED;
                return new Result(false, rollbackSucceeded, reservation.destination.id(), null,
                        reservation.amount - inserted);
            }
            completedFluids.add(reservation);
        }
        state = State.COMMITTED;
        committedReservations = List.copyOf(completed);
        committedFluidReservations = List.copyOf(completedFluids);
        return new Result(true, true, "", null, 0L);
    }

    public boolean compensateCommitted() {
        if (state != State.COMMITTED) return false;
        boolean successful = rollback(committedReservations) && rollbackFluids(committedFluidReservations);
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
    private record FluidReservation(MaterialSource destination, FluidRequirement requirement, long amount) {}

    private boolean rollbackFluids(List<FluidReservation> completed) {
        boolean successful = true;
        for (int index = completed.size() - 1; index >= 0; index--) {
            FluidReservation reservation = completed.get(index);
            if (reservation.destination.extract(reservation.requirement.stack((int) Math.min(reservation.amount, Integer.MAX_VALUE)),
                    reservation.amount, false) != reservation.amount) successful = false;
        }
        return successful;
    }

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
