package applygray.mattermanipulator.integration.ae2;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import applygray.mattermanipulator.building.BlockSpec;
import applygray.mattermanipulator.building.BuildingAdapter;
import applygray.mattermanipulator.building.BuildingContext;
import applygray.mattermanipulator.building.BuildingException;
import applygray.mattermanipulator.building.BuildingEventHooks;
import applygray.mattermanipulator.building.CapturedBlock;
import applygray.mattermanipulator.building.PreparedBlockChange;
import applygray.mattermanipulator.inventory.ResourceRequirements;
import applygray.mattermanipulator.inventory.ResourceRequirement;
import applygray.mattermanipulator.inventory.FluidRequirement;
import applygray.mattermanipulator.state.ManipulatorRemovalMode;
import applygray.mattermanipulator.state.ManipulatorTransform;

import ae2.api.implementations.items.IFacadeItem;
import ae2.api.inventories.InternalInventory;
import ae2.api.implementations.parts.ICablePart;
import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import ae2.api.upgrades.IUpgradeableObject;
import ae2.api.parts.IFacadePart;
import ae2.api.parts.IPart;
import ae2.api.parts.IPartHost;
import ae2.api.parts.IPartItem;
import ae2.api.parts.PartHelper;
import ae2.helpers.patternprovider.PatternProviderLogic;
import ae2.helpers.IConfigInvHost;
import ae2.helpers.externalstorage.GenericStackInv;
import ae2.parts.crafting.PatternProviderPart;
import ae2.parts.misc.InterfacePart;
import ae2.parts.p2p.P2PTunnelPart;
import ae2.tile.networking.TileCableBus;
import ae2.util.SettingsFrom;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.NonNullList;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fluids.FluidStack;

/**
 * Safe, target-native adapter for AE2 Supergiant cable buses.
 *
 * <p>The portable representation is made only from public dismantle-item settings and facade stacks. A part that
 * exposes additional drops is rejected instead of silently discarding inventory-backed state. Block snapshots are
 * used exclusively for transactional rollback, never as an interchangeable copy payload.</p>
 */
public final class Ae2BuildingAdapter implements BuildingAdapter {

