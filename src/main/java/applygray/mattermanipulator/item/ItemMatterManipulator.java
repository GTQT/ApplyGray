package applygray.mattermanipulator.item;

import java.util.List;

import applygray.api.ApplyGrayAPI;
import applygray.mattermanipulator.network.MatterManipulatorNetwork;
import applygray.mattermanipulator.state.ManipulatorCapability;
import applygray.mattermanipulator.state.ManipulatorLocation;
import applygray.mattermanipulator.state.ManipulatorState;
import applygray.mattermanipulator.state.ManipulatorTier;
import applygray.mattermanipulator.state.ManipulatorUpgrade;
import applygray.mattermanipulator.uplink.MatterManipulatorUplinkRegistry;
import applygray.mattermanipulator.uplink.UplinkEndpoint;

import gregtech.api.capability.GregtechCapabilities;
import gregtech.api.capability.IElectricItem;
import gregtech.api.capability.impl.ElectricItem;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
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
 * <p>This initial runtime layer persists selection state and supplies GregTech energy. World mutation remains in the
 * later transaction layer, so a partly migrated tool cannot consume or duplicate resources.</p>
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

    @Override
    public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos position, EnumHand hand,
                                      EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (world.isRemote) return EnumActionResult.SUCCESS;

        ItemStack stack = player.getHeldItem(hand);
        ManipulatorState state = state(stack);
        BlockPos selectedPosition = player.isSneaking() ? position : position.offset(facing);
        SelectionSlot slot = markNextSelection(state, ManipulatorLocation.fromWorld(world, selectedPosition));
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

    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (!player.isSneaking()) return ActionResult.newResult(EnumActionResult.PASS, stack);

        if (!world.isRemote) {
            ManipulatorState state = state(stack);
            state.clearSelections();
            saveState(stack, state);
            if (player instanceof EntityPlayerMP serverPlayer) {
                MatterManipulatorNetwork.sendStateTo(serverPlayer, hand, state);
            }
            player.sendStatusMessage(new TextComponentTranslation("applygray.matter_manipulator.selection.cleared"),
                    true);
        }
        return ActionResult.newResult(EnumActionResult.SUCCESS, stack);
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
        tooltip.add(TextFormatting.GRAY + "Tier: " + tier.name());
        tooltip.add(TextFormatting.GRAY + "Mode: " + state.placeMode().name());
        tooltip.add(TextFormatting.DARK_GRAY + "Selection: " + selectionSummary(state));
        if (state.uplinkAddress() != null) {
            tooltip.add(TextFormatting.DARK_AQUA + "Uplink: " + Long.toUnsignedString(state.uplinkAddress(), 16));
        }
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
