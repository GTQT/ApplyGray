package applygray.mattermanipulator.integration.ae2;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import applygray.mattermanipulator.building.BlockSpec;
import applygray.mattermanipulator.building.BuildingAdapter;
import applygray.mattermanipulator.building.BuildingContext;
import applygray.mattermanipulator.building.BuildingException;
import applygray.mattermanipulator.building.BuildingEventHooks;
import applygray.mattermanipulator.building.CapturedBlock;
import applygray.mattermanipulator.building.PreparedBlockChange;
import applygray.mattermanipulator.inventory.FluidRequirement;
import applygray.mattermanipulator.inventory.ResourceRequirement;
import applygray.mattermanipulator.inventory.ResourceRequirements;
import applygray.mattermanipulator.state.ManipulatorRemovalMode;
import applygray.mattermanipulator.state.ManipulatorTransform;

import ae2.api.inventories.ISegmentedInventory;
import ae2.api.inventories.InternalInventory;
import ae2.api.networking.energy.IAEPowerStorage;
import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import ae2.api.upgrades.IUpgradeableObject;
import ae2.block.AEBaseTileBlock;
import ae2.helpers.ICellWorkbenchHost;
import ae2.helpers.IConfigInvHost;
import ae2.helpers.InterfaceLogicHost;
import ae2.helpers.externalstorage.GenericStackInv;
import ae2.helpers.patternprovider.PatternProviderLogic;
import ae2.tile.AEBaseInvTile;
import ae2.tile.AEBaseTile;
import ae2.tile.crafting.TilePatternProvider;
import ae2.tile.misc.CanerMode;
import ae2.tile.misc.TileCaner;
import ae2.tile.misc.TileCharger;
import ae2.tile.misc.TileCellWorkbench;
import ae2.tile.misc.TileCondenser;
import ae2.tile.misc.TileCrystalAssembler;
import ae2.tile.misc.TileCrystalFixer;
import ae2.tile.misc.TileGrowthAccelerator;
import ae2.tile.misc.TileIngredientBuffer;
import ae2.tile.misc.TileInscriber;
import ae2.tile.misc.TileInterface;
import ae2.tile.misc.TileLightDetector;
import ae2.tile.misc.TileMysteriousCube;
import ae2.tile.misc.TileVibrationChamber;
import ae2.tile.networking.TileController;
import ae2.tile.networking.TileCreativeEnergyCell;
import ae2.tile.networking.TileCrystalResonanceGenerator;
import ae2.tile.networking.TileEnergyAcceptor;
import ae2.tile.networking.TileEnergyCell;
import ae2.tile.networking.TileWirelessAccessPoint;
import ae2.tile.powersink.AEBasePoweredTile;
import ae2.tile.qnb.TileQuantumBridge;
import ae2.tile.spatial.TileSpatialPylon;
import ae2.tile.storage.TileDrive;
import ae2.tile.storage.TileIOPort;
import ae2.tile.storage.TileMEChest;
import ae2.tile.storage.TileSkyChest;
import ae2.tile.storage.TileSkyStoneTank;
import ae2.util.CustomNameUtil;
import ae2.util.SettingsFrom;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fluids.FluidStack;

/** Explicit, transactional adapter for AE2 block entities whose portable state is fully exposed by public APIs. */
public final class Ae2TileBuildingAdapter implements BuildingAdapter {

    private static final String ID = "ae2-tile";
    private static final String EXPORTED_UPGRADES = "exported_upgrades";
    private static final String PATTERNS = "patterns";
    private static final String IO_PORT_INPUT_CELLS = "inputCells";
    private static final String IO_PORT_OUTPUT_CELLS = "outputCells";
    private static final String CELL_WORKBENCH_CONFIG = "applygray_cell_workbench_config";
    private static final String OUTPUT_SIDES = "applygray_output_sides";
    private static final String CANER_MODE = "applygray_caner_mode";
    private static final int WORLD_UPDATE_FLAGS = 3;
    private static final long BASE_EU_PER_COMPONENT = 750L;
    private static final double ENERGY_EPSILON = 0.000_001D;

    private static final Set<Class<? extends AEBaseTile>> SUPPORTED_TILES = Set.of(
            TileDrive.class,
            TileMEChest.class,
            TileCellWorkbench.class,
            TileSkyChest.class,
            TileSkyStoneTank.class,
            TileQuantumBridge.class,
            TileWirelessAccessPoint.class,
            TileIOPort.class,
            TileInterface.class,
            TilePatternProvider.class,
            TileIngredientBuffer.class,
            TileInscriber.class,
            TileCharger.class,
            TileVibrationChamber.class,
            TileCrystalAssembler.class,
            TileCrystalFixer.class,
            TileCondenser.class,
            TileCaner.class,
            TileController.class,
            TileEnergyAcceptor.class,
            TileGrowthAccelerator.class,
            TileCrystalResonanceGenerator.class,
            TileCreativeEnergyCell.class,
            TileEnergyCell.class,
            TileSpatialPylon.class,
            TileLightDetector.class,
            TileMysteriousCube.class);

