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
import gregtech.api.capability.IGhostSlotConfigurable;
import gregtech.api.capability.IBatch;
import gregtech.api.capability.IControllable;
import gregtech.api.capability.IDistinctBusController;
import gregtech.api.capability.IMultipleRecipeMaps;
import gregtech.api.capability.IRecipeControl;
import gregtech.api.capability.GregtechTileCapabilities;
import gregtech.api.capability.impl.GhostCircuitItemStackHandler;
import gregtech.api.capability.impl.ItemHandlerList;
import gregtech.api.cover.Cover;
import gregtech.api.cover.CoverDefinition;
import gregtech.api.cover.CoverHolder;
import gregtech.api.cover.CoverableView;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntityHolder;
import gregtech.api.metatileentity.SimpleMachineMetaTileEntity;
import gregtech.api.mattermanipulator.ISmartCopyLinkable;
import gregtech.api.mattermanipulator.SmartCopyLink;
import gregtech.api.pipenet.block.BlockPipe;
import gregtech.api.pipenet.longdist.ILDEndpoint.IOType;
import gregtech.api.pipenet.tile.IPipeTile;
import gregtech.api.pipenet.tile.TileEntityPipeBase;
import gregtech.api.unification.material.Material;
import gregtech.api.recipes.ingredients.IntCircuitIngredient;
import gregtech.common.blocks.MetaBlocks;
import gregtech.common.metatileentities.multi.multiblockpart.MetaTileEntityComplexDualHatch;
import gregtech.common.metatileentities.multi.multiblockpart.MetaTileEntityDualHatch;
import gregtech.common.metatileentities.multi.multiblockpart.MetaTileEntityFluidHatch;
import gregtech.common.metatileentities.multi.multiblockpart.MetaTileEntityHugeDualHatch;
import gregtech.common.metatileentities.multi.multiblockpart.MetaTileEntityItemBus;
import gregtech.common.pipelike.fluidpipe.tile.TileEntityFluidPipeTickable;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityMEPatternProvider;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityMEPatternProviderProxy;
import gregtech.common.metatileentities.storage.MetaTileEntityBuffer;
import gregtech.common.metatileentities.storage.MetaTileEntityDrum;
import gregtech.common.metatileentities.storage.MetaTileEntityLongDistanceEndpoint;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
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
        if (data.configuration() != null) data.configuration().apply(mte);
        installGhostCircuit(mte, data.ghostCircuit());
        installCovers(context, mte, data.covers());
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
        if (data.configuration() != null && !data.configuration().equals(MteConfiguration.capture(mte))) {
            throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                    "The placed GregTech machine did not retain its portable configuration");
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
        installCovers(context, pipe.getCoverableImplementation(), data.covers());
        pipe.markAsDirty();
        pipe.notifyBlockUpdate();
    }

    private static List<CoverState> captureCovers(CoverableView coverable) {
        List<CoverState> covers = new ArrayList<>();
        for (EnumFacing side : EnumFacing.VALUES) {
            Cover cover = coverable.getCoverAtSide(side);
            if (cover == null) continue;
            List<ItemStack> drops = cover.getDrops().stream().filter(stack -> !stack.isEmpty())
                    .map(ItemStack::copy).toList();
            ItemStack item = cover.getPickItem();
            if (item == null || item.isEmpty()) {
                throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, coverable.getPos(),
                        "The GregTech cover has no portable item representation");
            }
            if (drops.isEmpty()) drops = List.of(item.copy());
            NBTTagCompound coverData = new NBTTagCompound();
            cover.writeToNBT(coverData);
            if (drops.stream().noneMatch(drop -> ItemStack.areItemsEqual(drop, item))) {
                throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, coverable.getPos(),
                        "The GregTech cover dismantle outputs do not contain its placement item");
            }
            covers.add(new CoverState(side, item, cover.getDefinition().getResourceLocation(), coverData, drops));
        }
        return List.copyOf(covers);
    }

    private static ItemStack captureGhostCircuit(MetaTileEntity mte) {
        ItemStack stack = findGhostCircuit(mte.getImportItems());
        if (!stack.isEmpty()) return stack;
        if (mte instanceof IGhostSlotConfigurable configurable && configurable.hasGhostCircuitInventory()) {
            int config = configurable.getGhostCircuitConfig();
            if (config >= IntCircuitIngredient.CIRCUIT_MIN && config <= IntCircuitIngredient.CIRCUIT_MAX) {
                return IntCircuitIngredient.getIntegratedCircuit(config);
            }
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack findGhostCircuit(IItemHandler handler) {
        if (handler instanceof GhostCircuitItemStackHandler ghost) return ghost.getStackInSlot(0).copy();
        if (handler instanceof ItemHandlerList list) {
            for (IItemHandler nested : list.getBackingHandlers()) {
                ItemStack stack = findGhostCircuit(nested);
                if (!stack.isEmpty()) return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static void installGhostCircuit(MetaTileEntity mte, ItemStack ghostCircuit) {
        if (ghostCircuit == null || ghostCircuit.isEmpty()) return;
        if (!(mte instanceof IGhostSlotConfigurable configurable) || !configurable.hasGhostCircuitInventory()) {
            throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, mte.getPos(),
                    "The placed GregTech machine cannot restore its ghost circuit");
        }
        if (IntCircuitIngredient.isIntegratedCircuit(ghostCircuit)) {
            configurable.setGhostCircuitConfig(IntCircuitIngredient.getCircuitConfiguration(ghostCircuit));
        } else {
            configurable.setGhostCustomStack(ghostCircuit.copy());
        }
        if (!ItemStack.areItemStacksEqual(captureGhostCircuit(mte), ghostCircuit)) {
            throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, mte.getPos(),
                    "The placed GregTech machine did not retain its ghost circuit");
        }
    }

    private static void installCovers(BuildingContext context, CoverHolder coverHolder, List<CoverState> covers) {
        for (CoverState state : covers) {
            CoverDefinition definition = CoverDefinition.getCoverById(state.definitionId());
            if (definition == null || !coverHolder.canPlaceCoverOnSide(state.side())) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, coverHolder.getPos(),
                        "The captured GregTech cover is no longer placeable");
            }
            Cover cover = definition.createCover(coverHolder, state.side());
            if (!cover.canAttach(coverHolder, state.side())) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, coverHolder.getPos(),
                        "The captured GregTech cover rejected the destination");
            }
            cover.readFromNBT(state.data());
            cover.onAttachment(coverHolder, state.side(), context.player(), state.itemStack());
            coverHolder.addCover(state.side(), cover);
        }
        if (!covers.isEmpty()) {
            coverHolder.markDirty();
            coverHolder.notifyBlockUpdate();
        }
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
        private final List<CoverState> covers;
        private final ItemStack ghostCircuit;
        private final MteConfiguration configuration;

        private MteData(ItemStack placementStack, BlockSpec inputMaterial, EnumFacing frontFacing, int paintingColor,
                        SmartCopyLink smartCopyLink, BlockPos proxyMaster, List<CoverState> covers,
                        ItemStack ghostCircuit, MteConfiguration configuration) {
            this.placementStack = checkedStack(placementStack);
            this.inputMaterial = Objects.requireNonNull(inputMaterial, "inputMaterial");
            this.frontFacing = frontFacing;
            this.paintingColor = paintingColor;
            this.smartCopyLink = smartCopyLink;
            this.proxyMaster = proxyMaster;
            this.covers = List.copyOf(covers);
            this.ghostCircuit = ghostCircuit == null || ghostCircuit.isEmpty() ? ItemStack.EMPTY : checkedStack(ghostCircuit);
            this.configuration = configuration;
        }

        private static MteData forMaterial(ItemStack material) {
            return new MteData(material, BlockSpec.of(material), null, -1, null, null, List.of(), ItemStack.EMPTY,
                    null);
        }

        private static MteData capture(BuildingContext context, BlockPos position, MetaTileEntityHolder holder,
                                       boolean smartCopySource) {
            MetaTileEntity mte = holder.getMetaTileEntity();
            List<CoverState> covers = captureCovers(mte);
            ItemStack ghostCircuit = captureGhostCircuit(mte);
            if (context.replaceCribsWithProxies() && mte instanceof MetaTileEntityMEPatternProvider &&
                    !(mte instanceof MetaTileEntityMEPatternProviderProxy)) {
                ItemStack proxyStack = ApplyGrayMetaTileEntities.ME_PATTERN_PROVIDER_PROXY == null
                        ? ItemStack.EMPTY : ApplyGrayMetaTileEntities.ME_PATTERN_PROVIDER_PROXY.getStackForm();
                if (proxyStack.isEmpty()) {
                    throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                            "GregTech CRIB proxy is not registered");
                }
                return new MteData(proxyStack, bare(proxyStack), mte.hasFrontFacing() ? mte.getFrontFacing() : null,
                        mte.getPaintingColor(), null, position.toImmutable(), covers, ghostCircuit,
                        MteConfiguration.capture(mte));
            }
            if (smartCopySource && mte instanceof ISmartCopyLinkable linkable) {
                SmartCopyLink source = linkable.getSmartCopyLink().orElseGet(
                        () -> new SmartCopyLink(context.world().provider.getDimension(), position));
                ItemStack stack = mte.getStackForm();
                return new MteData(stack, bare(stack), mte.hasFrontFacing() ? mte.getFrontFacing() : null,
                        mte.getPaintingColor(), source, null, covers, ghostCircuit, MteConfiguration.capture(mte));
            }
            validateMtePortable(context, position, mte);
            ItemStack stack = mte.getStackForm();
            NBTTagCompound itemData = new NBTTagCompound();
            mte.writeItemStackData(itemData);
            if (!itemData.isEmpty()) stack.setTagCompound(itemData);
            if (holder.hasCustomName()) stack.setStackDisplayName(holder.getName());
            return new MteData(stack, bare(stack), mte.hasFrontFacing() ? mte.getFrontFacing() : null,
                    mte.getPaintingColor(), null, null, covers, ghostCircuit, MteConfiguration.capture(mte));
        }

        private static void validateMtePortable(BuildingContext context, BlockPos position, MetaTileEntity mte) {
            if (mte.keepsInventory() || !itemsEmpty(mte.getImportItems(), true) || !itemsEmpty(mte.getExportItems(), true) ||
                    !fluidsEmpty(mte.getImportFluids().getTankProperties()) ||
                    !fluidsEmpty(mte.getExportFluids().getTankProperties())) {
                throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                        "The GregTech machine has stored items or fluids and cannot be copied safely yet");
            }
            List<ItemStack> extraDrops = new ArrayList<>();
            mte.getDrops(extraDrops, context.player());
            List<ItemStack> supportedDrops = mte instanceof MetaTileEntityItemBus bus &&
                    !bus.getOutputFilterStack().isEmpty() ? List.of(bus.getOutputFilterStack()) : List.of();
            if (!extraDrops.isEmpty() && !sameUnorderedStacks(extraDrops, supportedDrops)) {
                throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                        "The GregTech machine has extra drops that cannot be restored safely yet");
            }
        }

        private static boolean sameUnorderedStacks(List<ItemStack> left, List<ItemStack> right) {
            if (left.size() != right.size()) return false;
            boolean[] matched = new boolean[right.size()];
            for (ItemStack expected : left) {
                boolean found = false;
                for (int index = 0; index < right.size(); index++) {
                    ItemStack actual = right.get(index);
                    if (!matched[index] && expected.getCount() == actual.getCount() &&
                            ItemStack.areItemStacksEqual(expected, actual)) {
                        matched[index] = true;
                        found = true;
                        break;
                    }
                }
                if (!found) return false;
            }
            return true;
        }

        @Override
        public BlockSpec primaryMaterial() {
            return bare(placementStack);
        }

        @Override
        public ResourceRequirements requiredResources() {
            List<ResourceRequirement> requirements = new ArrayList<>();
            requirements.add(new ResourceRequirement(inputMaterial, 1L));
            for (CoverState cover : covers) {
                for (ItemStack drop : cover.drops()) {
                    requirements.add(new ResourceRequirement(BlockSpec.of(drop), drop.getCount()));
                }
            }
            if (configuration != null) {
                for (ItemStack stored : configuration.storedStacks()) {
                    requirements.add(new ResourceRequirement(BlockSpec.of(stored), stored.getCount()));
                }
            }
            return ResourceRequirements.of(requirements.toArray(ResourceRequirement[]::new));
        }

        @Override
        public ResourceRequirements producedResources() {
            List<ItemStack> outputs = new ArrayList<>();
            outputs.add(placementStack);
            covers.forEach(cover -> outputs.addAll(cover.drops()));
            if (configuration != null) outputs.addAll(configuration.storedStacks());
            return ResourceRequirements.fromStacks(outputs);
        }

        @Override
        public int componentCount() {
            return 5 + covers.size() + (configuration == null ? 0 : configuration.storedStacks().size());
        }

        @Override
        public MteData transformed(ManipulatorTransform transform) {
            EnumFacing transformed = frontFacing == null ? null : transform.apply(frontFacing);
            List<CoverState> transformedCovers = covers.stream().map(cover -> cover.transformed(transform)).toList();
            return new MteData(placementStack, inputMaterial, transformed, paintingColor, smartCopyLink, proxyMaster,
                    transformedCovers, ghostCircuit,
                    configuration == null ? null : configuration.transformed(transform));
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

        private List<CoverState> covers() {
            return covers;
        }

        private ItemStack ghostCircuit() {
            return ghostCircuit.copy();
        }

        private MteConfiguration configuration() {
            return configuration;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof MteData data && frontFacing == data.frontFacing &&
                    paintingColor == data.paintingColor && Objects.equals(smartCopyLink, data.smartCopyLink) &&
                    Objects.equals(proxyMaster, data.proxyMaster) &&
                    Objects.equals(covers, data.covers) &&
                    Objects.equals(configuration, data.configuration) &&
                    ItemStack.areItemStacksEqual(ghostCircuit, data.ghostCircuit) &&
                    ItemStack.areItemStacksEqual(placementStack, data.placementStack);
        }

        @Override
        public int hashCode() {
            return Objects.hash(frontFacing, paintingColor, placementStack.getItem().getRegistryName(),
                    placementStack.getMetadata(), placementStack.getTagCompound(), smartCopyLink, proxyMaster, covers,
                    ghostCircuit.getItem().getRegistryName(), ghostCircuit.getMetadata(), ghostCircuit.getTagCompound(),
                    configuration);
        }
    }

    /** Portable machine settings that have public, side-effect-bounded restore APIs in the target GregTech build. */
    private static final class MteConfiguration {

        private final boolean muffled;
        private final Boolean workingEnabled;
        private final SimpleMachineConfiguration simpleMachine;
        private final ItemHandlingConfiguration itemHandling;
        private final ItemBusFilterConfiguration itemBusFilter;
        private final FluidHatchConfiguration fluidHatch;
        private final BufferConfiguration buffer;
        private final Boolean drumAutoOutput;
        private final Boolean batchEnabled;
        private final Boolean distinct;
        private final Boolean energyLackWarning;
        private final Integer recipeMapIndex;
        private final IOType longDistanceIoType;

        private MteConfiguration(boolean muffled, Boolean workingEnabled,
                                 SimpleMachineConfiguration simpleMachine,
                                 ItemHandlingConfiguration itemHandling,
                                 ItemBusFilterConfiguration itemBusFilter,
                                 FluidHatchConfiguration fluidHatch, BufferConfiguration buffer,
                                 Boolean drumAutoOutput, Boolean batchEnabled, Boolean distinct,
                                 Boolean energyLackWarning, Integer recipeMapIndex, IOType longDistanceIoType) {
            this.muffled = muffled;
            this.workingEnabled = workingEnabled;
            this.simpleMachine = simpleMachine;
            this.itemHandling = itemHandling;
            this.itemBusFilter = itemBusFilter;
            this.fluidHatch = fluidHatch;
            this.buffer = buffer;
            this.drumAutoOutput = drumAutoOutput;
            this.batchEnabled = batchEnabled;
            this.distinct = distinct;
            this.energyLackWarning = energyLackWarning;
            this.recipeMapIndex = recipeMapIndex;
            this.longDistanceIoType = longDistanceIoType;
        }

        private static MteConfiguration capture(MetaTileEntity mte) {
            IControllable controllable = controllable(mte);
            Boolean workingEnabled = controllable == null ? null : controllable.isWorkingEnabled();
            SimpleMachineConfiguration simpleMachine = mte instanceof SimpleMachineMetaTileEntity machine
                    ? SimpleMachineConfiguration.capture(machine) : null;
            ItemHandlingConfiguration itemHandling = ItemHandlingConfiguration.capture(mte);
            ItemBusFilterConfiguration itemBusFilter = mte instanceof MetaTileEntityItemBus bus
                    ? ItemBusFilterConfiguration.capture(bus) : null;
            FluidHatchConfiguration fluidHatch = mte instanceof MetaTileEntityFluidHatch hatch
                    ? FluidHatchConfiguration.capture(hatch) : null;
            BufferConfiguration buffer = mte instanceof MetaTileEntityBuffer machineBuffer
                    ? BufferConfiguration.capture(machineBuffer) : null;
            Boolean drumAutoOutput = mte instanceof MetaTileEntityDrum drum ? drum.isAutoOutput() : null;
            Boolean batchEnabled = mte instanceof IBatch batch && batch.isBatchAllowed()
                    ? batch.isBatchEnable() : null;
            Boolean distinct = mte instanceof IDistinctBusController controller && controller.canBeDistinct()
                    ? controller.isDistinct() : null;
            Boolean energyLackWarning = null;
            if (mte instanceof IRecipeControl control && control.enableExtendControl()) {
                if (control.isRecipeLocked()) {
                    throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, mte.getPos(),
                            "A locked GregTech recipe cannot be copied without its recipe identity");
                }
                energyLackWarning = control.isEnergyLackWarningEnabled();
            }
            Integer recipeMapIndex = mte instanceof IMultipleRecipeMaps maps ? maps.getRecipeMapIndex() : null;
            IOType ioType = mte instanceof MetaTileEntityLongDistanceEndpoint endpoint ? endpoint.getIoType() : null;
            return new MteConfiguration(mte.isMuffled(), workingEnabled, simpleMachine, itemHandling, itemBusFilter,
                    fluidHatch, buffer, drumAutoOutput, batchEnabled, distinct, energyLackWarning, recipeMapIndex,
                    ioType);
        }

        private void apply(MetaTileEntity mte) {
            mte.setMuffled(muffled);
            IControllable controllable = controllable(mte);
            if (workingEnabled != null) {
                if (controllable == null) incompatible(mte, "working-enabled state");
                controllable.setWorkingEnabled(workingEnabled);
            }
            if (simpleMachine != null) {
                if (mte instanceof SimpleMachineMetaTileEntity machine) {
                    simpleMachine.apply(machine);
                } else {
                    incompatible(mte, "single-machine I/O state");
                }
            }
            if (itemHandling != null) itemHandling.apply(mte);
            if (itemBusFilter != null) {
                if (mte instanceof MetaTileEntityItemBus bus) {
                    itemBusFilter.apply(bus);
                } else {
                    incompatible(mte, "item-bus output filter");
                }
            }
            if (fluidHatch != null) {
                if (mte instanceof MetaTileEntityFluidHatch hatch) {
                    fluidHatch.apply(hatch);
                } else {
                    incompatible(mte, "fluid-hatch lock");
                }
            }
            if (buffer != null) {
                if (mte instanceof MetaTileEntityBuffer machineBuffer) {
                    buffer.apply(machineBuffer);
                } else {
                    incompatible(mte, "buffer I/O state");
                }
            }
            if (drumAutoOutput != null) {
                if (mte instanceof MetaTileEntityDrum drum) {
                    drum.setAutoOutput(drumAutoOutput);
                } else {
                    incompatible(mte, "drum auto-output state");
                }
            }
            if (batchEnabled != null) {
                if (mte instanceof IBatch batch && batch.isBatchAllowed()) {
                    batch.setBatchEnable(batchEnabled);
                } else {
                    incompatible(mte, "batch state");
                }
            }
            if (distinct != null) {
                if (mte instanceof IDistinctBusController controller && controller.canBeDistinct()) {
                    controller.setDistinct(distinct);
                } else {
                    incompatible(mte, "input-separation state");
                }
            }
            if (energyLackWarning != null) {
                if (mte instanceof IRecipeControl control && control.enableExtendControl()) {
                    control.setEnergyLackWarningEnabled(energyLackWarning);
                } else {
                    incompatible(mte, "energy-warning state");
                }
            }
            if (recipeMapIndex != null) {
                if (mte instanceof IMultipleRecipeMaps maps) {
                    maps.setRecipeMapIndex(recipeMapIndex);
                } else {
                    incompatible(mte, "recipe-map selection");
                }
            }
            if (longDistanceIoType != null) {
                if (mte instanceof MetaTileEntityLongDistanceEndpoint endpoint) {
                    endpoint.setIoType(longDistanceIoType);
                } else {
                    incompatible(mte, "long-distance endpoint mode");
                }
            }
        }

        private MteConfiguration transformed(ManipulatorTransform transform) {
            return new MteConfiguration(muffled, workingEnabled,
                    simpleMachine == null ? null : simpleMachine.transformed(transform), itemHandling, itemBusFilter,
                    fluidHatch, buffer == null ? null : buffer.transformed(transform), drumAutoOutput, batchEnabled,
                    distinct, energyLackWarning, recipeMapIndex, longDistanceIoType);
        }

        private List<ItemStack> storedStacks() {
            return itemBusFilter == null ? List.of() : itemBusFilter.storedStacks();
        }

        private static IControllable controllable(MetaTileEntity mte) {
            if (mte instanceof IControllable direct) return direct;
            return mte.getCapability(GregtechTileCapabilities.CAPABILITY_CONTROLLABLE, null);
        }

        private static void incompatible(MetaTileEntity mte, String state) {
            throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, mte.getPos(),
                    "The placed GregTech machine cannot restore its " + state);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof MteConfiguration config)) return false;
            return muffled == config.muffled && Objects.equals(workingEnabled, config.workingEnabled) &&
                    Objects.equals(simpleMachine, config.simpleMachine) &&
                    Objects.equals(itemHandling, config.itemHandling) &&
                    Objects.equals(itemBusFilter, config.itemBusFilter) &&
                    Objects.equals(fluidHatch, config.fluidHatch) && Objects.equals(buffer, config.buffer) &&
                    Objects.equals(drumAutoOutput, config.drumAutoOutput) &&
                    Objects.equals(batchEnabled, config.batchEnabled) && Objects.equals(distinct, config.distinct) &&
                    Objects.equals(energyLackWarning, config.energyLackWarning) &&
                    Objects.equals(recipeMapIndex, config.recipeMapIndex) &&
                    longDistanceIoType == config.longDistanceIoType;
        }

        @Override
        public int hashCode() {
            return Objects.hash(muffled, workingEnabled, simpleMachine, itemHandling, itemBusFilter, fluidHatch, buffer,
                    drumAutoOutput, batchEnabled, distinct, energyLackWarning, recipeMapIndex, longDistanceIoType);
        }
    }

    private record SimpleMachineConfiguration(EnumFacing itemOutput, EnumFacing fluidOutput, boolean autoOutputItems,
                                              boolean autoOutputFluids, boolean allowItemInput,
                                              boolean allowFluidInput, boolean disallowSameItemInsert) {

        private static SimpleMachineConfiguration capture(SimpleMachineMetaTileEntity machine) {
            return new SimpleMachineConfiguration(machine.getOutputFacingItems(), machine.getOutputFacingFluids(),
                    machine.isAutoOutputItems(), machine.isAutoOutputFluids(),
                    machine.isAllowInputFromOutputSideItems(), machine.isAllowInputFromOutputSideFluids(),
                    machine.isDisallowSameItemInsert());
        }

        private void apply(SimpleMachineMetaTileEntity machine) {
            machine.setOutputFacingItems(itemOutput);
            machine.setOutputFacingFluids(fluidOutput);
            machine.setAutoOutputItems(autoOutputItems);
            machine.setAutoOutputFluids(autoOutputFluids);
            machine.setAllowInputFromOutputSideItems(allowItemInput);
            machine.setAllowInputFromOutputSideFluids(allowFluidInput);
            machine.setDisallowSameItemInsert(disallowSameItemInsert);
        }

        private SimpleMachineConfiguration transformed(ManipulatorTransform transform) {
            return new SimpleMachineConfiguration(transform.apply(itemOutput), transform.apply(fluidOutput),
                    autoOutputItems, autoOutputFluids, allowItemInput, allowFluidInput, disallowSameItemInsert);
        }
    }

    private record ItemHandlingConfiguration(boolean autoCollapse, Boolean disallowSameItemInsert) {

        private static ItemHandlingConfiguration capture(MetaTileEntity mte) {
            if (mte instanceof MetaTileEntityItemBus bus) {
                return new ItemHandlingConfiguration(bus.isAutoCollapse(), bus.isDisallowSameItemInsert());
            }
            if (mte instanceof MetaTileEntityDualHatch hatch) {
                return new ItemHandlingConfiguration(hatch.isAutoCollapse(), hatch.isDisallowSameItemInsert());
            }
            if (mte instanceof MetaTileEntityHugeDualHatch hatch) {
                return new ItemHandlingConfiguration(hatch.isAutoCollapse(), null);
            }
            if (mte instanceof MetaTileEntityComplexDualHatch hatch) {
                return new ItemHandlingConfiguration(hatch.isAutoCollapse(), null);
            }
            return null;
        }

        private void apply(MetaTileEntity mte) {
            if (mte instanceof MetaTileEntityItemBus bus) {
                bus.setAutoCollapse(autoCollapse);
                if (disallowSameItemInsert != null) bus.setDisallowSameItemInsert(disallowSameItemInsert);
            } else if (mte instanceof MetaTileEntityDualHatch hatch) {
                hatch.setAutoCollapse(autoCollapse);
                if (disallowSameItemInsert != null) hatch.setDisallowSameItemInsert(disallowSameItemInsert);
            } else if (mte instanceof MetaTileEntityHugeDualHatch hatch) {
                hatch.setAutoCollapse(autoCollapse);
                if (disallowSameItemInsert != null) MteConfiguration.incompatible(mte, "item-insertion mode");
            } else if (mte instanceof MetaTileEntityComplexDualHatch hatch) {
                hatch.setAutoCollapse(autoCollapse);
                if (disallowSameItemInsert != null) MteConfiguration.incompatible(mte, "item-insertion mode");
            } else {
                MteConfiguration.incompatible(mte, "item handling state");
            }
        }
    }

    private static final class ItemBusFilterConfiguration {

        private final NBTTagCompound data;
        private final ItemStack filterStack;

        private ItemBusFilterConfiguration(NBTTagCompound data, ItemStack filterStack) {
            this.data = data.copy();
            this.filterStack = filterStack.isEmpty() ? ItemStack.EMPTY : filterStack.copy();
        }

        private static ItemBusFilterConfiguration capture(MetaTileEntityItemBus bus) {
            NBTTagCompound data = bus.getOutputFilterData();
            return data == null ? null : new ItemBusFilterConfiguration(data, bus.getOutputFilterStack());
        }

        private void apply(MetaTileEntityItemBus bus) {
            bus.setOutputFilterData(data.copy());
        }

        private List<ItemStack> storedStacks() {
            return filterStack.isEmpty() ? List.of() : List.of(filterStack.copy());
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ItemBusFilterConfiguration config && Objects.equals(data, config.data) &&
                    ItemStack.areItemStacksEqual(filterStack, config.filterStack);
        }

        @Override
        public int hashCode() {
            return Objects.hash(data, filterStack.getItem().getRegistryName(), filterStack.getMetadata(),
                    filterStack.getTagCompound());
        }
    }

    private static final class FluidHatchConfiguration {

        private final boolean locked;
        private final FluidStack lockedFluid;

        private FluidHatchConfiguration(boolean locked, FluidStack lockedFluid) {
            this.locked = locked;
            this.lockedFluid = lockedFluid == null ? null : lockedFluid.copy();
            if (this.lockedFluid != null) this.lockedFluid.amount = 1;
        }

        private static FluidHatchConfiguration capture(MetaTileEntityFluidHatch hatch) {
            return new FluidHatchConfiguration(hatch.isLocked(), hatch.getLockedFluid());
        }

        private void apply(MetaTileEntityFluidHatch hatch) {
            hatch.setLocked(locked);
            if (lockedFluid != null) hatch.setLockedFluid(lockedFluid.copy());
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof FluidHatchConfiguration config) || locked != config.locked) return false;
            if (lockedFluid == null || config.lockedFluid == null) return lockedFluid == config.lockedFluid;
            return lockedFluid.isFluidStackIdentical(config.lockedFluid);
        }

        @Override
        public int hashCode() {
            return Objects.hash(locked, lockedFluid == null ? null : lockedFluid.getFluid().getName(),
                    lockedFluid == null ? null : lockedFluid.tag);
        }
    }

    private record BufferConfiguration(EnumFacing itemOutput, EnumFacing fluidOutput, boolean autoOutputItems,
                                       boolean autoOutputFluids) {

        private static BufferConfiguration capture(MetaTileEntityBuffer buffer) {
            return new BufferConfiguration(buffer.getOutputFacingItems(), buffer.getOutputFacingFluids(),
                    buffer.isAutoOutputItems(), buffer.isAutoOutputFluids());
        }

        private void apply(MetaTileEntityBuffer buffer) {
            buffer.setOutputFacingItems(itemOutput);
            buffer.setOutputFacingFluids(fluidOutput);
            buffer.setAutoOutputItems(autoOutputItems);
            buffer.setAutoOutputFluids(autoOutputFluids);
        }

        private BufferConfiguration transformed(ManipulatorTransform transform) {
            return new BufferConfiguration(transform.apply(itemOutput), transform.apply(fluidOutput), autoOutputItems,
                    autoOutputFluids);
        }
    }

    private static final class CoverState {

        private final EnumFacing side;
        private final ItemStack itemStack;
        private final ResourceLocation definitionId;
        private final NBTTagCompound data;
        private final List<ItemStack> drops;

        private CoverState(EnumFacing side, ItemStack itemStack, ResourceLocation definitionId, NBTTagCompound data,
                           List<ItemStack> drops) {
            this.side = Objects.requireNonNull(side, "side");
            this.itemStack = checkedStack(itemStack);
            this.definitionId = Objects.requireNonNull(definitionId, "definitionId");
            this.data = data.copy();
            this.drops = drops.stream().map(ItemStack::copy).toList();
        }

        private EnumFacing side() {
            return side;
        }

        private ItemStack itemStack() {
            return itemStack.copy();
        }

        private ResourceLocation definitionId() {
            return definitionId;
        }

        private NBTTagCompound data() {
            return data.copy();
        }

        private List<ItemStack> drops() {
            return drops.stream().map(ItemStack::copy).toList();
        }

        private CoverState transformed(ManipulatorTransform transform) {
            return new CoverState(transform.apply(side), itemStack, definitionId, data, drops);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof CoverState state && side == state.side &&
                    Objects.equals(definitionId, state.definitionId) &&
                    Objects.equals(data, state.data) && ItemStack.areItemStacksEqual(itemStack, state.itemStack) &&
                    sameOrderedStacks(drops, state.drops);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(side, definitionId, data, itemStack.getItem().getRegistryName(),
                    itemStack.getMetadata(), itemStack.getTagCompound());
            for (ItemStack drop : drops) {
                result = 31 * result + Objects.hash(drop.getItem().getRegistryName(), drop.getMetadata(),
                        drop.getCount(), drop.getTagCompound());
            }
            return result;
        }

        private static boolean sameOrderedStacks(List<ItemStack> left, List<ItemStack> right) {
            if (left.size() != right.size()) return false;
            for (int index = 0; index < left.size(); index++) {
                ItemStack first = left.get(index);
                ItemStack second = right.get(index);
                if (first.getCount() != second.getCount() || !ItemStack.areItemStacksEqual(first, second)) return false;
            }
            return true;
        }
    }

    private static final class PipeData implements PortableData {

        private final ItemStack pipeStack;
        private final BlockSpec inputMaterial;
        private final int connections;
        private final int blockedConnections;
        private final int paintingColor;
        private final Material frameMaterial;
        private final List<CoverState> covers;

        private PipeData(ItemStack pipeStack, BlockSpec inputMaterial, int connections, int blockedConnections,
                         int paintingColor, Material frameMaterial, List<CoverState> covers) {
            this.pipeStack = checkedStack(pipeStack);
            this.inputMaterial = Objects.requireNonNull(inputMaterial, "inputMaterial");
            this.connections = connections;
            this.blockedConnections = blockedConnections;
            this.paintingColor = paintingColor;
            this.frameMaterial = frameMaterial;
            this.covers = List.copyOf(covers);
        }

        private static PipeData forMaterial(ItemStack material) {
            return new PipeData(material, BlockSpec.of(material), 0, 0, -1, null, List.of());
        }

        @SuppressWarnings({ "rawtypes", "unchecked" })
        private static PipeData capture(BuildingContext context, BlockPos position, IPipeTile<?, ?> pipe) {
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
                    pipe.getBlockedConnections(), pipe.isPainted() ? pipe.getPaintingColor() : -1, frame,
                    captureCovers(pipe.getCoverableImplementation()));
        }

        @Override
        public BlockSpec primaryMaterial() {
            return bare(pipeStack);
        }

        @Override
        public ResourceRequirements requiredResources() {
            ResourceRequirements requirements = pipeRequirements(inputMaterial.toStack(), frameMaterial);
            for (CoverState cover : covers) {
                requirements = ResourceRequirements.combine(requirements, ResourceRequirements.fromStacks(cover.drops()));
            }
            return requirements;
        }

        @Override
        public ResourceRequirements producedResources() {
            ResourceRequirements requirements = pipeRequirements(pipeStack, frameMaterial);
            List<ItemStack> coverDrops = new ArrayList<>();
            covers.forEach(cover -> coverDrops.addAll(cover.drops()));
            return ResourceRequirements.combine(requirements, ResourceRequirements.fromStacks(coverDrops));
        }

        @Override
        public int componentCount() {
            return (frameMaterial == null ? 1 : 2) + covers.size();
        }

        @Override
        public PipeData transformed(ManipulatorTransform transform) {
            List<CoverState> transformedCovers = covers.stream().map(cover -> cover.transformed(transform)).toList();
            return new PipeData(pipeStack, inputMaterial, transform.applyFacingMask(connections),
                    transform.applyFacingMask(blockedConnections), paintingColor, frameMaterial, transformedCovers);
        }

        private ItemStack pipeStack() {
            return pipeStack.copy();
        }

        private List<CoverState> covers() {
            return covers;
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
                    Objects.equals(frameMaterial, data.frameMaterial) && Objects.equals(covers, data.covers) &&
                    ItemStack.areItemStacksEqual(pipeStack, data.pipeStack);
        }

        @Override
        public int hashCode() {
            return Objects.hash(connections, blockedConnections, paintingColor, frameMaterial,
                    covers, pipeStack.getItem().getRegistryName(), pipeStack.getMetadata(), pipeStack.getTagCompound());
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

    private static boolean itemsEmpty(IItemHandler handler, boolean ignoreGhostCircuit) {
        if (ignoreGhostCircuit && handler instanceof GhostCircuitItemStackHandler) return true;
        if (handler instanceof ItemHandlerList list) {
            for (IItemHandler nested : list.getBackingHandlers()) {
                if (!itemsEmpty(nested, ignoreGhostCircuit)) return false;
            }
            return true;
        }
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
