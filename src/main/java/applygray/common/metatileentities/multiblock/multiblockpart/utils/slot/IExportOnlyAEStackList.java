package applygray.common.metatileentities.multiblock.multiblockpart.utils.slot;

import org.jetbrains.annotations.NotNull;

public interface IExportOnlyAEStackList {

    @NotNull
    ExportOnlyAESlot @NotNull [] getInventory();

    boolean isAutoPull();

    boolean isStocking();
}
