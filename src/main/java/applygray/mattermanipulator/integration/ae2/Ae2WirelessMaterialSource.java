package applygray.mattermanipulator.integration.ae2;

import java.util.Objects;

import applygray.mattermanipulator.building.BlockSpec;
import applygray.mattermanipulator.inventory.MaterialSource;

import ae2.api.config.Actionable;
import ae2.api.networking.security.IActionSource;
import ae2.api.stacks.AEItemKey;
import ae2.api.storage.StorageHelper;
import ae2.core.gui.locator.GuiHostLocators;
import ae2.helpers.WirelessTerminalGuiHost;
import ae2.me.helpers.ActionHostEnergySource;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Exact material source backed by the player's active AE2 wireless terminal.
 *
 * <p>Every transfer rechecks the terminal's link, active grid node, AE power, and player-backed action source. AE2
 * therefore remains responsible for wireless range and security enforcement instead of treating an item NBT link as
 * a direct network handle.</p>
 */
public final class Ae2WirelessMaterialSource implements MaterialSource {

    private static final String ID = "ae2-wireless";

    private final EntityPlayerMP player;

    public Ae2WirelessMaterialSource(EntityPlayerMP player) {
        this.player = Objects.requireNonNull(player, "player");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public long extract(BlockSpec specification, long amount, boolean simulate) {
        AEItemKey key = keyFor(specification, amount);
        WirelessTerminalGuiHost<?> host = findConnectedTerminal();
        if (key == null || host == null) return 0L;
        return StorageHelper.poweredExtraction(new ActionHostEnergySource(host), host.getInventory(), key, amount,
                IActionSource.ofPlayer(player, host), simulate ? Actionable.SIMULATE : Actionable.MODULATE);
    }

    @Override
    public long insert(BlockSpec specification, long amount, boolean simulate) {
        AEItemKey key = keyFor(specification, amount);
        WirelessTerminalGuiHost<?> host = findConnectedTerminal();
        if (key == null || host == null) return 0L;
        return StorageHelper.poweredInsert(new ActionHostEnergySource(host), host.getInventory(), key, amount,
                IActionSource.ofPlayer(player, host), simulate ? Actionable.SIMULATE : Actionable.MODULATE);
    }

    private AEItemKey keyFor(BlockSpec specification, long amount) {
        if (amount <= 0L || specification.isAir()) return null;
        return AEItemKey.of(specification.toStack());
    }

    private WirelessTerminalGuiHost<?> findConnectedTerminal() {
        for (int slot = 0; slot < player.inventory.getSizeInventory(); slot++) {
            WirelessTerminalGuiHost<?> host = GuiHostLocators.forInventorySlot(slot)
                    .locate(player, WirelessTerminalGuiHost.class);
            if (host != null && host.getLinkStatus().connected() && host.getActionableNode() != null &&
                    host.getActionableNode().isActive()) {
                return host;
            }
        }
        return null;
    }
}