    private static final String ID = "ae2-cable-bus";
    private static final int WORLD_UPDATE_FLAGS = 3;
    private static final long BASE_EU_PER_COMPONENT = 750L;

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
        return isCenterCable(specification);
    }

    @Override
    public PreparedBlockChange prepareApply(BuildingContext context, BlockPos position, BlockSpec specification) {
        if (!isCenterCable(specification)) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                    "The selected AE2 material is not a center cable");
        }
        return preparePlacement(context, position, Ae2BusCaptureData.singleCable(specification.toStack()));
    }

    @Override
    public boolean absorbsTargetContents(BuildingContext context, BlockPos position) {
        // An existing bus is merged into rather than cleared, so this adapter already accounts for its parts.
        return context.world().getTileEntity(position) instanceof TileCableBus ||
                !BuildingAdapter.hasTileEntity(context, position);
    }

    @Override
    public PreparedBlockChange prepareApplyAfterTargetRemoval(BuildingContext context, BlockPos position,
                                                              BlockSpec specification) {
        if (!isCenterCable(specification)) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                    "The selected AE2 material is not a center cable");
        }
        validateEditable(context, position);
        requireEmptyDestination(context, position);
        return new Ae2PlacementChange(context, position, Blocks.AIR.getDefaultState(), TargetContents.empty(),
                Ae2BusCaptureData.singleCable(specification.toStack()));
    }

    @Override
    public boolean supportsCapture(BuildingContext context, BlockPos position) {
        return context.world().getTileEntity(position) instanceof TileCableBus;
    }

    @Override
    public CapturedBlock capture(BuildingContext context, BlockPos position) {
        validateEditable(context, position);
        Ae2BusCaptureData data = captureBus(context, position, context.smartCopyEnabled(), CapturePurpose.COPY);
        return new CapturedBlock(position, data.primaryMaterial(), "", data);
    }

    @Override
    public CapturedBlock transformCapture(CapturedBlock captured, ManipulatorTransform transform) {
        Objects.requireNonNull(captured, "captured");
        if (!(captured.data() instanceof Ae2BusCaptureData data)) {
            throw new IllegalArgumentException("AE2 cable bus capture has incompatible data");
        }
        return new CapturedBlock(captured.source(), captured.specification(), captured.adapterId(),
                data.transformed(transform));
    }

    @Override
    public PreparedBlockChange prepareApplyCaptured(BuildingContext context, BlockPos position, CapturedBlock captured) {
        if (!(captured.data() instanceof Ae2BusCaptureData data)) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                    "The captured block does not contain AE2 cable bus data");
        }
        return preparePlacement(context, position, data);
    }

    @Override
    public PreparedBlockChange prepareRemove(BuildingContext context, BlockPos position) {
        IBlockState originalState = validateEditable(context, position);
        if (!(context.world().getTileEntity(position) instanceof TileCableBus)) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                    "The target is not an AE2 cable bus");
        }
        requireRemoval(context, position, originalState);
        return new Ae2RemovalChange(context, position, originalState,
                captureBus(context, position, false, CapturePurpose.REMOVE));
    }

    @Override
    public boolean supportsMove(BuildingContext context, BlockPos source, BlockPos target) {
        if (!(context.world().getTileEntity(source) instanceof TileCableBus)) return false;
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
        if (!(context.world().getTileEntity(source) instanceof TileCableBus)) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, source,
                    "The move source is not an AE2 cable bus");
        }
        if (!targetPrecleared && !isAir(context, target, targetState)) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, target,
                    "Moving an AE2 cable bus currently requires an empty destination");
        }
        if (!context.world().checkNoEntityCollision(new AxisAlignedBB(target))) {
            throw new BuildingException(BuildingException.Reason.CANNOT_PLACE, target,
                    "An entity blocks the AE2 cable bus destination");
        }
        return new Ae2MoveChange(context, source, target, sourceState, targetState,
                captureBus(context, source, false, CapturePurpose.MOVE), targetPrecleared);
    }

    private static PreparedBlockChange preparePlacement(BuildingContext context, BlockPos position,
                                                         Ae2BusCaptureData data) {
        IBlockState originalState = validateEditable(context, position);
        TargetContents target = inspectTarget(context, position, originalState);
        requireReplacement(context, position, originalState);
        requireEmptyDestination(context, position);
        return new Ae2PlacementChange(context, position, originalState, target, data);
    }

    private static void requireEmptyDestination(BuildingContext context, BlockPos position) {
        if (!context.world().checkNoEntityCollision(new AxisAlignedBB(position))) {
            throw new BuildingException(BuildingException.Reason.CANNOT_PLACE, position,
                    "An entity blocks the AE2 cable bus destination");
        }
    }

    private static TargetContents inspectTarget(BuildingContext context, BlockPos position, IBlockState state) {
        TileEntity tile = context.world().getTileEntity(position);
        if (tile instanceof TileCableBus) {
            IPartHost host = (IPartHost) tile;
            if (host.getPart(null) == null) return TargetContents.empty();
            Ae2BusCaptureData data = captureBus(context, position, false, CapturePurpose.TARGET);
            return new TargetContents(data.producedResources(), data);
        }
        if (tile != null || state.getBlock().hasTileEntity(state)) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_TILE_ENTITY, position,
                    "The target TileEntity requires its own Matter Manipulator adapter");
        }
        if (isAir(context, position, state)) return TargetContents.empty();

        NonNullList<ItemStack> drops = NonNullList.create();
        state.getBlock().getDrops(drops, context.world(), position, state, 0);
        return new TargetContents(ResourceRequirements.fromStacks(drops), null);
    }

    private static Ae2BusCaptureData captureBus(BuildingContext context, BlockPos position, boolean smartCopySource,
                                                CapturePurpose purpose) {
        TileEntity tile = context.world().getTileEntity(position);
        if (!(tile instanceof TileCableBus bus)) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                    "The target is not an AE2 cable bus");
        }

        List<Ae2BusCaptureData.Part> parts = new ArrayList<>();
        capturePart(context, position, bus.getPart(null), null, parts, smartCopySource, purpose);
        for (EnumFacing side : EnumFacing.VALUES) {
            capturePart(context, position, bus.getPart(side), side, parts, smartCopySource, purpose);
        }
        if (parts.isEmpty()) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                    "The AE2 cable bus has no portable center cable");
        }

        List<Ae2BusCaptureData.Facade> facades = new ArrayList<>();
        for (EnumFacing side : EnumFacing.VALUES) {
            IFacadePart facade = bus.getFacadeContainer().getFacade(side);
            if (facade != null) facades.add(new Ae2BusCaptureData.Facade(side, facade.getItemStack()));
        }
        try {
            return new Ae2BusCaptureData(parts, facades);
        } catch (IllegalArgumentException exception) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position, exception.getMessage());
        }
    }

    private static void capturePart(BuildingContext context, BlockPos position, IPart part, EnumFacing side,
                                    List<Ae2BusCaptureData.Part> parts, boolean smartCopySource,
                                    CapturePurpose purpose) {
        if (part == null) return;
        if (context.replaceInterfacesWithP2P() && part instanceof InterfacePart) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                    "AE2 Interface to P2P replacement is unavailable: Supergiant P2P tunnels have no Interface pattern inventory");
        }
        if (part instanceof PatternProviderPart provider) {
            parts.add(capturePatternProviderPart(context, position, provider, side, smartCopySource, purpose));
            return;
        }
        ItemStack dismantleStack = captureDismantleStack(position, part);
        Ae2BusCaptureData.PortableSettings portableSettings = capturePortableSettings(position, part, false);
        List<ItemStack> additionalDrops = new ArrayList<>();
        part.addAdditionalDrops(additionalDrops, false);
        if (!sameStacks(additionalDrops, stacksFrom(portableSettings.upgrades()))) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                    "The AE2 part has inventory-backed drops beyond its explicit upgrade inventory");
        }
        Ae2BusCaptureData.P2PState p2pState = null;
        if (part instanceof P2PTunnelPart<?> && part instanceof PortableP2PStateAccess p2p) {
            p2pState = new Ae2BusCaptureData.P2PState(p2p.applygray$getFrequency(), p2p.applygray$isOutput());
            if (purpose == CapturePurpose.COPY && p2pState.frequency() != 0 && !p2pState.output()) {
                throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                        "A configured AE2 P2P input cannot be duplicated; move it or copy an output tunnel");
            }
        }
        parts.add(new Ae2BusCaptureData.Part(side, dismantleStack, portableSettings, null, p2pState));
    }

    private static Ae2BusCaptureData.Part capturePatternProviderPart(BuildingContext context, BlockPos position,
                                                                      PatternProviderPart provider, EnumFacing side,
                                                                      boolean smartCopySource,
                                                                      CapturePurpose purpose) {
        if (side == null) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                    "An AE2 Pattern Provider must occupy a cable-bus side");
        }
        ItemStack partStack = captureDismantleStack(position, provider);
        PatternProviderLogic logic = provider.getLogic();
        Ae2BusCaptureData.PortableSettings portableSettings = capturePortableSettings(position, provider, true);
        if (smartCopySource) {
            SmartCopyPatternProviderLink source = provider instanceof SmartCopyPatternProviderLinkable linked
                    ? linked.applygray$getSmartCopyLink().orElseGet(
                    () -> SmartCopyPatternProviderLink.forSource(context.world(), position, side))
                    : SmartCopyPatternProviderLink.forSource(context.world(), position, side);
            portableSettings = new Ae2BusCaptureData.PortableSettings(portableSettings.settings(), List.of(),
                    List.of(), List.of());
            return new Ae2BusCaptureData.Part(side, partStack, portableSettings,
                    Ae2BusCaptureData.PatternProviderContents.smartCopy(source), null);
        }

        List<Ae2BusCaptureData.InventoryStack> patterns = captureInventory(logic.getPatternInv());
        List<Ae2BusCaptureData.InventoryStack> upgrades = captureInventory(logic.getUpgrades());
        List<ItemStack> additionalDrops = new ArrayList<>();
        provider.addAdditionalDrops(additionalDrops, false);
        List<ItemStack> supportedDrops = new ArrayList<>(stacksFrom(patterns));
        supportedDrops.addAll(stacksFrom(upgrades));
        if (!sameStacks(additionalDrops, supportedDrops)) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                    "The AE2 Pattern Provider has queued inputs or return items that cannot be copied safely yet");
        }
        return new Ae2BusCaptureData.Part(side, partStack, portableSettings,
                new Ae2BusCaptureData.PatternProviderContents(patterns, upgrades), null);
    }

    private static Ae2BusCaptureData.PortableSettings capturePortableSettings(BlockPos position, IPart part,
                                                                               boolean patternProvider) {
        if (!(part instanceof PortableAe2PartSettings access)) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                    "The AE2 part does not expose portable Memory Card settings");
        }
        var settings = access.applygray$exportPortableSettings();
        if (patternProvider) settings.removeTag("patterns");
        List<Ae2BusCaptureData.InventoryStack> upgrades = part instanceof IUpgradeableObject upgradeable
                ? captureInventory(upgradeable.getUpgrades()) : List.of();
        if (patternProvider) upgrades = List.of();

        List<ResourceRequirement> configuredItems = new ArrayList<>();
        List<FluidRequirement> configuredFluids = new ArrayList<>();
        if (part instanceof IConfigInvHost configHost) {
            captureConfiguredResources(position, configHost.getConfig(), configuredItems, configuredFluids);
        }
        return new Ae2BusCaptureData.PortableSettings(settings, upgrades, configuredItems, configuredFluids);
    }

    private static void captureConfiguredResources(BlockPos position, GenericStackInv config,
                                                   List<ResourceRequirement> items,
                                                   List<FluidRequirement> fluids) {
        for (int slot = 0; slot < config.size(); slot++) {
            GenericStack stack = config.getStack(slot);
            if (stack == null || stack.amount() <= 0) continue;
            if (stack.what() instanceof AEItemKey itemKey) {
                items.add(new ResourceRequirement(BlockSpec.of(itemKey.toStack()), stack.amount()));
            } else if (stack.what() instanceof AEFluidKey fluidKey) {
                FluidStack fluid = fluidKey.toStack(1);
                fluids.add(new FluidRequirement(fluid, stack.amount()));
            } else {
                throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                        "The AE2 configuration contains a resource type the Matter Manipulator cannot transact");
            }
        }
    }

    private static ItemStack captureDismantleStack(BlockPos position, IPart part) {
        List<ItemStack> dismantleDrops = new ArrayList<>();
        part.addPartDrop(dismantleDrops, false);
        if (dismantleDrops.size() != 1 || dismantleDrops.getFirst().isEmpty()) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                    "The AE2 part does not have one portable dismantle item");
        }
        return dismantleDrops.getFirst();
    }

    private static List<Ae2BusCaptureData.InventoryStack> captureInventory(InternalInventory inventory) {
        List<Ae2BusCaptureData.InventoryStack> captured = new ArrayList<>();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                captured.add(new Ae2BusCaptureData.InventoryStack(slot, stack));
            }
        }
        return captured;
    }

    private static List<ItemStack> stacksFrom(List<Ae2BusCaptureData.InventoryStack> contents) {
        List<ItemStack> stacks = new ArrayList<>(contents.size());
        for (Ae2BusCaptureData.InventoryStack content : contents) {
            stacks.add(content.stack());
        }
        return stacks;
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
        if (isAir(context, position, state)) return;
        requireRemoval(context, position, state);
    }

    private static boolean isAir(BuildingContext context, BlockPos position, IBlockState state) {
        return state.getBlock().isAir(state, context.world(), position);
    }

    private static boolean isCenterCable(BlockSpec specification) {
        ItemStack stack = specification.toStack();
        if (stack.isEmpty() || !(stack.getItem() instanceof IPartItem<?> partItem)) return false;
        return ICablePart.class.isAssignableFrom(partItem.getPartClass());
    }

    private static long energyCost(BuildingContext context, BlockPos position, int componentCount) {
        double distance = Math.max(1.0D, context.player().getDistance(position.getX(), position.getY(), position.getZ()));
        double cost = BASE_EU_PER_COMPONENT * Math.max(1, componentCount) * Math.sqrt(distance);
        if (context.powerEfficiency()) cost *= 0.5D;
        return cost >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) Math.ceil(cost);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static IPart placePart(BuildingContext context, BlockPos position, Ae2BusCaptureData.Part part) {
        ItemStack stack = part.stack();
        if (!(stack.getItem() instanceof IPartItem<?> partItem)) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                    "The captured AE2 item is no longer a part item");
        }
        IPart placed = PartHelper.setPart(context.world(), position, part.side(), context.player(), (IPartItem) partItem);
        if (placed == null) {
            throw new BuildingException(BuildingException.Reason.CANNOT_PLACE, position,
                    "AE2 rejected the captured part at this cable bus position");
        }
        if (stack.hasTagCompound()) {
            placed.importSettings(SettingsFrom.DISMANTLE_ITEM, stack.getTagCompound().copy(), context.player());
        }
        restorePortableSettings(position, placed, part.portableSettings(), part.patternProviderContents() != null);
        restorePatternProviderContents(position, placed, part.patternProviderContents());
        restoreP2PState(position, placed, part.p2pState());
        return placed;
    }

    private static void restorePortableSettings(BlockPos position, IPart placed,
                                                Ae2BusCaptureData.PortableSettings portable,
                                                boolean patternProvider) {
        if (portable == null) return;
        if (!(placed instanceof PortableAe2PartSettings access)) {
            throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                    "AE2 did not create a part with portable settings support");
        }
        access.applygray$importPortableSettings(portable.settings());
        if (!portable.upgrades().isEmpty()) {
            if (!(placed instanceof IUpgradeableObject upgradeable)) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                        "The placed AE2 part cannot restore its upgrades");
            }
            restoreInventory(position, upgradeable.getUpgrades(), portable.upgrades());
        }
        var restored = access.applygray$exportPortableSettings();
        if (patternProvider) restored.removeTag("patterns");
        if (!portable.settings().equals(restored)) {
            throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                    "The placed AE2 part did not retain its portable settings");
        }
    }

    private static void restoreP2PState(BlockPos position, IPart placed, Ae2BusCaptureData.P2PState state) {
        if (state == null) return;
        if (!(placed instanceof PortableP2PStateAccess p2p)) {
            throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                    "AE2 did not create a P2P tunnel with portable state support");
        }
        p2p.applygray$setP2PState(state.frequency(), state.output());
        if (p2p.applygray$getFrequency() != state.frequency() || p2p.applygray$isOutput() != state.output()) {
            throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                    "The placed AE2 P2P tunnel did not retain its frequency and role");
        }
    }

    private static void restorePatternProviderContents(BlockPos position, IPart placed,
                                                       Ae2BusCaptureData.PatternProviderContents contents) {
        if (contents == null) return;
        if (!(placed instanceof PatternProviderPart provider)) {
            throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                    "AE2 did not create the captured Pattern Provider part");
        }

        SmartCopyPatternProviderLink smartCopyLink = contents.smartCopyLink();
        if (smartCopyLink != null) {
            if (!(provider instanceof SmartCopyPatternProviderLinkable linked) ||
                    !linked.applygray$setSmartCopyLink(smartCopyLink)) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                        "AE2 rejected the Smart Copy Pattern Provider source");
            }
            return;
        }

        restoreInventory(position, provider.getLogic().getPatternInv(), contents.patterns());
        restoreInventory(position, provider.getLogic().getUpgrades(), contents.upgrades());
        provider.getLogic().updatePatterns();
    }

    private static void restoreInventory(BlockPos position, InternalInventory inventory,
                                         List<Ae2BusCaptureData.InventoryStack> contents) {
        for (Ae2BusCaptureData.InventoryStack content : contents) {
            if (content.slot() >= inventory.size() || !inventory.getStackInSlot(content.slot()).isEmpty()) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                        "AE2 Pattern Provider inventory changed during restoration");
            }
            inventory.setItemDirect(content.slot(), content.stack());
        }
    }

    private static void placeFacade(BuildingContext context, BlockPos position, Ae2BusCaptureData.Facade facade) {
        IPartHost host = PartHelper.getPartHost(context.world(), position);
        if (host == null) {
            throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                    "AE2 did not create a cable bus host for the captured facade");
        }
        ItemStack stack = facade.stack();
        if (!(stack.getItem() instanceof IFacadeItem facadeItem)) {
            throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                    "The captured AE2 facade item is no longer available");
        }
        IFacadePart placed = facadeItem.createPartFromItemStack(stack, facade.side());
        if (placed == null || !host.getFacadeContainer().addFacade(placed)) {
            throw new BuildingException(BuildingException.Reason.CANNOT_PLACE, position,
                    "AE2 rejected the captured facade at this cable bus position");
        }
    }

    private static void installBus(BuildingContext context, BlockPos position, Ae2BusCaptureData data) {
        for (Ae2BusCaptureData.Part part : data.parts()) {
            placePart(context, position, part);
        }
        for (Ae2BusCaptureData.Facade facade : data.facades()) {
            placeFacade(context, position, facade);
        }
        IPartHost host = PartHelper.getPartHost(context.world(), position);
        if (host == null || host.getPart(null) == null) {
            throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                    "AE2 did not retain the captured center cable");
        }
        host.markForSave();
        host.markForUpdate();
        host.notifyNeighbors();
    }

    private static void clearForReplacement(BuildingContext context, BlockPos position, IBlockState originalState) {
        if (isAir(context, position, originalState)) return;
        TileEntity tile = context.world().getTileEntity(position);
        if (tile instanceof TileCableBus bus) {
            bus.disableDrops();
        } else {
            originalState.getBlock().onBlockHarvested(context.world(), position, originalState, context.player());
        }
        if (!context.world().setBlockState(position, Blocks.AIR.getDefaultState(), WORLD_UPDATE_FLAGS)) {
            throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                    "Minecraft rejected clearing the AE2 cable bus destination");
        }
    }

    private static final class TargetContents {

        private final ResourceRequirements outputs;
        private final Ae2BusCaptureData busData;

        private TargetContents(ResourceRequirements outputs, Ae2BusCaptureData busData) {
            this.outputs = outputs;
            this.busData = busData;
        }

        private static TargetContents empty() {
            return new TargetContents(ResourceRequirements.empty(), null);
        }
    }

    private abstract static class Ae2Change implements PreparedBlockChange {

        final BuildingContext context;
        final BlockPos position;
        final IBlockState originalState;
        BlockSnapshot snapshot;

        Ae2Change(BuildingContext context, BlockPos position, IBlockState originalState) {
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
                        "The target changed after the AE2 build was prepared");
            }
        }
    }

    private static final class Ae2PlacementChange extends Ae2Change {

        private final TargetContents target;
        private final Ae2BusCaptureData data;

        private Ae2PlacementChange(BuildingContext context, BlockPos position, IBlockState originalState,
                                   TargetContents target, Ae2BusCaptureData data) {
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
            return Ae2BuildingAdapter.energyCost(context, position, data.parts().size() + data.facades().size());
        }

        @Override
        public void apply() {
            verifyOriginalState();
            if (target.busData != null &&
                    !target.busData.equals(captureBus(context, position, false, CapturePurpose.VERIFY))) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                        "The destination AE2 cable bus changed after the build was prepared");
            }
            snapshot = BlockSnapshot.getBlockSnapshot(context.world(), position);
            clearForReplacement(context, position, originalState);
            installBus(context, position, data);
            if (BuildingEventHooks.isPlayerPlaceCanceled(context, snapshot)) {
                throw new BuildingException(BuildingException.Reason.PERMISSION_DENIED, position,
                        "A protection handler denied the AE2 cable bus placement");
            }
        }
    }

    private static final class Ae2RemovalChange extends Ae2Change {

        private final Ae2BusCaptureData data;

        private Ae2RemovalChange(BuildingContext context, BlockPos position, IBlockState originalState,
                                 Ae2BusCaptureData data) {
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
            return Ae2BuildingAdapter.energyCost(context, position, data.parts().size() + data.facades().size());
        }

        @Override
        public void apply() {
            verifyOriginalState();
            if (!data.equals(captureBus(context, position, false, CapturePurpose.REMOVE))) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, position,
                        "The AE2 cable bus changed after the removal was prepared");
            }
            if (MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(context.world(), position, originalState,
                    context.player()))) {
                throw new BuildingException(BuildingException.Reason.PERMISSION_DENIED, position,
                        "A protection handler denied the AE2 cable bus removal");
            }
            snapshot = BlockSnapshot.getBlockSnapshot(context.world(), position);
            clearForReplacement(context, position, originalState);
        }
    }

    private static final class Ae2MoveChange implements PreparedBlockChange {

        private final BuildingContext context;
        private final BlockPos source;
        private final BlockPos target;
        private final IBlockState sourceState;
        private final IBlockState targetState;
        private final Ae2BusCaptureData data;
        private final boolean targetPrecleared;
        private BlockSnapshot sourceSnapshot;
        private BlockSnapshot targetSnapshot;

        private Ae2MoveChange(BuildingContext context, BlockPos source, BlockPos target, IBlockState sourceState,
                              IBlockState targetState, Ae2BusCaptureData data, boolean targetPrecleared) {
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
        public long energyCost() {
            return Ae2BuildingAdapter.energyCost(context, source, data.parts().size() + data.facades().size()) +
                    Ae2BuildingAdapter.energyCost(context, target, data.parts().size() + data.facades().size());
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
            if (!data.equals(captureBus(context, source, false, CapturePurpose.MOVE))) {
                throw new BuildingException(BuildingException.Reason.BLOCK_CHANGE_FAILED, source,
                        "The source AE2 cable bus changed after the move was prepared");
            }
            if (MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(context.world(), source, sourceState,
                    context.player()))) {
                throw new BuildingException(BuildingException.Reason.PERMISSION_DENIED, source,
                        "A protection handler denied the source AE2 cable bus move");
            }

            sourceSnapshot = BlockSnapshot.getBlockSnapshot(context.world(), source);
            targetSnapshot = BlockSnapshot.getBlockSnapshot(context.world(), target);
            clearForReplacement(context, source, sourceState);
            installBus(context, target, data);
            if (BuildingEventHooks.isPlayerPlaceCanceled(context, targetSnapshot)) {
                throw new BuildingException(BuildingException.Reason.PERMISSION_DENIED, target,
                        "A protection handler denied the target AE2 cable bus move");
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
