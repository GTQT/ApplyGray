package applygray.mattermanipulator.building;

import java.util.Objects;

import applygray.mattermanipulator.inventory.FluidRequirement;
import applygray.mattermanipulator.inventory.ResourceRequirements;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidBlock;

/** Places a captured fluid through Forge's fluid-block contract and reserves millibuckets transactionally. */
public final class FluidBuildingAdapter implements BuildingAdapter {

    private static final int WORLD_UPDATE_FLAGS = 3;

    @Override
    public String id() {
        return "fluid";
    }

    @Override
    public boolean supports(BuildingContext context, BlockPos position, BlockSpec specification) {
        return specification.isFluid();
    }

    @Override
    public PreparedBlockChange prepareApply(BuildingContext context, BlockPos position, BlockSpec specification) {
        if (!specification.isFluid()) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                    "The selected material is not a fluid");
        }
        IBlockState original = validate(context, position);
        if (!original.getBlock().isAir(original, context.world(), position)) {
            if (!context.removalAllowed() || context.removalMode() == applygray.mattermanipulator.state.ManipulatorRemovalMode.NONE) {
                throw new BuildingException(BuildingException.Reason.REMOVAL_NOT_ALLOWED, position,
                        "Replacing the target fluid requires removal permission");
            }
        }
        FluidStack fluid = specification.fluidStack();
        Fluid registered = fluid.getFluid();
        Block block = registered.getBlock();
        if (!(block instanceof IFluidBlock)) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                    "The selected fluid has no placeable Forge fluid block");
        }
        ResourceRequirements outputs = drops(context, position, original);
        return new FluidPlacementChange(context, position, original, specification, fluid, outputs);
    }

    @Override
    public boolean supportsCapture(BuildingContext context, BlockPos position) {
        return false;
    }

    @Override
    public CapturedBlock capture(BuildingContext context, BlockPos position) {
        throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                "Fluid capture is represented by the block-state picker");
    }

    @Override
    public PreparedBlockChange prepareRemove(BuildingContext context, BlockPos position) {
        throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                "Fluid removal is handled by the ordinary block adapter");
    }

    @Override
    public boolean supportsMove(BuildingContext context, BlockPos source, BlockPos target) {
        return false;
    }

    @Override
    public PreparedBlockChange prepareMove(BuildingContext context, BlockPos source, BlockPos target) {
        throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, source,
                "Fluid moves are not supported by the atomic move adapter");
    }

    private static IBlockState validate(BuildingContext context, BlockPos position) {
        if (!context.world().isBlockLoaded(position)) {
            throw new BuildingException(BuildingException.Reason.CHUNK_NOT_LOADED, position,
                    "The target chunk is not loaded");
        }
        if (!context.world().getWorldBorder().contains(position)) {
            throw new BuildingException(BuildingException.Reason.OUTSIDE_WORLD_BORDER, position,
                    "The target is outside the world border");
        }
        EntityPlayer player = context.player();
        if (!context.world().isBlockModifiable(player, position) ||
                !player.canPlayerEdit(position, EnumFacing.UP, context.manipulatorStack())) {
            throw new BuildingException(BuildingException.Reason.PERMISSION_DENIED, position,
                    "The player cannot modify the fluid target");
        }
        TileEntity tile = context.world().getTileEntity(position);
        if (tile != null) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_TILE_ENTITY, position,
                    "A fluid placement cannot replace a TileEntity safely");
        }
        return context.world().getBlockState(position);
    }

    private static ResourceRequirements drops(BuildingContext context, BlockPos position, IBlockState state) {
        if (state.getBlock().isAir(state, context.world(), position)) return ResourceRequirements.empty();
        NonNullList<ItemStack> drops = NonNullList.create();
        state.getBlock().getDrops(drops, context.world(), position, state, 0);
        return ResourceRequirements.fromStacks(drops);
    }

    private static final class FluidPlacementChange implements PreparedBlockChange {

        private final BuildingContext context;
        private final BlockPos position;
        private final IBlockState original;
        private final BlockSpec specification;
        private final FluidStack fluid;
        private final ResourceRequirements outputs;
        private BlockSnapshot snapshot;

        private FluidPlacementChange(BuildingContext context, BlockPos position, IBlockState original,
                                     BlockSpec specification, FluidStack fluid, ResourceRequirements outputs) {
            this.context = Objects.requireNonNull(context, "context");
            this.position = position;
            this.original = original;
            this.specification = specification;
            this.fluid = fluid.copy();
            this.outputs = outputs;
        }

        @Override
        public BlockPos position() { return position; }

        @Override
        public BlockSpec materialCost() { return specification; }

        @Override
        public ResourceRequirements requiredResources() {
            return ResourceRequirements.fluids(new FluidRequirement(fluid, fluid.amount));
        }

        @Override
        public ResourceRequirements producedResources() { return outputs; }

        @Override
        public long energyCost() { return 128L; }

        @Override
        public boolean changesWorld() { return true; }

        @Override
        public void apply() {
            if (!context.world().getBlockState(position).equals(original)) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                        "The fluid target changed after the build was prepared");
            }
            snapshot = BlockSnapshot.getBlockSnapshot(context.world(), position);
            if (!context.world().setBlockState(position, Blocks.AIR.getDefaultState(), WORLD_UPDATE_FLAGS)) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                        "Minecraft rejected clearing the fluid target");
            }
            Block block = fluid.getFluid().getBlock();
            int consumed = ((IFluidBlock) block).place(context.world(), position, fluid.copy(), true);
            if (consumed != fluid.amount) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                        "The fluid block did not consume the reserved amount");
            }
        }

        @Override
        public void rollback() {
            if (snapshot != null) snapshot.restore(true, false);
        }
    }
}
