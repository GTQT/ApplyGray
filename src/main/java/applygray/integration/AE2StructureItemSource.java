package applygray.integration;

import gregtech.api.pattern.StructureItemSource;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.util.PlayerWirelessGridHelper;
import appeng.me.helpers.BaseActionSource;
import org.jetbrains.annotations.NotNull;

public final class AE2StructureItemSource implements StructureItemSource {

    @Override
    public boolean extract(@NotNull EntityPlayer player, @NotNull ItemStack candidate, boolean simulate) {
        if (player.world.isRemote || candidate.isEmpty()) return false;
        try {
            IStorageGrid storageGrid = PlayerWirelessGridHelper.getStorageGrid(player);
            if (storageGrid == null) return false;
            IItemStorageChannel channel = AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class);
            IMEMonitor<IAEItemStack> monitor = storageGrid.getInventory(channel);
            if (monitor == null) return false;
            IAEItemStack request = channel.createStack(candidate);
            request.setStackSize(1);
            IAEItemStack extracted = monitor.extractItems(request,
                    simulate ? Actionable.SIMULATE : Actionable.MODULATE, new BaseActionSource());
            return extracted != null && extracted.getStackSize() > 0;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