    private enum CapturePurpose {
        COPY,
        MOVE,
        REMOVE,
        TARGET,
        VERIFY
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean supports(BuildingContext context, BlockPos position, BlockSpec specification) {
        return blockFor(specification) instanceof AEBaseTileBlock<?>;
    }

    @Override
    public PreparedBlockChange prepareApply(BuildingContext context, BlockPos position, BlockSpec specification) {
        Block block = blockFor(specification);
        if (!(block instanceof AEBaseTileBlock<?>)) {
            throw unsupported(position, "The selected material is not an AE2 block entity");
        }
        IBlockState state = specification.toBlockState();
        if (state == null) throw unsupported(position, "The selected AE2 material has invalid block metadata");
        TileEntity prototype = block.createTileEntity(context.world(), state);
        if (!(prototype instanceof AEBaseTile tile) || !isSupported(tile)) {
            String name = prototype == null ? block.getRegistryName().toString() : prototype.getClass().getSimpleName();
            throw unsupported(position, "AE2 " + name + " has no lossless portable contract");
        }
        return preparePlacement(context, position, Ae2TileCaptureData.forMaterial(specification.toStack()), false);
    }

    @Override
    public boolean supportsCapture(BuildingContext context, BlockPos position) {
        return context.world().getTileEntity(position) instanceof AEBaseTile tile && isSupported(tile);
    }

    @Override
    public CapturedBlock capture(BuildingContext context, BlockPos position) {
        validateEditable(context, position);
        Ae2TileCaptureData data = captureTile(context, position, CapturePurpose.COPY);
        return new CapturedBlock(position, data.primaryMaterial(), ID, data);
    }

    @Override
    public CapturedBlock transformCapture(CapturedBlock captured, ManipulatorTransform transform) {
        Objects.requireNonNull(captured, "captured");
        if (!(captured.data() instanceof Ae2TileCaptureData data)) {
            throw new IllegalArgumentException("AE2 tile capture has incompatible data");
        }
        return new CapturedBlock(captured.source(), captured.specification(), ID, data.transformed(transform));
    }

    @Override
    public PreparedBlockChange prepareApplyCaptured(BuildingContext context, BlockPos position, CapturedBlock captured) {
        if (!(captured.data() instanceof Ae2TileCaptureData data)) {
            throw unsupported(position, "The captured block does not contain AE2 tile data");
        }
        return preparePlacement(context, position, data, true);
    }

    @Override
    public PreparedBlockChange prepareRemove(BuildingContext context, BlockPos position) {
        IBlockState originalState = validateEditable(context, position);
        requireRemoval(context, position, originalState);
        return new RemovalChange(context, position, originalState,
                captureTile(context, position, CapturePurpose.REMOVE));
    }

    @Override
    public boolean supportsMove(BuildingContext context, BlockPos source, BlockPos target) {
        return context.world().getTileEntity(source) instanceof AEBaseTile tile && isSupported(tile) &&
                isAir(context, target, context.world().getBlockState(target));
    }

    @Override
    public PreparedBlockChange prepareMove(BuildingContext context, BlockPos source, BlockPos target) {
        if (source.equals(target)) {
            throw new BuildingException(BuildingException.Reason.OVERLAPPING_MOVE, source,
                    "An AE2 move source and target cannot be the same block");
        }
        IBlockState sourceState = validateEditable(context, source);
        IBlockState targetState = validateEditable(context, target);
        if (!isAir(context, target, targetState)) {
            throw unsupported(target, "Moving an AE2 block entity requires an empty destination");
        }
        if (!context.world().checkNoEntityCollision(new AxisAlignedBB(target))) {
            throw new BuildingException(BuildingException.Reason.CANNOT_PLACE, target,
                    "An entity blocks the AE2 move destination");
        }
        return new MoveChange(context, source, target, sourceState, targetState,
                captureTile(context, source, CapturePurpose.MOVE));
    }

    private static PreparedBlockChange preparePlacement(BuildingContext context, BlockPos position,
                                                         Ae2TileCaptureData data, boolean verifyCapture) {
        IBlockState originalState = validateEditable(context, position);
        TargetContents target = inspectTarget(context, position, originalState);
        requireReplacement(context, position, originalState);
        if (!context.world().checkNoEntityCollision(new AxisAlignedBB(position))) {
            throw new BuildingException(BuildingException.Reason.CANNOT_PLACE, position,
                    "An entity blocks the AE2 block destination");
        }
        return new PlacementChange(context, position, originalState, target, data, verifyCapture);
    }

