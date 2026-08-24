package applygray.mattermanipulator.integration.gregtech;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import applygray.mattermanipulator.building.BlockSpec;
import applygray.mattermanipulator.building.BuildingAdapter;
import applygray.mattermanipulator.building.BuildingContext;
import applygray.mattermanipulator.building.BuildingException;
import applygray.mattermanipulator.building.CapturedBlock;
import applygray.mattermanipulator.building.CapturedBlockData;
import applygray.mattermanipulator.building.PreparedBlockChange;
import applygray.mattermanipulator.inventory.ResourceRequirement;
import applygray.mattermanipulator.inventory.ResourceRequirements;
import applygray.mattermanipulator.state.ManipulatorRemovalMode;
import applygray.mattermanipulator.state.ManipulatorTransform;
import applygray.common.ApplyGrayMetaTileEntities;

import gregtech.api.block.machines.BlockMachine;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntityHolder;
import gregtech.api.mattermanipulator.ISmartCopyLinkable;
import gregtech.api.mattermanipulator.SmartCopyLink;
import gregtech.api.pipenet.block.BlockPipe;
import gregtech.api.pipenet.tile.IPipeTile;
import gregtech.api.pipenet.tile.TileEntityPipeBase;
import gregtech.api.unification.material.Material;
import gregtech.common.blocks.MetaBlocks;
import gregtech.common.pipelike.fluidpipe.tile.TileEntityFluidPipeTickable;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityMEPatternProvider;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityMEPatternProviderProxy;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidTankProperties;
import net.minecraftforge.items.IItemHandler;

/**
 * Target-native GregTech adapter for portable MTE configuration and empty pipe networks.
 *
 * <p>The adapter accepts only state that GregTech itself exposes through item-stack data or pipe APIs. It explicitly
 * rejects live inventories, fluids, covers, and MTE-specific extra drops instead of serializing raw tile NBT. That
 * keeps a copy, removal, or move operation lossless within the supported state contract.</p>
 */
public final class GregTechBuildingAdapter implements BuildingAdapter {

    private static final String ID = "gregtech";
    private static final int WORLD_UPDATE_FLAGS = 3;
    private static final long BASE_EU_PER_COMPONENT = 1_000L;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean supports(BuildingContext context, BlockPos position, BlockSpec specification) {
        return typeFor(specification.toStack()) != null;
    }

