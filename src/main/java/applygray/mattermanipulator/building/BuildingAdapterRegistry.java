package applygray.mattermanipulator.building;

import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

import applygray.ApplyGrayMod;
import applygray.mattermanipulator.inventory.ResourceRequirements;
import applygray.mattermanipulator.state.ManipulatorTransform;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;

/** Ordered target-adapter registry. Specialised GT and AE2 adapters are registered before the vanilla fallback. */
public final class BuildingAdapterRegistry {

    private final List<BuildingAdapter> adapters;

    public BuildingAdapterRegistry(List<? extends BuildingAdapter> adapters) {
        this.adapters = List.copyOf(adapters);
        if (this.adapters.isEmpty()) throw new IllegalArgumentException("at least one building adapter is required");
    }

    public static BuildingAdapterRegistry vanillaOnly() {
        return new BuildingAdapterRegistry(List.of(new VanillaBuildingAdapter()));
    }

    public PreparedBlockChange prepareApply(BuildingContext context, BlockPos position, BlockSpec specification) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(specification, "specification");

        if (specification.isAir()) {
            BuildingAdapter targetAdapter = captureAdapter(context, position);
            if (targetAdapter != null) return targetAdapter.prepareRemove(context, position);
        }

        for (BuildingAdapter adapter : adapters) {
            if (!adapter.supports(context, position, specification)) continue;
            if (adapter.absorbsTargetContents(context, position)) {
                return adapter.prepareApply(context, position, specification);
            }
            // The placing adapter cannot account for the tile already standing here, so the adapter that owns it
            // hands its contents back through the transaction before the placement runs.
            BuildingAdapter targetAdapter = captureAdapter(context, position);
            if (targetAdapter != null && targetAdapter != adapter) {
                PreparedBlockChange removal = targetAdapter.prepareRemove(context, position);
                PreparedBlockChange placement = adapter.prepareApplyAfterTargetRemoval(context, position,
                        specification);
                return new SpecializedTargetReplacement(position, removal, placement);
            }
            logTargetAdapterMiss(context, position, specification);
            return adapter.prepareApply(context, position, specification);
        }
        throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                "No target building adapter supports " + specification.sortKey());
    }

    /** Records why no adapter claimed a tile-backed target, so the rejection that follows can be traced. */
    private void logTargetAdapterMiss(BuildingContext context, BlockPos position, BlockSpec specification) {
        if (!BuildingAdapter.hasTileEntity(context, position)) return;
        TileEntity targetTile = context.world().getTileEntity(position);
        StringJoiner matches = new StringJoiner(", ");
        for (BuildingAdapter candidate : adapters) {
            if (candidate instanceof VanillaBuildingAdapter) continue;
            matches.add(candidate.id() + "=" + candidate.supportsCapture(context, position));
        }
        ApplyGrayMod.LOGGER.warn("Matter Manipulator target adapter miss at {}: tileClass={}, block={}, "
                        + "candidates=[{}], selectedMaterial={}",
                position, targetTile == null ? "<null>" : targetTile.getClass().getName(),
                context.world().getBlockState(position).getBlock().getRegistryName(), matches,
                specification.sortKey());
    }

    public CapturedBlock capture(BuildingContext context, BlockPos position) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(position, "position");

        for (BuildingAdapter adapter : adapters) {
            if (adapter.supportsCapture(context, position)) {
                return adapter.capture(context, position).withAdapterId(adapter.id());
            }
        }
        throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                "No target building adapter can capture this block");
    }

    public CapturedBlock transformCapture(CapturedBlock captured, ManipulatorTransform transform) {
        Objects.requireNonNull(captured, "captured");
        Objects.requireNonNull(transform, "transform");
        return adapterFor(captured).transformCapture(captured, transform);
    }

    public PreparedBlockChange prepareApply(BuildingContext context, BlockPos position, CapturedBlock captured) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(captured, "captured");
        return adapterFor(captured).prepareApplyCaptured(context, position, captured);
    }

    public PreparedBlockChange prepareRemove(BuildingContext context, BlockPos position) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(position, "position");

        for (BuildingAdapter adapter : adapters) {
            if (adapter.supportsCapture(context, position)) return adapter.prepareRemove(context, position);
        }
        throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                "No target building adapter can remove this block");
    }

    public PreparedBlockChange prepareMove(BuildingContext context, BlockPos source, BlockPos target) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");

        // Resolve a live specialized source first so a failed move reports the source adapter's actual constraint
        // instead of falling through to VanillaBuildingAdapter and producing a misleading TileEntity rejection.
        for (BuildingAdapter adapter : adapters) {
            if (!(adapter instanceof VanillaBuildingAdapter) && adapter.supportsCapture(context, source)) {
                if (!isAir(context, target)) {
                    PreparedBlockChange targetRemoval = prepareRemove(context, target);
                    PreparedBlockChange sourceMove = adapter.prepareMoveAfterTargetRemoval(context, source, target);
                    return new TargetClearingMoveChange(source, target, targetRemoval, sourceMove);
                }
                return adapter.prepareMove(context, source, target);
            }
        }
        for (BuildingAdapter adapter : adapters) {
            if (adapter.supportsMove(context, source, target)) return adapter.prepareMove(context, source, target);
        }
        throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, source,
                "No target building adapter can move this block");
    }

    private static boolean isAir(BuildingContext context, BlockPos position) {
        IBlockState state = context.world().getBlockState(position);
        return state.getBlock().isAir(state, context.world(), position);
    }

    private BuildingAdapter adapterFor(CapturedBlock captured) {
        for (BuildingAdapter adapter : adapters) {
            if (adapter.id().equals(captured.adapterId())) return adapter;
        }
        throw new IllegalArgumentException("The captured block references an unavailable adapter: " + captured.adapterId());
    }

    private BuildingAdapter captureAdapter(BuildingContext context, BlockPos position) {
        for (BuildingAdapter adapter : adapters) {
            if (!(adapter instanceof VanillaBuildingAdapter) && adapter.supportsCapture(context, position)) return adapter;
        }
        return null;
    }

    private static final class SpecializedTargetReplacement implements PreparedBlockChange {
        private final BlockPos position;
        private final PreparedBlockChange removal;
        private final PreparedBlockChange placement;

        private SpecializedTargetReplacement(BlockPos position, PreparedBlockChange removal,
                                             PreparedBlockChange placement) {
            this.position = position;
            this.removal = removal;
            this.placement = placement;
        }

        @Override
        public BlockPos position() {
            return position;
        }

        @Override
        public BlockSpec materialCost() {
            return placement.materialCost();
        }

        @Override
        public ResourceRequirements requiredResources() {
            return ResourceRequirements.combine(removal.requiredResources(), placement.requiredResources());
        }

        @Override
        public ResourceRequirements producedResources() {
            return ResourceRequirements.combine(removal.producedResources(), placement.producedResources());
        }

        @Override
        public long energyCost() {
            return Math.addExact(removal.energyCost(), placement.energyCost());
        }

        @Override
        public boolean changesWorld() {
            return removal.changesWorld() || placement.changesWorld();
        }

        @Override
        public void apply() {
            removal.apply();
            placement.apply();
        }

        @Override
        public void rollback() {
            placement.rollback();
            removal.rollback();
        }
    }

    private static final class TargetClearingMoveChange implements PreparedBlockChange {
        private final BlockPos source;
        private final BlockPos target;
        private final PreparedBlockChange targetRemoval;
        private final PreparedBlockChange sourceMove;

        private TargetClearingMoveChange(BlockPos source, BlockPos target, PreparedBlockChange targetRemoval,
                                         PreparedBlockChange sourceMove) {
            this.source = source;
            this.target = target;
            this.targetRemoval = targetRemoval;
            this.sourceMove = sourceMove;
        }

        @Override
        public BlockPos position() {
            return source;
        }

        @Override
        public BlockSpec materialCost() {
            return BlockSpec.air();
        }

        @Override
        public ResourceRequirements requiredResources() {
            return ResourceRequirements.combine(targetRemoval.requiredResources(), sourceMove.requiredResources());
        }

        @Override
        public ResourceRequirements producedResources() {
            return ResourceRequirements.combine(targetRemoval.producedResources(), sourceMove.producedResources());
        }

        @Override
        public long energyCost() {
            return Math.addExact(targetRemoval.energyCost(), sourceMove.energyCost());
        }

        @Override
        public boolean changesWorld() {
            return targetRemoval.changesWorld() || sourceMove.changesWorld();
        }

        @Override
        public void apply() {
            targetRemoval.apply();
            sourceMove.apply();
        }

        @Override
        public void rollback() {
            sourceMove.rollback();
            targetRemoval.rollback();
        }
    }
}
