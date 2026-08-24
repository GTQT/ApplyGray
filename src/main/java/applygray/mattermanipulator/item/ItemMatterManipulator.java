package applygray.mattermanipulator.item;

import java.util.List;
import java.util.StringJoiner;

import applygray.ApplyGrayMod;
import applygray.api.ApplyGrayAPI;
import applygray.mattermanipulator.network.MatterManipulatorNetwork;
import applygray.mattermanipulator.network.ExecuteManipulatorOperationMessage;
import applygray.mattermanipulator.state.ManipulatorCapability;
import applygray.mattermanipulator.state.ManipulatorLocation;
import applygray.mattermanipulator.state.ManipulatorState;
import applygray.mattermanipulator.planning.CopyArraySpan;
import applygray.mattermanipulator.state.ManipulatorPendingAction;
import applygray.mattermanipulator.state.ManipulatorPlaceMode;
import applygray.mattermanipulator.state.ManipulatorMaterialPicker;
import applygray.mattermanipulator.building.BlockSpec;
import applygray.mattermanipulator.state.ManipulatorTier;
import applygray.mattermanipulator.state.ManipulatorUpgrade;
import applygray.mattermanipulator.uplink.MatterManipulatorUplinkRegistry;
import applygray.mattermanipulator.uplink.UplinkEndpoint;

import gregtech.api.capability.GregtechCapabilities;
import gregtech.api.capability.IElectricItem;
import gregtech.api.capability.impl.ElectricItem;
import gregtech.api.GTValues;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.EnumAction;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.Constants;

/**
 * The four target-native Matter Manipulator items.
 *
 * <p>The item owns selection state, GregTech electric capability, interaction charging, and remote Uplink charging.
 * World mutation is delegated to the server-only bounded transaction executor.</p>
 */
public final class ItemMatterManipulator extends Item {

    private static final String STATE_KEY = "MatterManipulator";

    private final ManipulatorTier tier;

    ItemMatterManipulator(ManipulatorTier tier) {
        this.tier = tier;
        setMaxStackSize(1);
        setCreativeTab(CreativeTabs.TOOLS);
        setRegistryName(ApplyGrayAPI.id("matter_manipulator_" + tier.name().toLowerCase()));
        setTranslationKey(ApplyGrayAPI.MODID + ".matter_manipulator_" + tier.name().toLowerCase());
    }

    public ManipulatorTier tier() {
        return tier;
    }

