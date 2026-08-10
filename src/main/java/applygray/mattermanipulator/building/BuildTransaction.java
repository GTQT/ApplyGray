package applygray.mattermanipulator.building;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import applygray.mattermanipulator.inventory.MaterialSource;
import applygray.mattermanipulator.inventory.EnergyTransaction;
import applygray.mattermanipulator.inventory.OutputTransaction;
import applygray.mattermanipulator.inventory.PowerSource;
import applygray.mattermanipulator.inventory.ResourceRequirement;
import applygray.mattermanipulator.inventory.ResourceRequirements;
import applygray.mattermanipulator.inventory.ResourceTransaction;

/**
 * Commits resources before applying prepared world changes and compensates both sides on failure.
 *
 * <p>One transaction is intentionally bounded to a single build batch. The future queued executor uses the tier's
 * batch limit to avoid holding a massive world rollback journal on the server thread.</p>
 */
public final class BuildTransaction {

    private final List<PreparedBlockChange> changes;
    private final ResourceTransaction resources;
    private final OutputTransaction outputs;
    private final EnergyTransaction energy;
    private State state = State.PREPARED;

    private BuildTransaction(List<PreparedBlockChange> changes, ResourceTransaction resources,
                             OutputTransaction outputs, EnergyTransaction energy) {
        this.changes = List.copyOf(changes);
        this.resources = resources;
        this.outputs = outputs;
        this.energy = energy;
    }

    public static BuildTransaction prepare(List<? extends PreparedBlockChange> changes,
                                           List<? extends MaterialSource> sources) {
        Objects.requireNonNull(changes, "changes");
        Objects.requireNonNull(sources, "sources");

        List<ResourceRequirement> requirements = new ArrayList<>();
        List<ResourceRequirement> outputRequirements = new ArrayList<>();
        for (PreparedBlockChange change : changes) {
            Objects.requireNonNull(change, "changes must not contain null");
            if (!change.changesWorld()) continue;
            requirements.addAll(change.requiredResources().entries());
        }
        for (PreparedBlockChange change : changes) {
            if (!change.changesWorld()) continue;
            outputRequirements.addAll(change.producedResources().entries());
        }
        ResourceTransaction resources = ResourceTransaction.prepare(sources,
                ResourceRequirements.of(requirements.toArray(ResourceRequirement[]::new)));
        OutputTransaction outputs = OutputTransaction.prepare(sources,
                ResourceRequirements.of(outputRequirements.toArray(ResourceRequirement[]::new)));
        return new BuildTransaction(new ArrayList<>(changes), resources, outputs, EnergyTransaction.none());
    }

    public static BuildTransaction prepare(List<? extends PreparedBlockChange> changes,
                                           List<? extends MaterialSource> sources, PowerSource powerSource) {
        BuildTransaction transaction = prepare(changes, sources);
        long energyCost = transaction.changes.stream().filter(PreparedBlockChange::changesWorld)
                .mapToLong(PreparedBlockChange::energyCost).reduce(0L, Math::addExact);
        return new BuildTransaction(transaction.changes, transaction.resources, transaction.outputs,
                EnergyTransaction.prepare(powerSource, energyCost));
    }

    public Result execute() {
        if (state != State.PREPARED) throw new IllegalStateException("Build transaction is " + state);

        if (!energy.commit()) {
            state = State.ENERGY_FAILURE;
            return new Result(state, false, false, true, true, false, null, energy.state().toString());
        }

        ResourceTransaction.Result resourceResult = resources.commit();
        if (!resourceResult.committed()) {
            boolean energyCompensationSucceeded = energy.compensateCommitted();
            state = State.RESOURCE_FAILURE;
            return new Result(state, false, false, resourceResult.rollbackSucceeded(), true,
                    energyCompensationSucceeded, null, resourceResult.failedSource());
        }

        OutputTransaction.Result outputResult = outputs.commit();
        if (!outputResult.committed()) {
            boolean resourceCompensationSucceeded = resources.compensateCommitted();
            boolean energyCompensationSucceeded = energy.compensateCommitted();
            state = State.OUTPUT_FAILURE;
            return new Result(state, false, false, resourceCompensationSucceeded, outputResult.rollbackSucceeded(),
                    energyCompensationSucceeded, null, outputResult.failedDestination());
        }

        List<PreparedBlockChange> applied = new ArrayList<>();
        try {
            for (PreparedBlockChange change : changes) {
                if (!change.changesWorld()) continue;
                applied.add(change);
                change.apply();
            }
            state = State.COMMITTED;
            return new Result(state, true, true, true, true, true, null, "");
        } catch (RuntimeException exception) {
            boolean worldRollbackSucceeded = rollbackWorld(applied);
            boolean outputCompensationSucceeded = outputs.compensateCommitted();
            boolean resourceCompensationSucceeded = resources.compensateCommitted();
            boolean energyCompensationSucceeded = energy.compensateCommitted();
            state = worldRollbackSucceeded && resourceCompensationSucceeded && outputCompensationSucceeded &&
                    energyCompensationSucceeded
                    ? State.ROLLED_BACK
                    : State.ROLLBACK_FAILED;
            return new Result(state, false, worldRollbackSucceeded, resourceCompensationSucceeded,
                    outputCompensationSucceeded, energyCompensationSucceeded, exception, "");
        }
    }

    public State state() {
        return state;
    }

    private static boolean rollbackWorld(List<PreparedBlockChange> applied) {
        boolean successful = true;
        for (int index = applied.size() - 1; index >= 0; index--) {
            try {
                applied.get(index).rollback();
            } catch (RuntimeException ignored) {
                successful = false;
            }
        }
        return successful;
    }

    public enum State {
        PREPARED,
        COMMITTED,
        ENERGY_FAILURE,
        RESOURCE_FAILURE,
        OUTPUT_FAILURE,
        ROLLED_BACK,
        ROLLBACK_FAILED
    }

    public record Result(State state, boolean committed, boolean worldRollbackSucceeded,
                         boolean resourceRollbackSucceeded, boolean outputRollbackSucceeded,
                         boolean energyRollbackSucceeded, RuntimeException failure, String failedSource) {}
}