    @Override
    public PreparedBlockChange prepareApply(BuildingContext context, BlockPos position, BlockSpec specification) {
        PortableType type = typeFor(specification.toStack());
        if (type == null) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                    "The selected material is not a GregTech machine or pipe");
        }
        PortableData data = switch (type) {
            case MTE -> MteData.forMaterial(specification.toStack());
            case PIPE -> PipeData.forMaterial(specification.toStack());
        };
        return preparePlacement(context, position, data);
    }

    @Override
    public boolean supportsCapture(BuildingContext context, BlockPos position) {
        return dataForTile(context, position, context.smartCopyEnabled()) != null;
    }

    @Override
    public CapturedBlock capture(BuildingContext context, BlockPos position) {
        validateEditable(context, position);
        PortableData data = requirePortableData(context, position, context.smartCopyEnabled());
        return new CapturedBlock(position, data.primaryMaterial(), "", data);
    }

    @Override
    public CapturedBlock transformCapture(CapturedBlock captured, ManipulatorTransform transform) {
        Objects.requireNonNull(captured, "captured");
        if (!(captured.data() instanceof PortableData data)) {
            throw new IllegalArgumentException("GregTech capture has incompatible data");
        }
        return new CapturedBlock(captured.source(), captured.specification(), captured.adapterId(),
                data.transformed(transform));
    }

    @Override
    public PreparedBlockChange prepareApplyCaptured(BuildingContext context, BlockPos position, CapturedBlock captured) {
        if (!(captured.data() instanceof PortableData data)) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                    "The captured block does not contain portable GregTech state");
        }
        return preparePlacement(context, position, data);
    }

    @Override
    public PreparedBlockChange prepareRemove(BuildingContext context, BlockPos position) {
        IBlockState originalState = validateEditable(context, position);
        requireRemoval(context, position, originalState);
        return new GregTechRemovalChange(context, position, originalState, requirePortableData(context, position, false));
    }

    @Override
    public boolean supportsMove(BuildingContext context, BlockPos source, BlockPos target) {
        if (dataForTile(context, source, false) == null) return false;
        IBlockState targetState = context.world().getBlockState(target);
        return isAir(context, target, targetState);
    }

    @Override
    public PreparedBlockChange prepareMove(BuildingContext context, BlockPos source, BlockPos target) {
        if (source.equals(target)) {
            throw new BuildingException(BuildingException.Reason.OVERLAPPING_MOVE, source,
                    "A move source and target cannot be the same block");
        }
        IBlockState sourceState = validateEditable(context, source);
        IBlockState targetState = validateEditable(context, target);
        PortableData data = requirePortableData(context, source, false);
        if (!isAir(context, target, targetState)) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, target,
                    "Moving a GregTech block currently requires an empty destination");
        }
        if (!context.world().checkNoEntityCollision(new AxisAlignedBB(target))) {
            throw new BuildingException(BuildingException.Reason.CANNOT_PLACE, target,
                    "An entity blocks the GregTech destination");
        }
        return new GregTechMoveChange(context, source, target, sourceState, targetState, data);
    }

    private static PreparedBlockChange preparePlacement(BuildingContext context, BlockPos position, PortableData data) {
        IBlockState originalState = validateEditable(context, position);
        TargetContents target = inspectTarget(context, position, originalState);
        requireReplacement(context, position, originalState);
        if (!context.world().checkNoEntityCollision(new AxisAlignedBB(position))) {
            throw new BuildingException(BuildingException.Reason.CANNOT_PLACE, position,
                    "An entity blocks the GregTech destination");
        }
        return new GregTechPlacementChange(context, position, originalState, target, data);
    }

    private static TargetContents inspectTarget(BuildingContext context, BlockPos position, IBlockState state) {
        PortableData portable = dataForTile(context, position, false);
        if (portable != null) return new TargetContents(portable.producedResources(), portable);

        TileEntity tile = context.world().getTileEntity(position);
        if (tile != null || state.getBlock().hasTileEntity(state)) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_TILE_ENTITY, position,
                    "The target TileEntity requires its own Matter Manipulator adapter");
        }
        if (isAir(context, position, state)) return TargetContents.empty();

        NonNullList<ItemStack> drops = NonNullList.create();
        state.getBlock().getDrops(drops, context.world(), position, state, 0);
        return new TargetContents(ResourceRequirements.fromStacks(drops), null);
    }

    private static PortableData requirePortableData(BuildingContext context, BlockPos position,
                                                    boolean smartCopySource) {
        PortableData data = dataForTile(context, position, smartCopySource);
        if (data == null) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                    "The target is not a supported GregTech machine or pipe");
        }
        return data;
    }

    private static PortableData dataForTile(BuildingContext context, BlockPos position, boolean smartCopySource) {
        TileEntity tile = context.world().getTileEntity(position);
        if (tile instanceof MetaTileEntityHolder holder && holder.getMetaTileEntity() != null) {
            return MteData.capture(context, position, holder, smartCopySource);
        }
        if (tile instanceof IPipeTile<?, ?> pipe) {
            return PipeData.capture(context, position, pipe);
        }
        return null;
    }

    private static IBlockState validateEditable(BuildingContext context, BlockPos position) {
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

    private static void requireRemoval(BuildingContext context, BlockPos position, IBlockState state) {
        if (!context.removalAllowed() || context.removalMode() == ManipulatorRemovalMode.NONE) {
            throw new BuildingException(BuildingException.Reason.REMOVAL_NOT_ALLOWED, position,
                    "The configured removal mode does not permit removing the target block");
        }
        if (context.removalMode() == ManipulatorRemovalMode.REPLACEABLE &&
                !state.getBlock().isReplaceable(context.world(), position)) {
            throw new BuildingException(BuildingException.Reason.REMOVAL_NOT_ALLOWED, position,
                    "The configured removal mode only permits replaceable blocks");
        }
    }

    private static void requireReplacement(BuildingContext context, BlockPos position, IBlockState state) {
        if (!isAir(context, position, state)) requireRemoval(context, position, state);
    }

    private static boolean isAir(BuildingContext context, BlockPos position, IBlockState state) {
        return state.getBlock().isAir(state, context.world(), position);
    }

    private static PortableType typeFor(ItemStack stack) {
        if (stack.isEmpty()) return null;
        Block block = Block.getBlockFromItem(stack.getItem());
        if (block instanceof BlockMachine) return PortableType.MTE;
        if (block instanceof BlockPipe<?, ?, ?>) return PortableType.PIPE;
        return null;
    }

    private static long energyCost(BuildingContext context, BlockPos position, int components) {
        double distance = Math.max(1.0D, context.player().getDistance(position.getX(), position.getY(), position.getZ()));
        double cost = BASE_EU_PER_COMPONENT * Math.max(1, components) * Math.sqrt(distance);
        if (context.powerEfficiency()) cost *= 0.5D;
        return cost >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) Math.ceil(cost);
    }

    private static void clearForReplacement(BuildingContext context, BlockPos position, IBlockState originalState) {
        if (isAir(context, position, originalState)) return;
        if (!context.world().setBlockState(position, Blocks.AIR.getDefaultState(), WORLD_UPDATE_FLAGS)) {
            throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                    "Minecraft rejected clearing the GregTech destination");
        }
    }

    private static void install(BuildingContext context, BlockPos position, PortableData data) {
        if (data instanceof MteData mte) {
            installMte(context, position, mte);
        } else if (data instanceof PipeData pipe) {
            installPipe(context, position, pipe);
        } else {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                    "The captured GregTech state type is not available");
        }
    }

    private static void installMte(BuildingContext context, BlockPos position, MteData data) {
        Block block = Block.getBlockFromItem(data.placementStack().getItem());
        if (!(block instanceof BlockMachine)) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                    "The captured GregTech machine item is no longer available");
        }
        IBlockState state = block.getStateForPlacement(context.world(), position, EnumFacing.UP, 0.5F, 0.5F, 0.5F,
                0, context.player(), context.hand());
        if (!context.world().setBlockState(position, state, WORLD_UPDATE_FLAGS)) {
            throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                    "Minecraft rejected the GregTech machine placement");
        }
        block.onBlockPlacedBy(context.world(), position, state, context.player(), data.placementStack());
        TileEntity tile = context.world().getTileEntity(position);
        if (!(tile instanceof MetaTileEntityHolder holder) || holder.getMetaTileEntity() == null) {
            throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                    "GregTech did not create the captured MetaTileEntity");
        }
        MetaTileEntity mte = holder.getMetaTileEntity();
        if (data.frontFacing() != null && mte.getFrontFacing() != data.frontFacing() &&
                mte.isValidFrontFacing(data.frontFacing())) {
            mte.setFrontFacing(data.frontFacing());
        }
        if (data.paintingColor() != mte.getPaintingColor()) mte.setPaintingColor(data.paintingColor());
        if (data.smartCopyLink() != null) {
            if (!(mte instanceof ISmartCopyLinkable linkable) || !linkable.setSmartCopyLink(data.smartCopyLink())) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                        "The placed GregTech machine rejected the Smart Copy source");
            }
        }
        if (data.proxyMaster() != null) {
            if (!(mte instanceof MetaTileEntityMEPatternProviderProxy proxy)) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                        "The captured GregTech CRIB proxy did not create its proxy MetaTileEntity");
            }
            proxy.setMainPosition(data.proxyMaster());
        }
        mte.markDirty();
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void installPipe(BuildingContext context, BlockPos position, PipeData data) {
        Block block = Block.getBlockFromItem(data.pipeStack().getItem());
        if (!(block instanceof BlockPipe)) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                    "The captured GregTech pipe item is no longer available");
        }
        IBlockState state = block.getStateForPlacement(context.world(), position, EnumFacing.UP, 0.5F, 0.5F, 0.5F,
                0, context.player(), context.hand());
        if (!context.world().setBlockState(position, state, WORLD_UPDATE_FLAGS)) {
            throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                    "Minecraft rejected the GregTech pipe placement");
        }
        block.onBlockPlacedBy(context.world(), position, state, context.player(), data.pipeStack());
        TileEntity tile = context.world().getTileEntity(position);
        if (!(tile instanceof IPipeTile pipe)) {
            throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                    "GregTech did not create the captured pipe tile");
        }
        pipe.setPaintingColor(data.paintingColor());
        for (EnumFacing side : EnumFacing.VALUES) {
            pipe.setConnection(side, isSet(data.connections(), side), false);
            pipe.setFaceBlocked(side, isSet(data.blockedConnections(), side));
        }
        if (data.frameMaterial() != null && pipe instanceof TileEntityPipeBase base) {
            base.setFrameMaterial(data.frameMaterial());
        }
        pipe.markAsDirty();
        pipe.notifyBlockUpdate();
    }

    private static boolean isSet(int value, EnumFacing side) {
        return (value & 1 << side.getIndex()) != 0;
    }

    private enum PortableType {
        MTE,
        PIPE
    }

    private interface PortableData extends CapturedBlockData {

        BlockSpec primaryMaterial();

        ResourceRequirements requiredResources();

        ResourceRequirements producedResources();

        int componentCount();

        PortableData transformed(ManipulatorTransform transform);
    }

    private static final class MteData implements PortableData {

        private final ItemStack placementStack;
        private final BlockSpec inputMaterial;
        private final EnumFacing frontFacing;
        private final int paintingColor;
        private final SmartCopyLink smartCopyLink;
        private final BlockPos proxyMaster;

        private MteData(ItemStack placementStack, BlockSpec inputMaterial, EnumFacing frontFacing, int paintingColor,
                        SmartCopyLink smartCopyLink, BlockPos proxyMaster) {
            this.placementStack = checkedStack(placementStack);
            this.inputMaterial = Objects.requireNonNull(inputMaterial, "inputMaterial");
            this.frontFacing = frontFacing;
            this.paintingColor = paintingColor;
            this.smartCopyLink = smartCopyLink;
            this.proxyMaster = proxyMaster;
        }

        private static MteData forMaterial(ItemStack material) {
            return new MteData(material, BlockSpec.of(material), null, -1, null, null);
        }

        private static MteData capture(BuildingContext context, BlockPos position, MetaTileEntityHolder holder,
                                       boolean smartCopySource) {
            MetaTileEntity mte = holder.getMetaTileEntity();
            if (context.replaceCribsWithProxies() && mte instanceof MetaTileEntityMEPatternProvider &&
                    !(mte instanceof MetaTileEntityMEPatternProviderProxy)) {
                ItemStack proxyStack = ApplyGrayMetaTileEntities.ME_PATTERN_PROVIDER_PROXY == null
                        ? ItemStack.EMPTY : ApplyGrayMetaTileEntities.ME_PATTERN_PROVIDER_PROXY.getStackForm();
                if (proxyStack.isEmpty()) {
                    throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                            "GregTech CRIB proxy is not registered");
                }
                return new MteData(proxyStack, bare(proxyStack), mte.hasFrontFacing() ? mte.getFrontFacing() : null,
                        mte.getPaintingColor(), null, position.toImmutable());
            }
            if (smartCopySource && mte instanceof ISmartCopyLinkable linkable) {
                SmartCopyLink source = linkable.getSmartCopyLink().orElseGet(
                        () -> new SmartCopyLink(context.world().provider.getDimension(), position));
                ItemStack stack = mte.getStackForm();
                return new MteData(stack, bare(stack), mte.hasFrontFacing() ? mte.getFrontFacing() : null,
                        mte.getPaintingColor(), source, null);
            }
            validateMtePortable(context, position, mte);
            ItemStack stack = mte.getStackForm();
            NBTTagCompound itemData = new NBTTagCompound();
            mte.writeItemStackData(itemData);
            if (!itemData.isEmpty()) stack.setTagCompound(itemData);
            if (holder.hasCustomName()) stack.setStackDisplayName(holder.getName());
            return new MteData(stack, bare(stack), mte.hasFrontFacing() ? mte.getFrontFacing() : null,
                    mte.getPaintingColor(), null, null);
        }

        private static void validateMtePortable(BuildingContext context, BlockPos position, MetaTileEntity mte) {
            if (mte.keepsInventory() || !itemsEmpty(mte.getImportItems()) || !itemsEmpty(mte.getExportItems()) ||
                    !fluidsEmpty(mte.getImportFluids().getTankProperties()) ||
                    !fluidsEmpty(mte.getExportFluids().getTankProperties())) {
                throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                        "The GregTech machine has stored items or fluids and cannot be copied safely yet");
            }
            for (EnumFacing side : EnumFacing.VALUES) {
                if (mte.getCoverAtSide(side) != null) {
                    throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                            "The GregTech machine has covers and cannot be copied safely yet");
                }
            }
            List<ItemStack> extraDrops = new ArrayList<>();
            mte.getDrops(extraDrops, context.player());
            if (!extraDrops.isEmpty()) {
                throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                        "The GregTech machine has extra drops that cannot be restored safely yet");
            }
        }

        @Override
        public BlockSpec primaryMaterial() {
            return bare(placementStack);
        }

        @Override
        public ResourceRequirements requiredResources() {
            return ResourceRequirements.of(new ResourceRequirement(inputMaterial, 1L));
        }

        @Override
        public ResourceRequirements producedResources() {
            return ResourceRequirements.fromStacks(List.of(placementStack));
        }

        @Override
        public int componentCount() {
            return 4;
        }

        @Override
        public MteData transformed(ManipulatorTransform transform) {
            EnumFacing transformed = frontFacing == null ? null : transform.apply(frontFacing);
            return new MteData(placementStack, inputMaterial, transformed, paintingColor, smartCopyLink, proxyMaster);
        }

        private ItemStack placementStack() {
            return placementStack.copy();
        }

        private EnumFacing frontFacing() {
            return frontFacing;
        }

        private int paintingColor() {
            return paintingColor;
        }

        private SmartCopyLink smartCopyLink() {
            return smartCopyLink;
        }

        private BlockPos proxyMaster() {
            return proxyMaster;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof MteData data && frontFacing == data.frontFacing &&
                    paintingColor == data.paintingColor && Objects.equals(smartCopyLink, data.smartCopyLink) &&
                    Objects.equals(proxyMaster, data.proxyMaster) &&
                    ItemStack.areItemStacksEqual(placementStack, data.placementStack);
        }

        @Override
        public int hashCode() {
            return Objects.hash(frontFacing, paintingColor, placementStack.getItem().getRegistryName(),
                    placementStack.getMetadata(), placementStack.getTagCompound(), smartCopyLink, proxyMaster);
        }
    }

    private static final class PipeData implements PortableData {

        private final ItemStack pipeStack;
        private final BlockSpec inputMaterial;
        private final int connections;
        private final int blockedConnections;
        private final int paintingColor;
        private final Material frameMaterial;

        private PipeData(ItemStack pipeStack, BlockSpec inputMaterial, int connections, int blockedConnections,
                         int paintingColor, Material frameMaterial) {
            this.pipeStack = checkedStack(pipeStack);
            this.inputMaterial = Objects.requireNonNull(inputMaterial, "inputMaterial");
            this.connections = connections;
            this.blockedConnections = blockedConnections;
            this.paintingColor = paintingColor;
            this.frameMaterial = frameMaterial;
        }

        private static PipeData forMaterial(ItemStack material) {
            return new PipeData(material, BlockSpec.of(material), 0, 0, -1, null);
        }

        @SuppressWarnings({ "rawtypes", "unchecked" })
        private static PipeData capture(BuildingContext context, BlockPos position, IPipeTile<?, ?> pipe) {
            if (pipe.getCoverableImplementation().hasAnyCover()) {
                throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                        "The GregTech pipe has covers and cannot be copied safely yet");
            }
            if (pipe instanceof TileEntityFluidPipeTickable fluidPipe) {
                for (FluidStack fluid : fluidPipe.getContainedFluids()) {
                    if (fluid != null && fluid.amount > 0) {
                        throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                                "The GregTech fluid pipe contains fluid and cannot be copied safely yet");
                    }
                }
            }
            IBlockState state = context.world().getBlockState(position);
            ItemStack pipeStack = state.getBlock().getItem(context.world(), position, state);
            if (pipeStack.isEmpty()) {
                throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                        "The GregTech pipe has no portable item representation");
            }
            Material frame = pipe.getFrameMaterial();
            ResourceRequirements requirements = pipeRequirements(pipeStack, frame);
            return new PipeData(pipeStack, primaryRequirement(requirements), pipe.getConnections(),
                    pipe.getBlockedConnections(), pipe.isPainted() ? pipe.getPaintingColor() : -1, frame);
        }

        @Override
        public BlockSpec primaryMaterial() {
            return bare(pipeStack);
        }

        @Override
        public ResourceRequirements requiredResources() {
            return pipeRequirements(inputMaterial.toStack(), frameMaterial);
        }

        @Override
        public ResourceRequirements producedResources() {
            return pipeRequirements(pipeStack, frameMaterial);
        }

        @Override
        public int componentCount() {
            return frameMaterial == null ? 1 : 2;
        }

        @Override
        public PipeData transformed(ManipulatorTransform transform) {
            return new PipeData(pipeStack, inputMaterial, transform.applyFacingMask(connections),
                    transform.applyFacingMask(blockedConnections), paintingColor, frameMaterial);
        }

        private ItemStack pipeStack() {
            return pipeStack.copy();
        }

        private int connections() {
            return connections;
        }

        private int blockedConnections() {
            return blockedConnections;
        }

        private int paintingColor() {
            return paintingColor;
        }

        private Material frameMaterial() {
            return frameMaterial;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof PipeData data && connections == data.connections &&
                    blockedConnections == data.blockedConnections && paintingColor == data.paintingColor &&
                    Objects.equals(frameMaterial, data.frameMaterial) && ItemStack.areItemStacksEqual(pipeStack, data.pipeStack);
        }

        @Override
        public int hashCode() {
            return Objects.hash(connections, blockedConnections, paintingColor, frameMaterial,
                    pipeStack.getItem().getRegistryName(), pipeStack.getMetadata(), pipeStack.getTagCompound());
        }
    }

    private static ResourceRequirements pipeRequirements(ItemStack pipeStack, Material frameMaterial) {
        List<ResourceRequirement> requirements = new ArrayList<>();
        requirements.add(new ResourceRequirement(bare(pipeStack), 1L));
        if (frameMaterial != null) {
            requirements.add(new ResourceRequirement(BlockSpec.of(MetaBlocks.FRAMES.get(frameMaterial).getItem(frameMaterial)),
                    1L));
        }
        return ResourceRequirements.of(requirements.toArray(ResourceRequirement[]::new));
    }

    private static BlockSpec primaryRequirement(ResourceRequirements requirements) {
        return requirements.entries().getFirst().specification();
    }

    private static boolean itemsEmpty(IItemHandler handler) {
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (!handler.getStackInSlot(slot).isEmpty()) return false;
        }
        return true;
    }

    private static boolean fluidsEmpty(IFluidTankProperties[] properties) {
        for (IFluidTankProperties property : properties) {
            FluidStack fluid = property.getContents();
            if (fluid != null && fluid.amount > 0) return false;
        }
        return true;
    }

    private static BlockSpec bare(ItemStack stack) {
        ItemStack bare = checkedStack(stack);
        bare.setTagCompound(null);
        return BlockSpec.of(bare);
    }

    private static ItemStack checkedStack(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty()) throw new IllegalArgumentException("GregTech portable data cannot contain an empty stack");
        ItemStack copy = stack.copy();
        copy.setCount(1);
        return copy;
    }

    private static final class TargetContents {

        private final ResourceRequirements outputs;
        private final PortableData data;

        private TargetContents(ResourceRequirements outputs, PortableData data) {
            this.outputs = outputs;
            this.data = data;
        }

        private static TargetContents empty() {
            return new TargetContents(ResourceRequirements.empty(), null);
        }
    }

    private abstract static class GregTechChange implements PreparedBlockChange {

        final BuildingContext context;
        final BlockPos position;
        final IBlockState originalState;
        BlockSnapshot snapshot;

        GregTechChange(BuildingContext context, BlockPos position, IBlockState originalState) {
            this.context = context;
            this.position = position;
            this.originalState = originalState;
        }

        @Override
        public BlockPos position() {
            return position;
        }

        @Override
        public boolean changesWorld() {
            return true;
        }

        @Override
        public void rollback() {
            if (snapshot != null) snapshot.restore(true, false);
        }

        final void verifyOriginalState() {
            if (!context.world().getBlockState(position).equals(originalState)) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                        "The target changed after the GregTech build was prepared");
            }
        }
    }

    private static final class GregTechPlacementChange extends GregTechChange {

        private final TargetContents target;
        private final PortableData data;

        private GregTechPlacementChange(BuildingContext context, BlockPos position, IBlockState originalState,
                                        TargetContents target, PortableData data) {
            super(context, position, originalState);
            this.target = target;
            this.data = data;
        }

        @Override
        public BlockSpec materialCost() {
            return data.primaryMaterial();
        }

        @Override
        public ResourceRequirements requiredResources() {
            return data.requiredResources();
        }

        @Override
        public ResourceRequirements producedResources() {
            return target.outputs;
        }

        @Override
        public long energyCost() {
            return GregTechBuildingAdapter.energyCost(context, position, data.componentCount());
        }

        @Override
        public void apply() {
            verifyOriginalState();
            if (target.data != null && !target.data.equals(requirePortableData(context, position, false))) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                        "The destination GregTech block changed after the build was prepared");
            }
            snapshot = BlockSnapshot.getBlockSnapshot(context.world(), position);
            clearForReplacement(context, position, originalState);
            install(context, position, data);
            BlockEvent.PlaceEvent event = ForgeEventFactory.onPlayerBlockPlace(context.player(), snapshot,
                    EnumFacing.UP, context.hand());
            if (event.isCanceled()) {
                throw new BuildingException(BuildingException.Reason.PERMISSION_DENIED, position,
                        "A protection handler denied the GregTech placement");
            }
        }
    }

    private static final class GregTechRemovalChange extends GregTechChange {

        private final PortableData data;

        private GregTechRemovalChange(BuildingContext context, BlockPos position, IBlockState originalState,
                                     PortableData data) {
            super(context, position, originalState);
            this.data = data;
        }

        @Override
        public BlockSpec materialCost() {
            return BlockSpec.air();
        }

        @Override
        public ResourceRequirements producedResources() {
            return data.producedResources();
        }

        @Override
        public long energyCost() {
            return GregTechBuildingAdapter.energyCost(context, position, data.componentCount());
        }

        @Override
        public void apply() {
            verifyOriginalState();
            if (!data.equals(requirePortableData(context, position, false))) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                        "The GregTech block changed after removal was prepared");
            }
            if (MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(context.world(), position, originalState,
                    context.player()))) {
                throw new BuildingException(BuildingException.Reason.PERMISSION_DENIED, position,
                        "A protection handler denied the GregTech removal");
            }
            snapshot = BlockSnapshot.getBlockSnapshot(context.world(), position);
            clearForReplacement(context, position, originalState);
        }
    }

    private static final class GregTechMoveChange implements PreparedBlockChange {

        private final BuildingContext context;
        private final BlockPos source;
        private final BlockPos target;
        private final IBlockState sourceState;
        private final IBlockState targetState;
        private final PortableData data;
        private BlockSnapshot sourceSnapshot;
        private BlockSnapshot targetSnapshot;

        private GregTechMoveChange(BuildingContext context, BlockPos source, BlockPos target, IBlockState sourceState,
                                   IBlockState targetState, PortableData data) {
            this.context = context;
            this.source = source;
            this.target = target;
            this.sourceState = sourceState;
            this.targetState = targetState;
            this.data = data;
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
        public long energyCost() {
            return GregTechBuildingAdapter.energyCost(context, source, data.componentCount()) +
                    GregTechBuildingAdapter.energyCost(context, target, data.componentCount());
        }

        @Override
        public boolean changesWorld() {
            return true;
        }

        @Override
        public void apply() {
            verifyState(source, sourceState);
            verifyState(target, targetState);
            if (!data.equals(requirePortableData(context, source, false))) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, source,
                        "The GregTech source changed after the move was prepared");
            }
            if (MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(context.world(), source, sourceState,
                    context.player()))) {
                throw new BuildingException(BuildingException.Reason.PERMISSION_DENIED, source,
                        "A protection handler denied the source GregTech move");
            }
            sourceSnapshot = BlockSnapshot.getBlockSnapshot(context.world(), source);
            targetSnapshot = BlockSnapshot.getBlockSnapshot(context.world(), target);
            clearForReplacement(context, source, sourceState);
            install(context, target, data);
            BlockEvent.PlaceEvent event = ForgeEventFactory.onPlayerBlockPlace(context.player(), targetSnapshot,
                    EnumFacing.UP, context.hand());
            if (event.isCanceled()) {
                throw new BuildingException(BuildingException.Reason.PERMISSION_DENIED, target,
                        "A protection handler denied the target GregTech move");
            }
        }

        @Override
        public void rollback() {
            if (targetSnapshot != null) targetSnapshot.restore(true, false);
            if (sourceSnapshot != null) sourceSnapshot.restore(true, false);
        }

        private void verifyState(BlockPos position, IBlockState expected) {
            if (!context.world().getBlockState(position).equals(expected)) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                        "A GregTech move source or target changed after preparation");
            }
        }
    }
}