    private static TargetContents inspectTarget(BuildingContext context, BlockPos position, IBlockState state) {
        TileEntity tile = context.world().getTileEntity(position);
        if (tile instanceof AEBaseTile) {
            Ae2TileCaptureData data = captureTile(context, position, CapturePurpose.TARGET);
            return new TargetContents(data.producedResources(), data);
        }
        if (tile != null || state.getBlock().hasTileEntity(state)) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_TILE_ENTITY, position,
                    "The destination TileEntity requires its own Matter Manipulator adapter");
        }
        if (isAir(context, position, state)) return TargetContents.empty();
        NonNullList<ItemStack> drops = NonNullList.create();
        state.getBlock().getDrops(drops, context.world(), position, state, 0);
        return new TargetContents(ResourceRequirements.fromStacks(drops), null);
    }

    private static Ae2TileCaptureData captureTile(BuildingContext context, BlockPos position,
                                                  CapturePurpose purpose) {
        TileEntity blockEntity = context.world().getTileEntity(position);
        if (!(blockEntity instanceof AEBaseTile tile)) {
            throw unsupported(position, "The target is not an AE2 block entity");
        }
        requireSupported(position, tile);
        IBlockState state = context.world().getBlockState(position);
        if (!(state.getBlock() instanceof AEBaseTileBlock<?>)) {
            throw unsupported(position, "The AE2 tile is not hosted by an AE2 tile block");
        }
        if (state.getBlockHardness(context.world(), position) < 0.0F) {
            throw new BuildingException(BuildingException.Reason.UNBREAKABLE, position,
                    "The AE2 block entity is unbreakable");
        }
        if (tile instanceof TileInterface interfaceTile &&
                !interfaceTile.getInterfaceLogic().getRequestedJobs().isEmpty()) {
            throw unsupported(position, "An AE2 Interface with active crafting requests cannot be reconstructed safely");
        }
        rejectRunningState(position, tile);

        double storedEnergy = captureStoredEnergy(position, tile, purpose);
        List<ItemStack> blockDrops = captureBlockDrops(context, position, state);
        ItemStack placementStack = capturePlacementStack(position, tile, blockDrops, purpose);
        NBTTagCompound settings = capturePortableSettings(tile);

        List<Ae2BusCaptureData.InventoryStack> inventory = captureInventory(portableInventory(tile));
        List<Ae2BusCaptureData.InventoryStack> upgrades = List.of();
        List<Ae2BusCaptureData.InventoryStack> patterns = List.of();
        boolean patternProvider = tile instanceof TilePatternProvider;
        if (patternProvider) {
            PatternProviderLogic logic = ((TilePatternProvider) tile).getLogic();
            patterns = captureInventory(logic.getPatternInv());
            upgrades = captureInventory(logic.getUpgrades());
            settings.removeTag(PATTERNS);
        } else if (!(tile instanceof TileCellWorkbench) && tile instanceof IUpgradeableObject upgradeable) {
            upgrades = captureInventory(upgradeable.getUpgrades());
        }

        List<Ae2TileCaptureData.GenericInventoryStack> genericInventory = captureGenericInventory(position, tile);
        List<FluidStack> storedFluids = captureStoredFluids(tile);
        List<ResourceRequirement> configuredItems = new ArrayList<>();
        List<FluidRequirement> configuredFluids = new ArrayList<>();
        captureConfiguredResources(position, tile, configuredItems, configuredFluids);

        List<ItemStack> additionalDrops = new ArrayList<>();
        tile.addAdditionalDrops(additionalDrops);
        List<ItemStack> expectedDrops = new ArrayList<>();
        expectedDrops.addAll(stacksFrom(inventory));
        expectedDrops.addAll(stacksFrom(upgrades));
        expectedDrops.addAll(stacksFrom(patterns));
        addGenericDrops(context, position, genericInventory, expectedDrops);
        if (!sameStacks(additionalDrops, expectedDrops)) {
            throw unsupported(position,
                    "The AE2 block has additional inventory or running-state drops outside its explicit portable stores");
        }

        return new Ae2TileCaptureData(placementStack, blockDrops, additionalDrops, settings, tile.getForward(),
                tile.getUp(), inventory, upgrades, patterns, configuredItems, configuredFluids, genericInventory,
                storedFluids, storedEnergy, patternProvider);
    }

    private static NBTTagCompound capturePortableSettings(AEBaseTile tile) {
        NBTTagCompound settings = tile.exportSettings(SettingsFrom.MEMORY_CARD);
        settings.removeTag(EXPORTED_UPGRADES);
        if (tile instanceof TileCellWorkbench workbench) {
            NBTTagList config = workbench.getConfig().writeToTag();
            if (config.tagCount() > 0) settings.setTag(CELL_WORKBENCH_CONFIG, config.copy());
        }
        if (tile instanceof TileInscriber inscriber) {
            settings.setInteger(OUTPUT_SIDES, facingMask(inscriber.getOutputSides()));
        } else if (tile instanceof TileCrystalAssembler assembler) {
            settings.setInteger(OUTPUT_SIDES, facingMask(assembler.getOutputSides()));
        } else if (tile instanceof TileCaner caner) {
            settings.setInteger(CANER_MODE, caner.getMode().ordinal());
        }
        return settings;
    }

    private static void rejectRunningState(BlockPos position, AEBaseTile tile) {
        boolean running = tile instanceof TileInscriber inscriber && inscriber.getProcessingTime() != 0 ||
                tile instanceof TileCharger charger && charger.isWorking() ||
                tile instanceof TileVibrationChamber chamber &&
                        chamber.getRemainingFuelTicks() > ENERGY_EPSILON ||
                tile instanceof TileCrystalAssembler assembler && assembler.getProcessingTime() != 0 ||
                tile instanceof TileCrystalFixer fixer && fixer.getProgress() != 0 ||
                tile instanceof TileCondenser condenser && condenser.getStoredPower() > ENERGY_EPSILON;
        if (tile instanceof TileCaner && (!(tile instanceof PortableCanerRuntimeStateAccess access) ||
                access.applygray$hasInFlightCanerState())) {
            running = true;
        }
        if (running) {
            throw unsupported(position,
                    "The AE2 processing machine has progress, consumed fuel, or an in-flight result that cannot be cloned");
        }
    }

    private static ItemStack capturePlacementStack(BlockPos position, AEBaseTile tile, List<ItemStack> blockDrops,
                                                   CapturePurpose purpose) {
        ItemStack base = tile.getItemFromTile();
        if (base.isEmpty()) {
            for (ItemStack drop : blockDrops) {
                if (drop.getItem() instanceof ItemBlock itemBlock && itemBlock.getBlock() == tile.getBlockType()) {
                    base = drop.copy();
                    break;
                }
            }
        }
        if (base.isEmpty()) throw unsupported(position, "The AE2 block has no portable placement item");
        base.setCount(1);
        base.setTagCompound(null);

        NBTTagCompound dismantle = tile.exportSettings(SettingsFrom.DISMANTLE_ITEM);
        NBTTagCompound nonConfiguration = dismantle.copy();
        nonConfiguration.removeTag(CustomNameUtil.CUSTOM_NAME_TAG);
        if (tile instanceof TileIOPort) {
            nonConfiguration.removeTag(IO_PORT_INPUT_CELLS);
            nonConfiguration.removeTag(IO_PORT_OUTPUT_CELLS);
        }
        if (purpose == CapturePurpose.COPY && !nonConfiguration.isEmpty()) {
            throw unsupported(position,
                    "The AE2 block carries dismantle-only energy or identity state and cannot be duplicated");
        }
        NBTTagCompound placementSettings = new NBTTagCompound();
        if (dismantle.hasKey(CustomNameUtil.CUSTOM_NAME_TAG, Constants.NBT.TAG_STRING)) {
            placementSettings.setString(CustomNameUtil.CUSTOM_NAME_TAG,
                    dismantle.getString(CustomNameUtil.CUSTOM_NAME_TAG));
        }
        if (!placementSettings.isEmpty()) base.setTagCompound(placementSettings);
        return base;
    }

    private static double captureStoredEnergy(BlockPos position, AEBaseTile tile, CapturePurpose purpose) {
        double storedEnergy = 0.0D;
        if (tile instanceof AEBasePoweredTile poweredTile) {
            storedEnergy = poweredTile.getInternalCurrentPower();
        } else if (tile instanceof TileEnergyCell energyCell) {
            storedEnergy = energyCell.getAECurrentPower();
        } else if (tile instanceof IAEPowerStorage storage && !(tile instanceof TileCreativeEnergyCell)) {
            storedEnergy = storage.getAECurrentPower();
        }
        if (storedEnergy > ENERGY_EPSILON && purpose != CapturePurpose.MOVE && purpose != CapturePurpose.VERIFY) {
            throw unsupported(position,
                    "Stored AE energy cannot enter an item/fluid transaction; move the block after disconnecting power");
        }
        return storedEnergy;
    }

    private static List<ItemStack> captureBlockDrops(BuildingContext context, BlockPos position, IBlockState state) {
        NonNullList<ItemStack> drops = NonNullList.create();
        state.getBlock().getDrops(drops, context.world(), position, state, 0);
        long blockItems = drops.stream().filter(stack -> stack.getItem() instanceof ItemBlock itemBlock &&
                itemBlock.getBlock() == state.getBlock()).count();
        if (blockItems != 1L) {
            throw unsupported(position, "The AE2 block does not have exactly one portable block drop");
        }
        return List.copyOf(drops);
    }

    private static InternalInventory portableInventory(AEBaseTile tile) {
        if (tile instanceof ICellWorkbenchHost workbench) {
            InternalInventory cells = workbench.getSubInventory(ISegmentedInventory.CELLS);
            return cells == null ? InternalInventory.empty() : cells;
        }
        return tile instanceof AEBaseInvTile inventoryTile ? inventoryTile.getInternalInventory()
                : InternalInventory.empty();
    }

    private static List<Ae2BusCaptureData.InventoryStack> captureInventory(InternalInventory inventory) {
        List<Ae2BusCaptureData.InventoryStack> captured = new ArrayList<>();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty()) captured.add(new Ae2BusCaptureData.InventoryStack(slot, stack));
        }
        return List.copyOf(captured);
    }

    private static List<Ae2TileCaptureData.GenericInventoryStack> captureGenericInventory(BlockPos position,
                                                                                           AEBaseTile tile) {
        GenericStackInv generic = genericInventory(tile);
        if (generic == null) return List.of();
        List<Ae2TileCaptureData.GenericInventoryStack> captured = new ArrayList<>();
        for (int slot = 0; slot < generic.size(); slot++) {
            GenericStack stack = generic.getStack(slot);
            if (stack == null || stack.amount() <= 0) continue;
            if (stack.what() instanceof AEItemKey itemKey) {
                captured.add(new Ae2TileCaptureData.GenericInventoryStack(slot, itemKey.toStack(), null,
                        stack.amount()));
            } else if (stack.what() instanceof AEFluidKey fluidKey) {
                captured.add(new Ae2TileCaptureData.GenericInventoryStack(slot, null, fluidKey.toStack(1),
                        stack.amount()));
            } else {
                throw unsupported(position, "The AE2 generic inventory contains an unsupported resource type");
            }
        }
        return List.copyOf(captured);
    }

    private static GenericStackInv genericInventory(AEBaseTile tile) {
        if (tile instanceof InterfaceLogicHost interfaceHost) return interfaceHost.getStorage();
        if (tile instanceof TileIngredientBuffer ingredientBuffer) return ingredientBuffer.getBuffer();
        if (tile instanceof TileCrystalAssembler assembler) return assembler.getTank();
        if (tile instanceof TileCaner caner) return caner.getGenericInv();
        return null;
    }

    private static List<FluidStack> captureStoredFluids(AEBaseTile tile) {
        if (!(tile instanceof TileSkyStoneTank tank)) return List.of();
        FluidStack contents = tank.getTank().getFluid();
        return contents == null || contents.amount <= 0 ? List.of() : List.of(contents.copy());
    }

    private static void captureConfiguredResources(BlockPos position, AEBaseTile tile,
                                                   List<ResourceRequirement> items,
                                                   List<FluidRequirement> fluids) {
        GenericStackInv config = tile instanceof IConfigInvHost host ? host.getConfig()
                : tile instanceof TileCellWorkbench workbench ? workbench.getConfig() : null;
        if (config == null) return;
        for (int slot = 0; slot < config.size(); slot++) {
            GenericStack stack = config.getStack(slot);
            if (stack == null || stack.amount() <= 0) continue;
            if (stack.what() instanceof AEItemKey itemKey) {
                items.add(new ResourceRequirement(BlockSpec.of(itemKey.toStack()), stack.amount()));
            } else if (stack.what() instanceof AEFluidKey fluidKey) {
                fluids.add(new FluidRequirement(fluidKey.toStack(1), stack.amount()));
            } else {
                throw unsupported(position, "The AE2 configuration contains an unsupported resource type");
            }
        }
    }

    private static void addGenericDrops(BuildingContext context, BlockPos position,
                                        List<Ae2TileCaptureData.GenericInventoryStack> genericInventory,
                                        List<ItemStack> drops) {
        for (Ae2TileCaptureData.GenericInventoryStack entry : genericInventory) {
            if (entry.item() != null) {
                AEItemKey.of(entry.item()).addDrops(entry.amount(), drops, context.world(), position);
            } else {
                AEFluidKey.of(entry.fluid()).addDrops(entry.amount(), drops, context.world(), position);
            }
        }
    }

    private static List<ItemStack> stacksFrom(List<Ae2BusCaptureData.InventoryStack> contents) {
        return contents.stream().map(Ae2BusCaptureData.InventoryStack::stack).toList();
    }

    private static boolean sameStacks(List<ItemStack> left, List<ItemStack> right) {
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

    private static void installTile(BuildingContext context, BlockPos position, Ae2TileCaptureData data,
                                    boolean verifyCapture) {
        ItemStack placement = data.placementStack();
        Block block = Block.getBlockFromItem(placement.getItem());
        if (!(block instanceof AEBaseTileBlock<?>)) {
            throw unsupported(position, "The captured AE2 placement item is no longer an AE2 tile block");
        }
        IBlockState targetState = BlockSpec.of(placement).toBlockState();
        if (targetState == null) {
            throw unsupported(position, "The captured AE2 placement item has invalid block metadata");
        }
        if (!context.world().mayPlace(block, position, false, EnumFacing.UP, context.player())) {
            throw new BuildingException(BuildingException.Reason.CANNOT_PLACE, position,
                    "Minecraft rejected the AE2 block placement");
        }
        if (!context.world().setBlockState(position, targetState, WORLD_UPDATE_FLAGS)) {
            throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                    "Minecraft rejected the AE2 block state");
        }
        block.onBlockPlacedBy(context.world(), position, targetState, context.player(), placement);
        if (!(context.world().getTileEntity(position) instanceof AEBaseTile tile)) {
            throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                    "AE2 did not create the captured block entity");
        }
        requireSupported(position, tile);

        restoreInventory(position, portableInventory(tile), data.inventory());
        if (data.patternProvider()) {
            if (!(tile instanceof TilePatternProvider provider)) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                        "AE2 did not create the captured Pattern Provider");
            }
            restoreInventory(position, provider.getLogic().getPatternInv(), data.patterns());
            restoreInventory(position, provider.getLogic().getUpgrades(), data.upgrades());
        } else if (!data.upgrades().isEmpty()) {
            if (!(tile instanceof IUpgradeableObject upgradeable)) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                        "The placed AE2 block cannot restore its upgrades");
            }
            restoreInventory(position, upgradeable.getUpgrades(), data.upgrades());
        }
        restoreGenericInventory(position, tile, data.genericInventory());
        restoreStoredFluids(position, tile, data.storedFluids());
        restorePortableSettings(position, tile, data.settings());
        if (data.forward() != null) tile.setOrientation(data.forward(), data.up());
        restoreStoredEnergy(position, tile, data.storedEnergy());
        if (tile instanceof TilePatternProvider provider) provider.getLogic().updatePatterns();
        tile.saveChanges();
        tile.markForUpdate();

        if (verifyCapture && !data.equals(captureTile(context, position, CapturePurpose.VERIFY))) {
            throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                    "The placed AE2 block did not retain its captured portable state");
        }
    }

    private static void restoreInventory(BlockPos position, InternalInventory inventory,
                                         List<Ae2BusCaptureData.InventoryStack> contents) {
        for (Ae2BusCaptureData.InventoryStack content : contents) {
            if (content.slot() >= inventory.size() || !inventory.getStackInSlot(content.slot()).isEmpty()) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                        "The AE2 inventory layout changed during restoration");
            }
            inventory.setItemDirect(content.slot(), content.stack());
        }
    }

    private static void restoreGenericInventory(BlockPos position, AEBaseTile tile,
                                                List<Ae2TileCaptureData.GenericInventoryStack> contents) {
        if (contents.isEmpty()) return;
        GenericStackInv inventory = genericInventory(tile);
        if (inventory == null) {
            throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                    "The placed AE2 block has no generic inventory");
        }
        for (Ae2TileCaptureData.GenericInventoryStack content : contents) {
            if (content.slot() >= inventory.size() || inventory.getStack(content.slot()) != null) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                        "The AE2 generic inventory layout changed during restoration");
            }
            GenericStack stack = content.item() != null ? new GenericStack(AEItemKey.of(content.item()), content.amount())
                    : new GenericStack(AEFluidKey.of(content.fluid()), content.amount());
            inventory.setStack(content.slot(), stack);
        }
    }

    private static void restoreStoredFluids(BlockPos position, AEBaseTile tile, List<FluidStack> contents) {
        if (contents.isEmpty()) return;
        if (!(tile instanceof TileSkyStoneTank tank) || contents.size() != 1) {
            throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                    "The placed AE2 block cannot restore its fluid inventory");
        }
        FluidStack fluid = contents.getFirst();
        if (tank.getTank().fill(fluid, true) != fluid.amount) {
            throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                    "The placed AE2 tank rejected its captured fluid");
        }
    }

    private static void restorePortableSettings(BlockPos position, AEBaseTile tile, NBTTagCompound settings) {
        NBTTagCompound portable = settings.copy();
        NBTTagList workbenchConfig = portable.hasKey(CELL_WORKBENCH_CONFIG, Constants.NBT.TAG_LIST)
                ? portable.getTagList(CELL_WORKBENCH_CONFIG, Constants.NBT.TAG_COMPOUND).copy() : null;
        portable.removeTag(CELL_WORKBENCH_CONFIG);
        Integer outputSides = portable.hasKey(OUTPUT_SIDES, Constants.NBT.TAG_INT)
                ? portable.getInteger(OUTPUT_SIDES) : null;
        portable.removeTag(OUTPUT_SIDES);
        Integer canerMode = portable.hasKey(CANER_MODE, Constants.NBT.TAG_INT)
                ? portable.getInteger(CANER_MODE) : null;
        portable.removeTag(CANER_MODE);
        tile.importSettings(SettingsFrom.MEMORY_CARD, portable, null);
        if (workbenchConfig != null) {
            if (!(tile instanceof TileCellWorkbench workbench)) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                        "The placed AE2 block cannot restore Cell Workbench configuration");
            }
            workbench.getConfig().readFromTag(workbenchConfig);
        }
        if (outputSides != null) restoreOutputSides(position, tile, outputSides);
        if (canerMode != null) restoreCanerMode(position, tile, canerMode);
    }

    private static int facingMask(Iterable<EnumFacing> sides) {
        int mask = 0;
        for (EnumFacing side : sides) mask |= 1 << side.getIndex();
        return mask;
    }

    private static void restoreOutputSides(BlockPos position, AEBaseTile tile, int mask) {
        if (tile instanceof TileInscriber inscriber) {
            for (EnumFacing side : EnumFacing.VALUES) {
                inscriber.setOutputSideEnabled(side, (mask & 1 << side.getIndex()) != 0);
            }
        } else if (tile instanceof TileCrystalAssembler assembler) {
            for (EnumFacing side : EnumFacing.VALUES) {
                assembler.setOutputSideEnabled(side, (mask & 1 << side.getIndex()) != 0);
            }
        } else {
            throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                    "The placed AE2 block cannot restore output-side configuration");
        }
    }

    private static void restoreCanerMode(BlockPos position, AEBaseTile tile, int ordinal) {
        if (!(tile instanceof TileCaner caner) || ordinal < 0 || ordinal >= CanerMode.values().length) {
            throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                    "The placed AE2 block cannot restore Caner mode");
        }
        caner.setMode(CanerMode.values()[ordinal]);
    }

    private static void restoreStoredEnergy(BlockPos position, AEBaseTile tile, double energy) {
        if (energy <= ENERGY_EPSILON) return;
        if (tile instanceof AEBasePoweredTile poweredTile) {
            poweredTile.setInternalCurrentPower(energy);
        } else if (tile instanceof TileEnergyCell energyCell) {
            double overflow = energyCell.injectAEPower(energy, ae2.api.config.Actionable.MODULATE);
            if (overflow > ENERGY_EPSILON) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                        "The placed AE2 Energy Cell rejected captured energy");
            }
        } else {
            throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                    "The placed AE2 block cannot restore captured energy");
        }
    }

    private static void clearForReplacement(BuildingContext context, BlockPos position, IBlockState originalState) {
        if (isAir(context, position, originalState)) return;
        TileEntity tile = context.world().getTileEntity(position);
        if (tile instanceof AEBaseTile aeTile) {
            aeTile.clearContent();
        } else {
            originalState.getBlock().onBlockHarvested(context.world(), position, originalState, context.player());
        }
        if (!context.world().setBlockState(position, Blocks.AIR.getDefaultState(), WORLD_UPDATE_FLAGS)) {
            throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                    "Minecraft rejected clearing the AE2 destination");
        }
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

    private static Block blockFor(BlockSpec specification) {
        ItemStack stack = specification.toStack();
        return stack.isEmpty() ? Blocks.AIR : Block.getBlockFromItem(stack.getItem());
    }

    private static boolean isSupported(AEBaseTile tile) {
        return SUPPORTED_TILES.contains(tile.getClass());
    }

    private static void requireSupported(BlockPos position, AEBaseTile tile) {
        if (!isSupported(tile)) {
            throw unsupported(position, "AE2 " + tile.getClass().getSimpleName() +
                    " has processing, link, ticket, or other state without a lossless portable contract");
        }
    }

    private static BuildingException unsupported(BlockPos position, String message) {
        return new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position, message);
    }

    private static long energyCost(BuildingContext context, BlockPos position, int componentCount) {
        double distance = Math.max(1.0D, context.player().getDistance(position.getX(), position.getY(), position.getZ()));
        double cost = BASE_EU_PER_COMPONENT * Math.max(1, componentCount) * Math.sqrt(distance);
        if (context.powerEfficiency()) cost *= 0.5D;
        return cost >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) Math.ceil(cost);
    }

    private record TargetContents(ResourceRequirements outputs, Ae2TileCaptureData data) {
        private static TargetContents empty() {
            return new TargetContents(ResourceRequirements.empty(), null);
        }
    }

    private abstract static class TileChange implements PreparedBlockChange {
        final BuildingContext context;
        final BlockPos position;
        final IBlockState originalState;
        BlockSnapshot snapshot;

        TileChange(BuildingContext context, BlockPos position, IBlockState originalState) {
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
                        "The target changed after the AE2 operation was prepared");
            }
        }
    }

    private static final class PlacementChange extends TileChange {
        private final TargetContents target;
        private final Ae2TileCaptureData data;
        private final boolean verifyCapture;

        PlacementChange(BuildingContext context, BlockPos position, IBlockState originalState, TargetContents target,
                        Ae2TileCaptureData data, boolean verifyCapture) {
            super(context, position, originalState);
            this.target = target;
            this.data = data;
            this.verifyCapture = verifyCapture;
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
            return target.outputs();
        }

        @Override
        public long energyCost() {
            return Ae2TileBuildingAdapter.energyCost(context, position, data.componentCount());
        }

        @Override
        public void apply() {
            verifyOriginalState();
            if (target.data() != null &&
                    !target.data().equals(captureTile(context, position, CapturePurpose.TARGET))) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                        "The destination AE2 block changed after preparation");
            }
            snapshot = BlockSnapshot.getBlockSnapshot(context.world(), position);
            clearForReplacement(context, position, originalState);
            installTile(context, position, data, verifyCapture);
            if (BuildingEventHooks.isPlayerPlaceCanceled(context, snapshot)) {
                throw new BuildingException(BuildingException.Reason.PERMISSION_DENIED, position,
                        "A protection handler denied the AE2 block placement");
            }
        }
    }

    private static final class RemovalChange extends TileChange {
        private final Ae2TileCaptureData data;

        RemovalChange(BuildingContext context, BlockPos position, IBlockState originalState,
                      Ae2TileCaptureData data) {
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
            return Ae2TileBuildingAdapter.energyCost(context, position, data.componentCount());
        }

        @Override
        public void apply() {
            verifyOriginalState();
            if (!data.equals(captureTile(context, position, CapturePurpose.REMOVE))) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                        "The AE2 block changed after removal was prepared");
            }
            if (MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(context.world(), position, originalState,
                    context.player()))) {
                throw new BuildingException(BuildingException.Reason.PERMISSION_DENIED, position,
                        "A protection handler denied the AE2 block removal");
            }
            snapshot = BlockSnapshot.getBlockSnapshot(context.world(), position);
            clearForReplacement(context, position, originalState);
        }
    }

    private static final class MoveChange implements PreparedBlockChange {
        private final BuildingContext context;
        private final BlockPos source;
        private final BlockPos target;
        private final IBlockState sourceState;
        private final IBlockState targetState;
        private final Ae2TileCaptureData data;
        private BlockSnapshot sourceSnapshot;
        private BlockSnapshot targetSnapshot;

        MoveChange(BuildingContext context, BlockPos source, BlockPos target, IBlockState sourceState,
                   IBlockState targetState, Ae2TileCaptureData data) {
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
            return Ae2TileBuildingAdapter.energyCost(context, source, data.componentCount()) +
                    Ae2TileBuildingAdapter.energyCost(context, target, data.componentCount());
        }

        @Override
        public boolean changesWorld() {
            return true;
        }

        @Override
        public void apply() {
            verifyState(source, sourceState);
            verifyState(target, targetState);
            if (!data.equals(captureTile(context, source, CapturePurpose.MOVE))) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, source,
                        "The source AE2 block changed after the move was prepared");
            }
            if (MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(context.world(), source, sourceState,
                    context.player()))) {
                throw new BuildingException(BuildingException.Reason.PERMISSION_DENIED, source,
                        "A protection handler denied the AE2 source move");
            }
            sourceSnapshot = BlockSnapshot.getBlockSnapshot(context.world(), source);
            targetSnapshot = BlockSnapshot.getBlockSnapshot(context.world(), target);
            clearForReplacement(context, source, sourceState);
            installTile(context, target, data, true);
            if (BuildingEventHooks.isPlayerPlaceCanceled(context, targetSnapshot)) {
                throw new BuildingException(BuildingException.Reason.PERMISSION_DENIED, target,
                        "A protection handler denied the AE2 move destination");
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
                        "An AE2 move source or target changed after preparation");
            }
        }
    }
}