    public ManipulatorState state(ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() != this || !stack.hasTagCompound()) {
            return new ManipulatorState();
        }
        NBTTagCompound tag = stack.getTagCompound();
        if (!tag.hasKey(STATE_KEY, Constants.NBT.TAG_COMPOUND)) return new ManipulatorState();
        return ManipulatorState.readFromNbt(tag.getCompoundTag(STATE_KEY));
    }

    public void saveState(ItemStack stack, ManipulatorState state) {
        if (stack.isEmpty() || stack.getItem() != this) {
            throw new IllegalArgumentException("The state does not belong to this Matter Manipulator item");
        }
        NBTTagCompound tag = stack.hasTagCompound() ? stack.getTagCompound() : new NBTTagCompound();
        tag.setTag(STATE_KEY, state.writeToNbt());
        stack.setTagCompound(tag);
    }

    public boolean hasCapability(ItemStack stack, ManipulatorCapability capability) {
        if (tier.hasCapability(capability)) return true;
        for (ManipulatorUpgrade upgrade : state(stack).installedUpgrades()) {
            if (upgrade.providedCapabilities().contains(capability)) return true;
        }
        return false;
    }

    /** Binds a capable target-native manipulator to a formed Quantum Uplink address. */
    public boolean setUplinkAddress(ItemStack stack, long address) {
        if (address == 0L || !hasCapability(stack, ManipulatorCapability.UPLINK)) return false;
        ManipulatorState state = state(stack);
        state.setUplinkAddress(address);
        saveState(stack, state);
        return true;
    }

    @Override
    public ICapabilityProvider initCapabilities(ItemStack stack, NBTTagCompound nbt) {
        return new ElectricItem(stack, tier.maximumCharge(), tier.voltageTier(), true, false);
    }

    /** Matches GT5U's creative entries: expose both an empty tool and a fully charged tool. */
    @Override
    public void getSubItems(CreativeTabs creativeTab, net.minecraft.util.NonNullList<ItemStack> subItems) {
        if (!isInCreativeTab(creativeTab)) return;

        ItemStack empty = new ItemStack(this);
        subItems.add(empty);

        ItemStack charged = new ItemStack(this);
        IElectricItem electricItem = charged.getCapability(GregtechCapabilities.CAPABILITY_ELECTRIC_ITEM, null);
        if (electricItem != null) {
            electricItem.charge(tier.maximumCharge(), tier.voltageTier(), true, false);
        }
        subItems.add(charged);
    }

    @Override
    public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos position, EnumHand hand,
                                      EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (player.isSneaking()) {
            player.setActiveHand(hand);
            if (world.isRemote) {
                MatterManipulatorNetwork.CHANNEL.sendToServer(new ExecuteManipulatorOperationMessage(hand,
                        ExecuteManipulatorOperationMessage.Action.START));
            }
            return EnumActionResult.SUCCESS;
        }
        if (world.isRemote) return EnumActionResult.SUCCESS;

        ItemStack stack = player.getHeldItem(hand);
        ManipulatorState state = state(stack);
        if (state.pendingAction() == ManipulatorPendingAction.GEOM_SELECTING_BLOCK ||
                state.pendingAction() == ManipulatorPendingAction.EXCH_SET_TARGET ||
                state.pendingAction() == ManipulatorPendingAction.EXCH_SET_REPLACE ||
                state.pendingAction() == ManipulatorPendingAction.EXCH_ADD_REPLACE ||
                state.pendingAction() == ManipulatorPendingAction.PICK_CABLE) {
            BlockSpec picked = BlockSpec.fromState(world.getBlockState(position));
            if (!picked.isAir()) {
                if (state.pendingAction() == ManipulatorPendingAction.EXCH_SET_TARGET) {
                    state.setPickTarget(applygray.mattermanipulator.state.ManipulatorPickTarget.EXCHANGE_REPLACEMENT);
                } else if (state.pendingAction() == ManipulatorPendingAction.EXCH_SET_REPLACE) {
                    state.setPickTarget(applygray.mattermanipulator.state.ManipulatorPickTarget.EXCHANGE_WHITELIST_SET);
                } else if (state.pendingAction() == ManipulatorPendingAction.EXCH_ADD_REPLACE) {
                    state.setPickTarget(applygray.mattermanipulator.state.ManipulatorPickTarget.EXCHANGE_WHITELIST_ADD);
                } else if (state.pendingAction() == ManipulatorPendingAction.PICK_CABLE) {
                    state.setPickTarget(applygray.mattermanipulator.state.ManipulatorPickTarget.CABLE);
                }
                ManipulatorMaterialPicker.apply(state, picked, player.isSneaking() &&
                        state.pendingAction() == ManipulatorPendingAction.GEOM_SELECTING_BLOCK);
                state.setPendingAction(ManipulatorPendingAction.NONE);
                saveState(stack, state);
                if (player instanceof EntityPlayerMP serverPlayer) MatterManipulatorNetwork.sendStateTo(serverPlayer, hand, state);
                return EnumActionResult.SUCCESS;
            }
        }
        BlockPos selectedPosition = player.isSneaking() ? position : position.offset(facing);
        ManipulatorLocation location = ManipulatorLocation.fromWorld(world, selectedPosition);
        if (state.pendingAction() == ManipulatorPendingAction.MARK_ARRAY) {
            if (state.selectionA() == null || state.selectionB() == null || state.selectionC() == null) {
                state.setPendingAction(ManipulatorPendingAction.NONE);
                saveState(stack, state);
                player.sendStatusMessage(new TextComponentTranslation(
                        "applygray.matter_manipulator.selection.incomplete"), true);
                return EnumActionResult.FAIL;
            }
            BlockPos span = CopyArraySpan.calculate(state.selectionA(), state.selectionB(), state.selectionC(),
                    selectedPosition, state.copyTransform());
            state.setCopyRepeats(span.getX(), span.getY(), span.getZ());
            state.setPendingAction(ManipulatorPendingAction.NONE);
            saveState(stack, state);
            if (player instanceof EntityPlayerMP serverPlayer) {
                MatterManipulatorNetwork.sendStateTo(serverPlayer, hand, state);
            }
            player.sendStatusMessage(new TextComponentTranslation("applygray.matter_manipulator.array.marked",
                    span.getX(), span.getY(), span.getZ()), true);
            return EnumActionResult.SUCCESS;
        }
        SelectionSlot slot = applyPendingSelection(state, location);
        if (slot == null && state.pendingAction() == ManipulatorPendingAction.NONE) {
            slot = markNextSelection(state, location);
            if (slot == SelectionSlot.A && (state.placeMode() == ManipulatorPlaceMode.GEOMETRY ||
                    state.placeMode() == ManipulatorPlaceMode.EXCHANGING || state.placeMode() == ManipulatorPlaceMode.CABLES)) {
                state.setPendingAction(ManipulatorPendingAction.MOVING_COORDS);
            }
        }
        if (slot == null) {
            player.sendStatusMessage(new TextComponentTranslation("applygray.matter_manipulator.selection.complete"),
                    true);
            return EnumActionResult.FAIL;
        }

        saveState(stack, state);
        if (player instanceof EntityPlayerMP serverPlayer) {
            MatterManipulatorNetwork.sendStateTo(serverPlayer, hand, state);
        }
        player.sendStatusMessage(new TextComponentTranslation(slot.translationKey, selectedPosition.getX(),
                selectedPosition.getY(), selectedPosition.getZ(), world.provider.getDimension()), true);
        return EnumActionResult.SUCCESS;
    }

    private static SelectionSlot applyPendingSelection(ManipulatorState state, ManipulatorLocation location) {
        switch (state.pendingAction()) {
            case MARK_COPY_A -> { state.setSelectionA(location); state.setPendingAction(ManipulatorPendingAction.MARK_COPY_B); return SelectionSlot.A; }
            case MARK_COPY_B -> { state.setSelectionB(location); state.setPendingAction(ManipulatorPendingAction.NONE); return SelectionSlot.B; }
            case MARK_CUT_A -> { state.setSelectionA(location); state.setPendingAction(ManipulatorPendingAction.MARK_CUT_B); return SelectionSlot.A; }
            case MARK_CUT_B -> { state.setSelectionB(location); state.setPendingAction(ManipulatorPendingAction.NONE); return SelectionSlot.B; }
            case MARK_PASTE -> { state.setSelectionC(location); state.setPendingAction(ManipulatorPendingAction.NONE); return SelectionSlot.C; }
            case MOVING_COORDS -> {
                if (state.selectionA() == null) state.setSelectionA(location);
                else if (state.selectionB() == null) state.setSelectionB(location);
                else if (state.shape().requiresThirdPoint() && state.selectionC() == null) state.setSelectionC(location);
                if (state.selectionB() != null && (!state.shape().requiresThirdPoint() || state.selectionC() != null)) {
                    state.setPendingAction(ManipulatorPendingAction.NONE);
                }
                return state.selectionC() != null ? SelectionSlot.C : SelectionSlot.B;
            }
            default -> { return null; }
        }
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (player.isSneaking()) {
            player.setActiveHand(hand);
            if (world.isRemote) {
                MatterManipulatorNetwork.CHANNEL.sendToServer(new ExecuteManipulatorOperationMessage(hand,
                        ExecuteManipulatorOperationMessage.Action.START));
            }
            return ActionResult.newResult(EnumActionResult.SUCCESS, stack);
        }

        if (world.isRemote) ApplyGrayMod.proxy.openMatterManipulatorConfiguration(hand);
        return ActionResult.newResult(EnumActionResult.SUCCESS, stack);
    }

    @Override
    public int getMaxItemUseDuration(ItemStack stack) {
        return 72_000;
    }

    @Override
    public EnumAction getItemUseAction(ItemStack stack) {
        return EnumAction.BOW;
    }

    @Override
    public void onPlayerStoppedUsing(ItemStack stack, World world, EntityLivingBase entity, int timeLeft) {
        if (!world.isRemote || !(entity instanceof EntityPlayer player)) return;
        int useTicks = getMaxItemUseDuration(stack) - timeLeft;
        if (useTicks < 1) return;
        EnumHand hand = player.getHeldItemMainhand() == stack ? EnumHand.MAIN_HAND : EnumHand.OFF_HAND;
        MatterManipulatorNetwork.CHANNEL.sendToServer(new ExecuteManipulatorOperationMessage(hand,
                ExecuteManipulatorOperationMessage.Action.STOP));
    }

    @Override
    public void onUpdate(ItemStack stack, World world, Entity holder, int itemSlot, boolean selected) {
        if (world.isRemote || world.getTotalWorldTime() % 100L != 0L) return;

        ManipulatorState state = state(stack);
        if (!state.hasUpgrade(ManipulatorUpgrade.POWER_P2P) || state.uplinkAddress() == null) return;

        UplinkEndpoint uplink = MatterManipulatorUplinkRegistry.find(state.uplinkAddress());
        if (uplink == null || !uplink.isActive()) return;

        IElectricItem electricItem = stack.getCapability(GregtechCapabilities.CAPABILITY_ELECTRIC_ITEM, null);
        if (electricItem == null) return;

        long required = electricItem.charge(Long.MAX_VALUE, tier.voltageTier(), true, true);
        long available = uplink.drainPower(required, true);
        if (available <= 0L) return;

        long drained = uplink.drainPower(available, false);
        if (drained <= 0L) return;

        long charged = electricItem.charge(drained, tier.voltageTier(), true, false);
        if (charged < drained) uplink.restorePower(drained - charged);
    }

    @Override
    public void addInformation(ItemStack stack, World world, List<String> tooltip,
                               net.minecraft.client.util.ITooltipFlag flag) {
        ManipulatorState state = state(stack);
        if (!GuiScreen.isShiftKeyDown()) {
            tooltip.add(translate("applygray.matter_manipulator.tooltip.hold_shift"));
        } else {
            if (hasCapability(stack, ManipulatorCapability.AE_NETWORK)) {
                tooltip.add(translate("applygray.matter_manipulator.tooltip.me_connection"));
            }
            if (hasCapability(stack, ManipulatorCapability.UPLINK)) {
                if (state.uplinkAddress() == null) {
                    tooltip.add(translate("applygray.matter_manipulator.tooltip.uplink.none"));
                } else {
                    tooltip.add(translate("applygray.matter_manipulator.tooltip.uplink.address",
                            Long.toUnsignedString(state.uplinkAddress(), 16)));
                }
            }
            if (tier.capabilities().stream().filter(capability -> capability == ManipulatorCapability.GEOMETRY ||
                    capability == ManipulatorCapability.COPYING || capability == ManipulatorCapability.MOVING ||
                    capability == ManipulatorCapability.EXCHANGING || capability == ManipulatorCapability.CABLES)
                    .count() > 1 || state.installedUpgrades().stream().anyMatch(upgrade ->
                            upgrade.providedCapabilities().stream().anyMatch(capability ->
                                    capability == ManipulatorCapability.COPYING || capability == ManipulatorCapability.MOVING ||
                                    capability == ManipulatorCapability.EXCHANGING || capability == ManipulatorCapability.CABLES))) {
                tooltip.add(translate("applygray.matter_manipulator.tooltip.mode", localizedEnum(state.placeMode())));
            }
            if (hasCapability(stack, ManipulatorCapability.REMOVAL)) {
                tooltip.add(translate("applygray.matter_manipulator.tooltip.removing", localizedEnum(state.removalMode())));
            }
            if (state.pendingAction() != ManipulatorPendingAction.NONE) {
                tooltip.add(translate("applygray.matter_manipulator.tooltip.pending",
                        localizedEnum(state.pendingAction())));
            }
            switch (state.placeMode()) {
                case GEOMETRY -> {
                    tooltip.add(translate("applygray.matter_manipulator.tooltip.shape", localizedEnum(state.shape())));
                    tooltip.add(translate("applygray.matter_manipulator.tooltip.coord_a", locationText(state.selectionA())));
                    tooltip.add(translate("applygray.matter_manipulator.tooltip.coord_b", locationText(state.selectionB())));
                    tooltip.add(translate("applygray.matter_manipulator.tooltip.corner", materials(state.geometryConfiguration().corners())));
                    tooltip.add(translate("applygray.matter_manipulator.tooltip.edge", materials(state.geometryConfiguration().edges())));
                    tooltip.add(translate("applygray.matter_manipulator.tooltip.face", materials(state.geometryConfiguration().faces())));
                    tooltip.add(translate("applygray.matter_manipulator.tooltip.volume", materials(state.geometryConfiguration().volumes())));
                }
                case COPYING -> {
                    tooltip.add(translate("applygray.matter_manipulator.tooltip.copy_a", locationText(state.selectionA())));
                    tooltip.add(translate("applygray.matter_manipulator.tooltip.copy_b", locationText(state.selectionB())));
                    tooltip.add(translate("applygray.matter_manipulator.tooltip.paste", locationText(state.selectionC())));
                    tooltip.add(translate("applygray.matter_manipulator.tooltip.stack", state.copyRepeatX(), state.copyRepeatY(), state.copyRepeatZ()));
                    tooltip.add(translate("applygray.matter_manipulator.tooltip.transform", state.copyTransform().axisSummary()));
                    tooltip.add(translate("applygray.matter_manipulator.tooltip.smart_copy", state.smartCopy()
                            ? translate("applygray.matter_manipulator.tooltip.on")
                            : translate("applygray.matter_manipulator.tooltip.off")));
                }
                case MOVING -> {
                    tooltip.add(translate("applygray.matter_manipulator.tooltip.cut_a", locationText(state.selectionA())));
                    tooltip.add(translate("applygray.matter_manipulator.tooltip.cut_b", locationText(state.selectionB())));
                    tooltip.add(translate("applygray.matter_manipulator.tooltip.paste", locationText(state.selectionC())));
                }
                case EXCHANGING -> {
                    tooltip.add(translate("applygray.matter_manipulator.tooltip.removable", materials(state.exchangeWhitelist())));
                    tooltip.add(translate("applygray.matter_manipulator.tooltip.replacing", materials(state.exchangeReplacement())));
                }
                case CABLES -> {
                    tooltip.add(translate("applygray.matter_manipulator.tooltip.coord_a", locationText(state.selectionA())));
                    tooltip.add(translate("applygray.matter_manipulator.tooltip.coord_b", locationText(state.selectionB())));
                    tooltip.add(translate("applygray.matter_manipulator.tooltip.cable", display(state.cableMaterial())));
                }
            }
            if (!state.installedUpgrades().isEmpty()) {
                tooltip.add(translate("applygray.matter_manipulator.tooltip.installed_upgrades"));
                for (ManipulatorUpgrade upgrade : state.installedUpgrades()) {
                    tooltip.add(TextFormatting.GRAY + "- " + translate(upgrade.translationKey()));
                }
            }
        }
        IElectricItem electricItem = stack.getCapability(GregtechCapabilities.CAPABILITY_ELECTRIC_ITEM, null);
        long charge = electricItem == null ? 0L : electricItem.discharge(Long.MAX_VALUE, tier.voltageTier(), true, true, true);
        tooltip.add(translate("applygray.matter_manipulator.tooltip.voltage", charge, tier.maximumCharge(),
                GTValues.V[tier.voltageTier()], GTValues.VN[tier.voltageTier()]));
    }

    private static String translate(String key, Object... args) {
        return net.minecraft.client.resources.I18n.format(key, args);
    }

    private static String localizedEnum(Enum<?> value) {
        return translate("applygray.matter_manipulator.enum." + value.name().toLowerCase());
    }

    private static String locationText(ManipulatorLocation location) {
        return location == null ? translate("applygray.matter_manipulator.tooltip.none") :
                location.position().getX() + ", " + location.position().getY() + ", " + location.position().getZ() +
                        " (" + location.dimension() + ")";
    }

    private static String display(applygray.mattermanipulator.building.BlockSpec specification) {
        if (specification.isAir()) return translate("applygray.matter_manipulator.tooltip.none");
        return specification.isFluid() ? specification.fluidStack().getLocalizedName()
                : specification.toStack().getDisplayName();
    }

    private static String materials(applygray.mattermanipulator.building.WeightedBlockList list) {
        if (list.entries().isEmpty()) return translate("applygray.matter_manipulator.tooltip.none");
        StringJoiner joiner = new StringJoiner(", ");
        list.entries().forEach(entry -> joiner.add(display(entry.spec()) + (entry.weight() == 1 ? "" : " x" + entry.weight())));
        return joiner.toString();
    }

    private static SelectionSlot markNextSelection(ManipulatorState state, ManipulatorLocation location) {
        if (state.selectionA() == null) {
            state.setSelectionA(location);
            return SelectionSlot.A;
        }
        if (state.selectionB() == null) {
            state.setSelectionB(location);
            return SelectionSlot.B;
        }
        if (requiresThirdSelection(state) && state.selectionC() == null) {
            state.setSelectionC(location);
            return SelectionSlot.C;
        }
        return null;
    }

    private static String selectionSummary(ManipulatorState state) {
        int selected = 0;
        if (state.selectionA() != null) selected++;
        if (state.selectionB() != null) selected++;
        if (requiresThirdSelection(state) && state.selectionC() != null) selected++;
        int required = requiresThirdSelection(state) ? 3 : 2;
        return selected + "/" + required;
    }

    private static boolean requiresThirdSelection(ManipulatorState state) {
        return switch (state.placeMode()) {
            case COPYING, MOVING -> true;
            case GEOMETRY -> state.shape().requiresThirdPoint();
            case EXCHANGING, CABLES -> false;
        };
    }

    private enum SelectionSlot {
        A("applygray.matter_manipulator.selection.a"),
        B("applygray.matter_manipulator.selection.b"),
        C("applygray.matter_manipulator.selection.c");

        private final String translationKey;

        SelectionSlot(String translationKey) {
            this.translationKey = translationKey;
        }
    }
}
