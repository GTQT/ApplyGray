package applygray.integration;

import gregtech.api.pattern.StructureItemSource;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import ae2.api.config.Actionable;
import ae2.api.networking.security.IActionSource;
import ae2.api.stacks.AEItemKey;
import ae2.api.storage.StorageHelper;
import ae2.core.gui.locator.GuiHostLocators;
import ae2.helpers.WirelessTerminalGuiHost;
import ae2.me.helpers.ActionHostEnergySource;
import org.jetbrains.annotations.NotNull;

public final class AE2StructureItemSource implements StructureItemSource {

    @Override
    public boolean extract(@NotNull EntityPlayer player, @NotNull ItemStack candidate, boolean simulate) {
        if (player.world.isRemote || candidate.isEmpty()) return false;
        try {
            AEItemKey key = AEItemKey.of(candidate);
            if (key == null) return false;

            WirelessTerminalGuiHost<?> host = findConnectedTerminal(player);
            if (host == null) return false;

            long extracted = StorageHelper.poweredExtraction(new ActionHostEnergySource(host), host.getInventory(), key,
                    1, IActionSource.ofPlayer(player, host),
                    simulate ? Actionable.SIMULATE : Actionable.MODULATE);
            return extracted > 0;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * 玩家身上是否有已连接、可用的无线终端。
     *
     * <p>供构建器在诊断与建造成败反馈里区分“AE 里没有”和“根本没连上 AE”。
     */
    public static boolean isLinked(@NotNull EntityPlayer player) {
        return findConnectedTerminal(player) != null;
    }

    private static WirelessTerminalGuiHost<?> findConnectedTerminal(EntityPlayer player) {
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
