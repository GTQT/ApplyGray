package applygray.mattermanipulator.building;

import applygray.mattermanipulator.inventory.ResourceRequirements;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.world.BlockEvent;

/**
 * Safe target-native adapter for ordinary 1.12.2 blocks.
 *
 * <p>It deliberately excludes every TileEntity. A specialised adapter must account for inventories, fluids,
 * configuration, and ownership before such a block can be copied, moved, removed, or replaced.</p>
 */
public final class VanillaBuildingAdapter implements BuildingAdapter {

    private static final double EU_PER_BLOCK = 128.0D;
    private static final double EU_DISTANCE_EXPONENT = 1.25D;
    private static final int WORLD_UPDATE_FLAGS = 3;

    @Override
    public String id() {
        return "vanilla";
    }

    @Override
    public boolean supports(BuildingContext context, BlockPos position, BlockSpec specification) {
        return specification.isAir() || isSafeMaterial(specification);
    }

    @Override
    public boolean supportsCapture(BuildingContext context, BlockPos position) {
        return true;
    }

    public static boolean isSafeMaterial(BlockSpec specification) {
        IBlockState state = stateFor(specification);
        return state != null && !state.getBlock().hasTileEntity(state);
    }

    @Override
    public CapturedBlock capture(BuildingContext context, BlockPos position) {
        IBlockState state = validatePosition(context, position);
        rejectTileEntity(context, position, state);

        BlockSpec specification = BlockSpec.fromState(state);
        if (!isAir(context, position, state) && specification.isAir()) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                    "The source block has no portable item representation");
        }
        return new CapturedBlock(position, specification);
    }

    @Override
    public PreparedBlockChange prepareApply(BuildingContext context, BlockPos position, BlockSpec specification) {
        if (specification.isAir()) return prepareRemove(context, position);

        IBlockState originalState = validatePosition(context, position);
        rejectTileEntity(context, position, originalState);
        IBlockState targetState = stateForOrThrow(specification, position);
        if (originalState == targetState || originalState.equals(targetState)) {
            return new NoOpBlockChange(position);
        }
        if (!canReplace(context, position, originalState)) {
            throw new BuildingException(BuildingException.Reason.REMOVAL_NOT_ALLOWED, position,
                    "The configured removal mode does not permit replacing the target block");
        }
        return new VanillaPlacementChange(context, position, originalState, targetState, specification,
                dropsFor(context, position, originalState));
    }

    @Override
    public PreparedBlockChange prepareRemove(BuildingContext context, BlockPos position) {
        IBlockState originalState = validatePosition(context, position);
        rejectTileEntity(context, position, originalState);
        if (isAir(context, position, originalState)) return new NoOpBlockChange(position);

        if (!context.removalAllowed() ||
                context.removalMode() == applygray.mattermanipulator.state.ManipulatorRemovalMode.NONE) {
            throw new BuildingException(BuildingException.Reason.REMOVAL_NOT_ALLOWED, position,
                    "The configured removal mode does not permit removing the target block");
        }
        if (context.removalMode() == applygray.mattermanipulator.state.ManipulatorRemovalMode.REPLACEABLE &&
                !originalState.getBlock().isReplaceable(context.world(), position)) {
            throw new BuildingException(BuildingException.Reason.REMOVAL_NOT_ALLOWED, position,
                    "The configured removal mode only permits replaceable blocks");
        }

        return new VanillaRemovalChange(context, position, originalState, dropsFor(context, position, originalState));
    }

    @Override
    public boolean supportsMove(BuildingContext context, BlockPos source, BlockPos target) {
        return true;
    }

    @Override
    public PreparedBlockChange prepareMove(BuildingContext context, BlockPos source, BlockPos target) {
        if (source.equals(target)) {
            throw new BuildingException(BuildingException.Reason.OVERLAPPING_MOVE, source,
                    "A move source and target cannot be the same block");
        }

        IBlockState sourceState = validatePosition(context, source);
        IBlockState targetState = validatePosition(context, target);
        rejectTileEntity(context, source, sourceState);
        rejectTileEntity(context, target, targetState);
        if (isAir(context, source, sourceState)) return new NoOpBlockChange(source);
        if (sourceState.getBlockHardness(context.world(), source) < 0.0F) {
            throw new BuildingException(BuildingException.Reason.UNBREAKABLE, source,
                    "The move contains an unbreakable block");
        }
        if (sourceState.equals(targetState)) {
            return new VanillaRemovalChange(context, source, sourceState, dropsFor(context, source, sourceState));
        }
        if (!isAir(context, target, targetState) && targetState.getBlockHardness(context.world(), target) < 0.0F) {
            throw new BuildingException(BuildingException.Reason.UNBREAKABLE, target,
                    "The move target is unbreakable");
        }
        if (!canReplace(context, target, targetState)) {
            throw new BuildingException(BuildingException.Reason.REMOVAL_NOT_ALLOWED, target,
                    "The configured removal mode does not permit replacing the move target");
        }
        return new VanillaMoveChange(context, source, target, sourceState, targetState,
                dropsFor(context, target, targetState));
    }

    private static IBlockState validatePosition(BuildingContext context, BlockPos position) {
        if (!context.world().isBlockLoaded(position)) {
            throw new BuildingException(BuildingException.Reason.CHUNK_NOT_LOADED, position,
                    "The target chunk is not loaded");
        }
        if (!context.world().getWorldBorder().contains(position)) {
            throw new BuildingException(BuildingException.Reason.OUTSIDE_WORLD_BORDER, position,
                    "The target is outside the world border");
        }
        if (!context.world().isBlockModifiable(context.player(), position) ||
                !context.player().canPlayerEdit(position, EnumFacing.UP, context.manipulatorStack())) {
            throw new BuildingException(BuildingException.Reason.PERMISSION_DENIED, position,
                    "The player cannot modify the target block");
        }
        return context.world().getBlockState(position);
    }

    private static void rejectTileEntity(BuildingContext context, BlockPos position, IBlockState state) {
        TileEntity tileEntity = context.world().getTileEntity(position);
        if (tileEntity != null || state.getBlock().hasTileEntity(state)) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_TILE_ENTITY, position,
                    "The target contains a TileEntity and requires a dedicated adapter");
        }
    }

    private static boolean canReplace(BuildingContext context, BlockPos position, IBlockState currentState) {
        if (isAir(context, position, currentState)) return true;
        if (!context.removalAllowed()) return false;
        return switch (context.removalMode()) {
            case NONE -> false;
            case REPLACEABLE -> currentState.getBlock().isReplaceable(context.world(), position);
            case ALL -> true;
        };
    }

    private static boolean isAir(BuildingContext context, BlockPos position, IBlockState state) {
        return state.getBlock().isAir(state, context.world(), position);
    }

    private static ResourceRequirements dropsFor(BuildingContext context, BlockPos position, IBlockState state) {
        if (isAir(context, position, state)) return ResourceRequirements.empty();
        NonNullList<ItemStack> drops = NonNullList.create();
        state.getBlock().getDrops(drops, context.world(), position, state, 0);
        return ResourceRequirements.fromStacks(drops);
    }

    private static IBlockState stateFor(BlockSpec specification) {
        return specification.toBlockState();
    }

    private static IBlockState stateForOrThrow(BlockSpec specification, BlockPos position) {
        IBlockState state = stateFor(specification);
        if (state == null || state.getBlock().hasTileEntity(state)) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                    "The selected material is not a safe vanilla block");
        }
        return state;
    }

    private static long energyCost(BuildingContext context, BlockPos position, IBlockState originalState,
                                   IBlockState targetState) {
        float originalHardness = originalState.getBlockHardness(context.world(), position);
        float targetHardness = targetState.getBlockHardness(context.world(), position);
        double hardness = Math.max(0.0D, Math.max(originalHardness, targetHardness));
        double distance = Math.max(1.0D, context.player().getDistance(position.getX(), position.getY(), position.getZ()));
        double usage = EU_PER_BLOCK * (1.0D + Math.sqrt(hardness)) * Math.pow(distance, EU_DISTANCE_EXPONENT);
        if (context.powerEfficiency()) usage *= 0.5D;
        return usage >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.round(Math.ceil(usage));
    }

    private static final class NoOpBlockChange implements PreparedBlockChange {

        private final BlockPos position;

        private NoOpBlockChange(BlockPos position) {
            this.position = position;
        }

        @Override
        public BlockPos position() {
            return position;
        }

        @Override
        public BlockSpec materialCost() {
            return BlockSpec.air();
        }

        @Override
        public long energyCost() {
            return 0L;
        }

        @Override
        public boolean changesWorld() {
            return false;
        }

        @Override
        public void apply() {}

        @Override
        public void rollback() {}
    }

    private abstract static class VanillaChange implements PreparedBlockChange {

        final BuildingContext context;
        final BlockPos position;
        final IBlockState originalState;
        final ResourceRequirements outputs;
        BlockSnapshot snapshot;

        VanillaChange(BuildingContext context, BlockPos position, IBlockState originalState,
                      ResourceRequirements outputs) {
            this.context = context;
            this.position = position;
            this.originalState = originalState;
            this.outputs = outputs;
        }

        @Override
        public BlockPos position() {
            return position;
        }

        @Override
        public ResourceRequirements producedResources() {
            return outputs;
        }

        @Override
        public boolean changesWorld() {
            return true;
        }

        @Override
        public void rollback() {
            if (snapshot != null) {
                snapshot.restore(true, false);
            } else {
                context.world().setBlockState(position, originalState, WORLD_UPDATE_FLAGS);
            }
        }

        final void verifyOriginalState() {
            if (!context.world().getBlockState(position).equals(originalState)) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                        "The target changed after the build was prepared");
            }
        }
    }

    private static final class VanillaPlacementChange extends VanillaChange {

        private final IBlockState targetState;
        private final BlockSpec materialCost;

        private VanillaPlacementChange(BuildingContext context, BlockPos position, IBlockState originalState,
                                       IBlockState targetState, BlockSpec materialCost, ResourceRequirements outputs) {
            super(context, position, originalState, outputs);
            this.targetState = targetState;
            this.materialCost = materialCost;
        }

        @Override
        public BlockSpec materialCost() {
            return materialCost;
        }

        @Override
        public long energyCost() {
            return VanillaBuildingAdapter.energyCost(context, position, originalState, targetState);
        }

        @Override
        public void apply() {
            verifyOriginalState();
            snapshot = BlockSnapshot.getBlockSnapshot(context.world(), position);
            // Vanilla's mayPlace check rejects occupied positions. Replacement permission was validated during
            // preparation, so clear the old block first and run the placement check against the actual post-removal
            // world state. BuildTransaction restores the snapshot if any later step fails.
            if (!isAir(context, position, originalState) &&
                    !context.world().setBlockState(position, Blocks.AIR.getDefaultState(), WORLD_UPDATE_FLAGS)) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                        "Minecraft rejected removal of the replaced block");
            }
            if (!context.world().mayPlace(targetState.getBlock(), position, false, EnumFacing.UP, context.player())) {
                rollback();
                throw new BuildingException(BuildingException.Reason.CANNOT_PLACE, position,
                        "The target block cannot be placed after removing the original block");
            }
            if (!context.world().setBlockState(position, targetState, WORLD_UPDATE_FLAGS)) {
                rollback();
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                        "Minecraft rejected the requested block state");
            }
            targetState.getBlock().onBlockPlacedBy(context.world(), position, targetState, context.player(),
                    materialCost.toStack());
            if (context.world().getTileEntity(position) != null) {
                rollback();
                throw new BuildingException(BuildingException.Reason.UNSUPPORTED_TILE_ENTITY, position,
                        "The placed block created a TileEntity and requires a dedicated adapter");
            }

            if (BuildingEventHooks.isPlayerPlaceCanceled(context, snapshot)) {
                rollback();
                throw new BuildingException(BuildingException.Reason.PERMISSION_DENIED, position,
                        "A protection handler denied the block placement");
            }
        }
    }

    private static final class VanillaRemovalChange extends VanillaChange {

        private VanillaRemovalChange(BuildingContext context, BlockPos position, IBlockState originalState,
                                     ResourceRequirements outputs) {
            super(context, position, originalState, outputs);
        }

        @Override
        public BlockSpec materialCost() {
            return BlockSpec.air();
        }

        @Override
        public long energyCost() {
            return VanillaBuildingAdapter.energyCost(context, position, originalState, Blocks.AIR.getDefaultState());
        }

        @Override
        public void apply() {
            verifyOriginalState();
            BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(context.world(), position, originalState,
                    context.player());
            if (MinecraftForge.EVENT_BUS.post(event)) {
                throw new BuildingException(BuildingException.Reason.PERMISSION_DENIED, position,
                        "A protection handler denied the block removal");
            }

            snapshot = BlockSnapshot.getBlockSnapshot(context.world(), position);
            originalState.getBlock().onBlockHarvested(context.world(), position, originalState, context.player());
            if (!context.world().setBlockState(position, Blocks.AIR.getDefaultState(), WORLD_UPDATE_FLAGS)) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                        "Minecraft rejected the requested block removal");
            }
        }
    }

    private static final class VanillaMoveChange implements PreparedBlockChange {

        private final BuildingContext context;
        private final BlockPos source;
        private final BlockPos target;
        private final IBlockState sourceState;
        private final IBlockState targetState;
        private final ResourceRequirements outputs;
        private BlockSnapshot sourceSnapshot;
        private BlockSnapshot targetSnapshot;

        private VanillaMoveChange(BuildingContext context, BlockPos source, BlockPos target, IBlockState sourceState,
                                  IBlockState targetState, ResourceRequirements outputs) {
            this.context = context;
            this.source = source;
            this.target = target;
            this.sourceState = sourceState;
            this.targetState = targetState;
            this.outputs = outputs;
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
        public ResourceRequirements producedResources() {
            return outputs;
        }

        @Override
        public long energyCost() {
            long sourceCost = VanillaBuildingAdapter.energyCost(context, source, sourceState,
                    Blocks.AIR.getDefaultState());
            long targetCost = isAir(context, target, targetState) ? 0L :
                    VanillaBuildingAdapter.energyCost(context, target, targetState, Blocks.AIR.getDefaultState());
            return Long.MAX_VALUE - sourceCost < targetCost ? Long.MAX_VALUE : sourceCost + targetCost;
        }

        @Override
        public boolean changesWorld() {
            return true;
        }

        @Override
        public void apply() {
            verifyOriginalState(source, sourceState);
            verifyOriginalState(target, targetState);
            if (MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(context.world(), source, sourceState,
                    context.player()))) {
                throw new BuildingException(BuildingException.Reason.PERMISSION_DENIED, source,
                        "A protection handler denied the source move");
            }
            if (!isAir(context, target, targetState) && MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(
                    context.world(), target, targetState, context.player()))) {
                throw new BuildingException(BuildingException.Reason.PERMISSION_DENIED, target,
                        "A protection handler denied the target move");
            }

            sourceSnapshot = BlockSnapshot.getBlockSnapshot(context.world(), source);
            targetSnapshot = BlockSnapshot.getBlockSnapshot(context.world(), target);
            if (!context.world().setBlockState(source, Blocks.AIR.getDefaultState(), WORLD_UPDATE_FLAGS)) {
                rollback();
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, source,
                        "Minecraft rejected removal of the move source");
            }
            if (!isAir(context, target, targetState) &&
                    !context.world().setBlockState(target, Blocks.AIR.getDefaultState(), WORLD_UPDATE_FLAGS)) {
                rollback();
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, target,
                        "Minecraft rejected removal of the move target");
            }
            if (!context.world().mayPlace(sourceState.getBlock(), target, false, EnumFacing.UP, context.player())) {
                rollback();
                throw new BuildingException(BuildingException.Reason.CANNOT_PLACE, target,
                        "The source block cannot be placed after clearing the move target");
            }
            if (!context.world().setBlockState(target, sourceState, WORLD_UPDATE_FLAGS)) {
                rollback();
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, target,
                        "Minecraft rejected the move target state");
            }
            if (BuildingEventHooks.isPlayerPlaceCanceled(context, targetSnapshot)) {
                rollback();
                throw new BuildingException(BuildingException.Reason.PERMISSION_DENIED, target,
                        "A protection handler denied the move target");
            }
        }

        @Override
        public void rollback() {
            if (targetSnapshot != null) targetSnapshot.restore(true, false);
            if (sourceSnapshot != null) sourceSnapshot.restore(true, false);
        }

        private void verifyOriginalState(BlockPos position, IBlockState expected) {
            if (!context.world().getBlockState(position).equals(expected)) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                        "A move source or target changed after the move was prepared");
            }
        }
    }
}
