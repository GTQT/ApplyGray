package gregtech.common.metatileentities.multi.multiblockpart.appeng.slot;

import org.jetbrains.annotations.NotNull;

public interface IExportOnlyAEStackList {

    @NotNull
    ExportOnlyAESlot @NotNull [] getInventory();

    boolean isAutoPull();

    boolean isStocking();
}
