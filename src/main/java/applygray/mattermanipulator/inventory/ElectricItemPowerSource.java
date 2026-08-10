package applygray.mattermanipulator.inventory;

import java.util.Objects;

import applygray.mattermanipulator.state.ManipulatorTier;

import gregtech.api.capability.GregtechCapabilities;
import gregtech.api.capability.IElectricItem;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

/** Power source backed by the active tool's GregTech electric-item capability. */
public final class ElectricItemPowerSource implements PowerSource {

    private final String id;
    private final IElectricItem electricItem;
    private final int voltageTier;
    private final boolean creative;

    public ElectricItemPowerSource(String id, ItemStack stack, ManipulatorTier tier, EntityPlayer player) {
        this.id = Objects.requireNonNull(id, "id");
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(player, "player");
        this.electricItem = stack.getCapability(GregtechCapabilities.CAPABILITY_ELECTRIC_ITEM, null);
        if (electricItem == null) throw new IllegalArgumentException("The Matter Manipulator has no electric capability");
        this.voltageTier = tier.voltageTier();
        this.creative = player.capabilities.isCreativeMode;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public long extract(long amount, boolean simulate) {
        if (amount <= 0L || creative) return Math.max(0L, amount);
        return electricItem.discharge(amount, voltageTier, true, false, simulate);
    }

    @Override
    public long insert(long amount, boolean simulate) {
        if (amount <= 0L || creative) return Math.max(0L, amount);
        return electricItem.charge(amount, voltageTier, true, simulate);
    }
}
