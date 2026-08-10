package applygray.mattermanipulator.building;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

import applygray.mattermanipulator.planning.BoundExchangeOperation;
import applygray.mattermanipulator.planning.BoundExchangePlan;
import applygray.mattermanipulator.planning.GeometryPlanException;
import applygray.mattermanipulator.planning.GeometryPlanner;
import applygray.mattermanipulator.state.ManipulatorCapability;
import applygray.mattermanipulator.state.ManipulatorLocation;
import applygray.mattermanipulator.state.ManipulatorUpgrade;

import net.minecraft.util.math.BlockPos;

/** Captures an exact whitelist match for each target block before applying deterministic exchange replacements. */
public final class ExchangeBuildService {

    private final BuildingAdapterRegistry adapters;

    public ExchangeBuildService(BuildingAdapterRegistry adapters) {
        this.adapters = Objects.requireNonNull(adapters, "adapters");
    }

    public BoundExchangePlan createPlan(ExchangeBuildRequest request) {
        Objects.requireNonNull(request, "request");
        validateRequest(request);
        ManipulatorLocation a = request.state().selectionA();
        ManipulatorLocation b = request.state().selectionB();
        validateRegionLimit(a.position(), b.position());
        validateRange(request, a.position(), b.position());

        BuildingContext context = context(request);
        Random random = new Random(31L * request.state().hashCode() + a.hashCode() + b.hashCode());
        List<BoundExchangeOperation> operations = new ArrayList<>();
        forEachPosition(a.position(), b.position(), position -> {
            CapturedBlock captured = adapters.capture(context, position);
            if (request.state().exchangeWhitelist().contains(captured.specification())) {
                operations.add(new BoundExchangeOperation(position,
                        request.state().exchangeReplacement().select(random)));
            }
        });
        return new BoundExchangePlan(operations);
    }

    public ExchangeBuildResult executeNextBatch(ExchangeBuildRequest request, BoundExchangePlan plan, int startIndex) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(plan, "plan");
        if (request.player().world.isRemote) throw new IllegalArgumentException("Exchanges must execute on the server");
        if (startIndex < 0 || startIndex > plan.operations().size()) {
            throw new IllegalArgumentException("startIndex is outside the exchange plan");
        }

        int nextOperationIndex = Math.min(plan.operations().size(), startIndex + request.tier().blocksPerBatch());
        BuildingContext context = context(request);
        List<PreparedBlockChange> changes = new ArrayList<>(nextOperationIndex - startIndex);
        for (int index = startIndex; index < nextOperationIndex; index++) {
            BoundExchangeOperation operation = plan.operations().get(index);
            changes.add(adapters.prepareApply(context, operation.position(), operation.replacement()));
        }
        BuildTransaction transaction = BuildTransaction.prepare(changes, request.materialSources(), request.powerSource());
        return new ExchangeBuildResult(plan, startIndex, nextOperationIndex, transaction.execute());
    }

    private static BuildingContext context(ExchangeBuildRequest request) {
        return new BuildingContext(request.player().world, request.player(), request.manipulatorStack(), request.hand(),
                request.state().removalMode(), request.state().hasUpgrade(ManipulatorUpgrade.POWER_EFFICIENCY),
                request.tier().hasCapability(ManipulatorCapability.REMOVAL) || request.state().hasUpgrade(
                        ManipulatorUpgrade.MINING));
    }

    private static void validateRequest(ExchangeBuildRequest request) {
        if (request.player().world.isRemote) throw new IllegalArgumentException("Exchanges must execute on the server");
        if (!request.tier().hasCapability(ManipulatorCapability.EXCHANGING)) {
            throw new IllegalArgumentException("The selected Matter Manipulator tier cannot exchange blocks");
        }
        ManipulatorLocation a = request.state().selectionA();
        ManipulatorLocation b = request.state().selectionB();
        if (a == null || b == null) {
            throw new GeometryPlanException(GeometryPlanException.Reason.MISSING_SELECTION,
                    "Exchanging needs two corners");
        }
        if (a.dimension() != b.dimension() || a.dimension() != request.player().world.provider.getDimension()) {
            throw new GeometryPlanException(GeometryPlanException.Reason.CROSS_DIMENSION_SELECTION,
                    "The exchange region must be in the player's current dimension");
        }
    }

    private static void validateRegionLimit(BlockPos a, BlockPos b) {
        long x = span(a.getX(), b.getX());
        long y = span(a.getY(), b.getY());
        long z = span(a.getZ(), b.getZ());
        if (x > GeometryPlanner.DEFAULT_MAX_OPERATION_COUNT || y > GeometryPlanner.DEFAULT_MAX_OPERATION_COUNT ||
                z > GeometryPlanner.DEFAULT_MAX_OPERATION_COUNT ||
                x > GeometryPlanner.DEFAULT_MAX_OPERATION_COUNT / y ||
                x * y > GeometryPlanner.DEFAULT_MAX_OPERATION_COUNT / z) {
            throw new GeometryPlanException(GeometryPlanException.Reason.OPERATION_LIMIT_EXCEEDED,
                    "The exchange operation exceeds its maximum block count");
        }
    }

    private static void validateRange(ExchangeBuildRequest request, BlockPos a, BlockPos b) {
        int maximumRange = request.tier().maximumRange();
        if (maximumRange < 0) return;
        long maximumDistanceSquared = (long) maximumRange * maximumRange;
        BlockPos player = new BlockPos(request.player());
        forEachPosition(a, b, position -> {
            if (player.distanceSq(position) > maximumDistanceSquared) {
                throw new GeometryPlanException(GeometryPlanException.Reason.OPERATION_LIMIT_EXCEEDED,
                        "The exchange operation exceeds the manipulator range");
            }
        });
    }

    private static long span(int first, int second) {
        return Math.abs((long) first - second) + 1L;
    }

    private static void forEachPosition(BlockPos a, BlockPos b, PositionConsumer consumer) {
        long minX = Math.min(a.getX(), b.getX());
        long minY = Math.min(a.getY(), b.getY());
        long minZ = Math.min(a.getZ(), b.getZ());
        long maxX = Math.max(a.getX(), b.getX());
        long maxY = Math.max(a.getY(), b.getY());
        long maxZ = Math.max(a.getZ(), b.getZ());
        for (long y = minY; y <= maxY; y++) {
            for (long x = minX; x <= maxX; x++) {
                for (long z = minZ; z <= maxZ; z++) {
                    consumer.accept(new BlockPos(Math.toIntExact(x), Math.toIntExact(y), Math.toIntExact(z)));
                }
            }
        }
    }

    @FunctionalInterface
    private interface PositionConsumer {

        void accept(BlockPos position);
    }
}
