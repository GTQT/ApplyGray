package applygray.mattermanipulator.integration.gregtech;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import applygray.mattermanipulator.building.BlockSpec;
import applygray.mattermanipulator.building.BuildingAdapter;
import applygray.mattermanipulator.building.BuildingContext;
import applygray.mattermanipulator.building.BuildingException;
import applygray.mattermanipulator.building.BuildingEventHooks;
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
import gregtech.api.capability.IGenerator;
import gregtech.api.capability.IMultipleRecipeMaps;
import gregtech.api.capability.IMultipleTankHandler;
import gregtech.api.capability.IObjectHolder;
import gregtech.api.capability.IRecipeControl;
import gregtech.api.capability.IThreadHatch;
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
import gregtech.api.metatileentity.multiblock.MultiblockControllerBase;
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
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityMEInputBase;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityMEDualInputHatch;
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
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
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
        return new GregTechRemovalChange(context, position, originalState,
                requirePortableData(context, position, false, true));
    }

    @Override
    public boolean supportsMove(BuildingContext context, BlockPos source, BlockPos target) {
        if (dataForTile(context, source, false) == null) return false;
        IBlockState targetState = context.world().getBlockState(target);
        return isAir(context, target, targetState);
    }

    @Override
    public PreparedBlockChange prepareMove(BuildingContext context, BlockPos source, BlockPos target) {
        return prepareMove(context, source, target, false);
    }

    @Override
    public PreparedBlockChange prepareMoveAfterTargetRemoval(BuildingContext context, BlockPos source,
                                                              BlockPos target) {
        return prepareMove(context, source, target, true);
    }

    private PreparedBlockChange prepareMove(BuildingContext context, BlockPos source, BlockPos target,
                                            boolean targetPrecleared) {
        if (source.equals(target)) {
            throw new BuildingException(BuildingException.Reason.OVERLAPPING_MOVE, source,
                    "A move source and target cannot be the same block");
        }
        IBlockState sourceState = validateEditable(context, source);
        IBlockState targetState = validateEditable(context, target);
        PortableData data = requirePortableData(context, source, false, true);
        if (!targetPrecleared && !isAir(context, target, targetState)) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, target,
                    "Moving a GregTech block currently requires an empty destination");
        }
        if (!context.world().checkNoEntityCollision(new AxisAlignedBB(target))) {
            throw new BuildingException(BuildingException.Reason.CANNOT_PLACE, target,
                    "An entity blocks the GregTech destination");
        }
        return new GregTechMoveChange(context, source, target, sourceState, targetState, data, targetPrecleared);
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
        PortableData portable = dataForTile(context, position, false, true);
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
        return requirePortableData(context, position, smartCopySource, false);
    }

    private static PortableData requirePortableData(BuildingContext context, BlockPos position,
                                                    boolean smartCopySource, boolean clearContents) {
        PortableData data = dataForTile(context, position, smartCopySource, clearContents);
        if (data == null) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                    "The target is not a supported GregTech machine or pipe");
        }
        return data;
    }

    private static PortableData dataForTile(BuildingContext context, BlockPos position, boolean smartCopySource) {
        return dataForTile(context, position, smartCopySource, false);
    }

    private static PortableData dataForTile(BuildingContext context, BlockPos position, boolean smartCopySource,
                                            boolean clearContents) {
        TileEntity tile = context.world().getTileEntity(position);
        if (tile instanceof MetaTileEntityHolder holder && holder.getMetaTileEntity() != null) {
            return MteData.capture(context, position, holder, smartCopySource, clearContents);
        }
        if (tile instanceof IPipeTile<?, ?> pipe) {
            return PipeData.capture(context, position, pipe, clearContents);
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

    private static ResourceRequirements captureLiveContents(MetaTileEntity mte) {
        List<ItemStack> items = new ArrayList<>();
        Set<IItemHandler> seenItems = Collections.newSetFromMap(new IdentityHashMap<>());
        captureItems(mte.getImportItems(), seenItems, items);
        captureItems(mte.getExportItems(), seenItems, items);
        if (mte instanceof IObjectHolder objectHolder) captureItems(objectHolder.getAsHandler(), seenItems, items);

        List<FluidStack> fluids = new ArrayList<>();
        Set<IMultipleTankHandler> seenFluids = Collections.newSetFromMap(new IdentityHashMap<>());
        captureFluids(mte.getImportFluids(), seenFluids, fluids);
        captureFluids(mte.getExportFluids(), seenFluids, fluids);
        return ResourceRequirements.combine(ResourceRequirements.fromStacks(items),
                ResourceRequirements.fromFluids(fluids));
    }

    private static void captureItems(IItemHandler handler, Set<IItemHandler> seen, List<ItemStack> output) {
        if (handler instanceof GhostCircuitItemStackHandler) return;
        if (!seen.add(handler)) return;
        if (handler instanceof ItemHandlerList list) {
            for (IItemHandler nested : list.getBackingHandlers()) captureItems(nested, seen, output);
            return;
        }
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack != null && !stack.isEmpty()) output.add(stack.copy());
        }
    }

    private static void captureFluids(IMultipleTankHandler handler, Set<IMultipleTankHandler> seen,
                                      List<FluidStack> output) {
        if (!seen.add(handler)) return;
        for (IMultipleTankHandler.ITankEntry tank : handler.getFluidTanks()) {
            FluidStack stack = tank.getFluid();
            if (stack != null && stack.amount > 0) output.add(stack.copy());
        }
    }

    private static void clearFluids(IMultipleTankHandler handler) {
        for (IMultipleTankHandler.ITankEntry tank : handler.getFluidTanks()) {
            if (tank.getFluidAmount() > 0) tank.drain(Integer.MAX_VALUE, true);
        }
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
        if (data.upwardsFacing() != null) {
            if (!(mte instanceof MultiblockControllerBase controller) || !controller.allowsExtendedFacing()) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                        "The placed GregTech machine cannot restore its upwards facing");
            }
            controller.setUpwardsFacing(data.upwardsFacing());
        }
        if (data.frontFacing() != null && mte.getFrontFacing() != data.frontFacing()) {
            throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                    "The placed GregTech machine did not retain its front facing");
        }
        if (data.upwardsFacing() != null &&
                (!(mte instanceof MultiblockControllerBase controller) ||
                        controller.getUpwardsFacing() != data.upwardsFacing())) {
            throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                    "The placed GregTech machine did not retain its upwards facing");
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

        ResourceRequirements liveContents();

        void clearLiveContents(BuildingContext context, BlockPos position);

        int componentCount();

        PortableData transformed(ManipulatorTransform transform);
    }

    private static final class MteData implements PortableData {

        private final ItemStack placementStack;
        private final BlockSpec inputMaterial;
        private final EnumFacing frontFacing;
        private final EnumFacing upwardsFacing;
        private final int paintingColor;
        private final SmartCopyLink smartCopyLink;
        private final BlockPos proxyMaster;
        private final List<CoverState> covers;
        private final ItemStack ghostCircuit;
        private final MteConfiguration configuration;
        private final ResourceRequirements liveContents;

        private MteData(ItemStack placementStack, BlockSpec inputMaterial, EnumFacing frontFacing,
                        EnumFacing upwardsFacing, int paintingColor,
                       SmartCopyLink smartCopyLink, BlockPos proxyMaster, List<CoverState> covers,
                        ItemStack ghostCircuit, MteConfiguration configuration,
                        ResourceRequirements liveContents) {
            this.placementStack = checkedStack(placementStack);
            this.inputMaterial = Objects.requireNonNull(inputMaterial, "inputMaterial");
            this.frontFacing = frontFacing;
            this.upwardsFacing = upwardsFacing;
            this.paintingColor = paintingColor;
            this.smartCopyLink = smartCopyLink;
            this.proxyMaster = proxyMaster;
            this.covers = List.copyOf(covers);
            this.ghostCircuit = ghostCircuit == null || ghostCircuit.isEmpty() ? ItemStack.EMPTY : checkedStack(ghostCircuit);
            this.configuration = configuration;
            this.liveContents = liveContents == null ? ResourceRequirements.empty() : liveContents;
        }

        private static MteData forMaterial(ItemStack material) {
            return new MteData(material, BlockSpec.of(material), null, null, -1, null, null, List.of(), ItemStack.EMPTY,
                    null, ResourceRequirements.empty());
        }

        private static MteData capture(BuildingContext context, BlockPos position, MetaTileEntityHolder holder,
                                       boolean smartCopySource, boolean clearContents) {
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
                        upwardsFacing(mte), mte.getPaintingColor(), null, position.toImmutable(), covers, ghostCircuit,
                        MteConfiguration.capture(mte), clearContents ? captureLiveContents(mte) : ResourceRequirements.empty());
            }
            if (smartCopySource && mte instanceof ISmartCopyLinkable linkable) {
                SmartCopyLink source = linkable.getSmartCopyLink().orElseGet(
                        () -> new SmartCopyLink(context.world().provider.getDimension(), position));
                ItemStack stack = mte.getStackForm();
                return new MteData(stack, bare(stack), mte.hasFrontFacing() ? mte.getFrontFacing() : null,
                        upwardsFacing(mte), mte.getPaintingColor(), source, null, covers, ghostCircuit,
                        MteConfiguration.capture(mte),
                        ResourceRequirements.empty());
            }
            validateMtePortable(context, position, mte, clearContents);
            ItemStack stack = mte.getStackForm();
            NBTTagCompound itemData = new NBTTagCompound();
            mte.writeItemStackData(itemData);
            if (!itemData.isEmpty()) stack.setTagCompound(itemData);
            if (holder.hasCustomName()) stack.setStackDisplayName(holder.getName());
            return new MteData(stack, bare(stack), mte.hasFrontFacing() ? mte.getFrontFacing() : null,
                    upwardsFacing(mte), mte.getPaintingColor(), null, null, covers, ghostCircuit,
                    MteConfiguration.capture(mte),
                    clearContents ? captureLiveContents(mte) : ResourceRequirements.empty());
        }

        private static EnumFacing upwardsFacing(MetaTileEntity mte) {
            return mte instanceof MultiblockControllerBase controller && controller.allowsExtendedFacing()
                    ? controller.getUpwardsFacing() : null;
        }

        private static void validateMtePortable(BuildingContext context, BlockPos position, MetaTileEntity mte,
                                                boolean clearContents) {
            if (clearContents && mte.keepsInventory()) {
                throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                        "The GregTech machine keeps internal inventory and cannot be cleared safely");
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
            return ResourceRequirements.combine(ResourceRequirements.fromStacks(outputs), liveContents);
        }

        @Override
        public ResourceRequirements liveContents() {
            return liveContents;
        }

        @Override
        public void clearLiveContents(BuildingContext context, BlockPos position) {
            TileEntity tile = context.world().getTileEntity(position);
            if (!(tile instanceof MetaTileEntityHolder holder) || holder.getMetaTileEntity() == null) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                        "The GregTech machine disappeared before its inventory could be cleared");
            }
            MetaTileEntity mte = holder.getMetaTileEntity();
            mte.clearMachineInventory(new ArrayList<>());
            clearFluids(mte.getImportFluids());
            clearFluids(mte.getExportFluids());
        }

        @Override
        public int componentCount() {
            return 5 + covers.size() + (configuration == null ? 0 : configuration.storedStacks().size());
        }

        @Override
        public MteData transformed(ManipulatorTransform transform) {
            EnumFacing transformed = frontFacing == null ? null : transform.apply(frontFacing);
            EnumFacing transformedUpwards = upwardsFacing == null ? null : transform.apply(upwardsFacing);
            List<CoverState> transformedCovers = covers.stream().map(cover -> cover.transformed(transform)).toList();
            return new MteData(placementStack, inputMaterial, transformed, transformedUpwards, paintingColor,
                    smartCopyLink, proxyMaster,
                    transformedCovers, ghostCircuit, configuration == null ? null : configuration.transformed(transform),
                    liveContents);
        }

        private ItemStack placementStack() {
            return placementStack.copy();
        }

        private EnumFacing frontFacing() {
            return frontFacing;
        }

        private EnumFacing upwardsFacing() {
            return upwardsFacing;
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
                    upwardsFacing == data.upwardsFacing &&
                    paintingColor == data.paintingColor && Objects.equals(smartCopyLink, data.smartCopyLink) &&
                    Objects.equals(proxyMaster, data.proxyMaster) &&
                    Objects.equals(covers, data.covers) &&
                    Objects.equals(configuration, data.configuration) &&
                    ItemStack.areItemStacksEqual(ghostCircuit, data.ghostCircuit) &&
                    ItemStack.areItemStacksEqual(placementStack, data.placementStack);
        }

        @Override
        public int hashCode() {
            return Objects.hash(frontFacing, upwardsFacing, paintingColor, placementStack.getItem().getRegistryName(),
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
        private final Boolean energyOverflow;
        private final StructureCheckConfiguration structureCheck;
        private final Integer threadCount;
        private final Integer recipeMapIndex;
        private final IOType longDistanceIoType;
        private final NBTTagCompound meConfiguration;

        private MteConfiguration(boolean muffled, Boolean workingEnabled,
                                 SimpleMachineConfiguration simpleMachine,
                                 ItemHandlingConfiguration itemHandling,
                                 ItemBusFilterConfiguration itemBusFilter,
                                 FluidHatchConfiguration fluidHatch, BufferConfiguration buffer,
                                 Boolean drumAutoOutput, Boolean batchEnabled, Boolean distinct,
                                 Boolean energyLackWarning, Boolean energyOverflow,
                                 StructureCheckConfiguration structureCheck, Integer threadCount,
                                 Integer recipeMapIndex, IOType longDistanceIoType,
                                 NBTTagCompound meConfiguration) {
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
            this.energyOverflow = energyOverflow;
            this.structureCheck = structureCheck;
            this.threadCount = threadCount;
            this.recipeMapIndex = recipeMapIndex;
            this.longDistanceIoType = longDistanceIoType;
            this.meConfiguration = meConfiguration == null ? null : meConfiguration.copy();
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
            Boolean energyOverflow = mte instanceof IGenerator generator ? generator.isEnergyOverFlow() : null;
            StructureCheckConfiguration structureCheck = mte instanceof MultiblockControllerBase controller
                    ? StructureCheckConfiguration.capture(controller) : null;
            Integer threadCount = mte instanceof IThreadHatch hatch
                    ? Math.min(Math.max(hatch.getCurrentThread(), 1), hatch.getMaxThread()) : null;
            Integer recipeMapIndex = mte instanceof IMultipleRecipeMaps maps ? maps.getRecipeMapIndex() : null;
            IOType ioType = mte instanceof MetaTileEntityLongDistanceEndpoint endpoint ? endpoint.getIoType() : null;
            NBTTagCompound meConfiguration = meConfiguration(mte);
            return new MteConfiguration(mte.isMuffled(), workingEnabled, simpleMachine, itemHandling, itemBusFilter,
                    fluidHatch, buffer, drumAutoOutput, batchEnabled, distinct, energyLackWarning, energyOverflow,
                    structureCheck, threadCount, recipeMapIndex, ioType, meConfiguration);
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
            if (energyOverflow != null) {
                if (mte instanceof IGenerator generator) {
                    generator.setEnergyOverFlowMode(energyOverflow);
                } else {
                    incompatible(mte, "energy-overflow mode");
                }
            }
            if (structureCheck != null) {
                if (mte instanceof MultiblockControllerBase controller) {
                    structureCheck.apply(controller);
                } else {
                    incompatible(mte, "structure-check intervals");
                }
            }
            if (threadCount != null) {
                if (mte instanceof IThreadHatch hatch) {
                    hatch.setCurrentThread(threadCount);
                } else {
                    incompatible(mte, "thread count");
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
            if (meConfiguration != null) {
                if (mte instanceof MetaTileEntityMEInputBase me) {
                    me.applygray$importPortableConfiguration(meConfiguration.copy());
                } else if (mte instanceof MetaTileEntityMEDualInputHatch dual) {
                    dual.applygray$importPortableConfiguration(meConfiguration.copy());
                } else {
                    incompatible(mte, "ME configuration");
                }
            }
        }

        private MteConfiguration transformed(ManipulatorTransform transform) {
            return new MteConfiguration(muffled, workingEnabled,
                    simpleMachine == null ? null : simpleMachine.transformed(transform), itemHandling, itemBusFilter,
                    fluidHatch, buffer == null ? null : buffer.transformed(transform), drumAutoOutput, batchEnabled,
                    distinct, energyLackWarning, energyOverflow, structureCheck, threadCount, recipeMapIndex,
                    longDistanceIoType, meConfiguration == null ? null : meConfiguration.copy());
        }

        private List<ItemStack> storedStacks() {
            return itemBusFilter == null ? List.of() : itemBusFilter.storedStacks();
        }

        private static NBTTagCompound meConfiguration(MetaTileEntity mte) {
            if (mte instanceof MetaTileEntityMEInputBase me) return me.applygray$exportPortableConfiguration();
            if (mte instanceof MetaTileEntityMEDualInputHatch dual) {
                return dual.applygray$exportPortableConfiguration();
            }
            return null;
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
                    Objects.equals(energyOverflow, config.energyOverflow) &&
                    Objects.equals(structureCheck, config.structureCheck) &&
                    Objects.equals(threadCount, config.threadCount) &&
                    Objects.equals(recipeMapIndex, config.recipeMapIndex) &&
                    longDistanceIoType == config.longDistanceIoType &&
                    Objects.equals(meConfiguration, config.meConfiguration);
        }

        @Override
        public int hashCode() {
            return Objects.hash(muffled, workingEnabled, simpleMachine, itemHandling, itemBusFilter, fluidHatch, buffer,
                    drumAutoOutput, batchEnabled, distinct, energyLackWarning, energyOverflow, structureCheck,
                    threadCount, recipeMapIndex, longDistanceIoType, meConfiguration);
        }
    }

    /**
     * Structure check pacing on a multiblock controller. The intervals are clamped exactly like the setters clamp them,
     * so a captured value still compares equal after it has been restored.
     */
    private record StructureCheckConfiguration(boolean delayed, int standbyInterval, int workInterval) {

        private static StructureCheckConfiguration capture(MultiblockControllerBase controller) {
            return new StructureCheckConfiguration(controller.isDelayCheck(),
                    clamp(controller.getDelayStructureCheckStandby()), clamp(controller.getDelayStructureCheckWork()));
        }

        private void apply(MultiblockControllerBase controller) {
            controller.setDelayCheck(delayed);
            controller.setDelayStructureCheckStandby(standbyInterval);
            controller.setDelayStructureCheckWork(workInterval);
        }

        private static int clamp(int interval) {
            return Math.max(Math.min(1200, interval), 20);
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
        private final ResourceRequirements liveContents;

        private PipeData(ItemStack pipeStack, BlockSpec inputMaterial, int connections, int blockedConnections,
                         int paintingColor, Material frameMaterial, List<CoverState> covers,
                         ResourceRequirements liveContents) {
            this.pipeStack = checkedStack(pipeStack);
            this.inputMaterial = Objects.requireNonNull(inputMaterial, "inputMaterial");
            this.connections = connections;
            this.blockedConnections = blockedConnections;
            this.paintingColor = paintingColor;
            this.frameMaterial = frameMaterial;
            this.covers = List.copyOf(covers);
            this.liveContents = liveContents == null ? ResourceRequirements.empty() : liveContents;
        }

        private static PipeData forMaterial(ItemStack material) {
            return new PipeData(material, BlockSpec.of(material), 0, 0, -1, null, List.of(),
                    ResourceRequirements.empty());
        }

        @SuppressWarnings({ "rawtypes", "unchecked" })
        private static PipeData capture(BuildingContext context, BlockPos position, IPipeTile<?, ?> pipe,
                                        boolean clearContents) {
            IBlockState state = context.world().getBlockState(position);
            ItemStack pipeStack = state.getBlock().getPickBlock(state,
                    new RayTraceResult(Vec3d.ZERO, EnumFacing.UP, position), context.world(), position,
                    context.player());
            if (pipeStack.isEmpty()) {
                throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                        "The GregTech pipe has no portable item representation");
            }
            Material frame = pipe.getFrameMaterial();
            ResourceRequirements requirements = pipeRequirements(pipeStack, frame);
            return new PipeData(pipeStack, primaryRequirement(requirements), pipe.getConnections(),
                    pipe.getBlockedConnections(), pipe.isPainted() ? pipe.getPaintingColor() : -1, frame,
                    captureCovers(pipe.getCoverableImplementation()),
                    clearContents && pipe instanceof TileEntityFluidPipeTickable fluidPipe
                            ? ResourceRequirements.fromFluids(List.of(fluidPipe.getContainedFluids()))
                            : ResourceRequirements.empty());
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
            return ResourceRequirements.combine(ResourceRequirements.combine(requirements,
                    ResourceRequirements.fromStacks(coverDrops)), liveContents);
        }

        @Override
        public ResourceRequirements liveContents() {
            return liveContents;
        }

        @Override
        public void clearLiveContents(BuildingContext context, BlockPos position) {
            TileEntity tile = context.world().getTileEntity(position);
            if (!(tile instanceof IPipeTile<?, ?> pipe)) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                        "The GregTech pipe disappeared before its contents could be cleared");
            }
            if (pipe instanceof TileEntityFluidPipeTickable fluidPipe) {
                for (FluidTank tank : fluidPipe.getFluidTanks()) {
                    if (tank.getFluidAmount() > 0) {
                        tank.drain(Integer.MAX_VALUE, true);
                    }
                }
            }
        }

        @Override
        public int componentCount() {
            return (frameMaterial == null ? 1 : 2) + covers.size();
        }

        @Override
        public PipeData transformed(ManipulatorTransform transform) {
            List<CoverState> transformedCovers = covers.stream().map(cover -> cover.transformed(transform)).toList();
            return new PipeData(pipeStack, inputMaterial, transform.applyFacingMask(connections),
                    transform.applyFacingMask(blockedConnections), paintingColor, frameMaterial, transformedCovers,
                    liveContents);
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
            if (target.data != null) target.data.clearLiveContents(context, position);
            clearForReplacement(context, position, originalState);
            install(context, position, data);
            if (BuildingEventHooks.isPlayerPlaceCanceled(context, snapshot)) {
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
            data.clearLiveContents(context, position);
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
        private final boolean targetPrecleared;
        private BlockSnapshot sourceSnapshot;
        private BlockSnapshot targetSnapshot;

        private GregTechMoveChange(BuildingContext context, BlockPos source, BlockPos target, IBlockState sourceState,
                                   IBlockState targetState, PortableData data, boolean targetPrecleared) {
            this.context = context;
            this.source = source;
            this.target = target;
            this.sourceState = sourceState;
            this.targetState = targetState;
            this.data = data;
            this.targetPrecleared = targetPrecleared;
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
            return data.liveContents();
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
            if (targetPrecleared) {
                verifyState(target, Blocks.AIR.getDefaultState());
            } else {
                verifyState(target, targetState);
            }
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
            data.clearLiveContents(context, source);
            clearForReplacement(context, source, sourceState);
            install(context, target, data);
            if (BuildingEventHooks.isPlayerPlaceCanceled(context, targetSnapshot)) {
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
