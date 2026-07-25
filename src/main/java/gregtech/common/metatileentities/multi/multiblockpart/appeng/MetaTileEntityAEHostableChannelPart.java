package gregtech.common.metatileentities.multi.multiblockpart.appeng;

import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import ae2.api.storage.MEStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Legacy channel-specific host base collapsed onto Supergiant's unified network storage.
 */
public abstract class MetaTileEntityAEHostableChannelPart extends MetaTileEntityAEHostablePart {

    protected MetaTileEntityAEHostableChannelPart(ResourceLocation metaTileEntityId, int tier, boolean isExportHatch) {
        super(metaTileEntityId, tier, isExportHatch);
    }

    @Override
    public void clearMachineInventory(@NotNull List<@NotNull ItemStack> itemBuffer) {
    }

    @Nullable
    protected MEStorage getMonitor() {
        return getNetworkStorage();
    }
}
