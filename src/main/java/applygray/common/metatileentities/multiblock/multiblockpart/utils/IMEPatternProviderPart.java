package applygray.common.metatileentities.multiblock.multiblockpart.utils;

import gregtech.api.metatileentity.multiblock.MultiblockControllerBase;

import net.minecraftforge.items.IItemHandler;

import org.jetbrains.annotations.Nullable;

public interface IMEPatternProviderPart {

    int getTier();

    @Nullable
    IItemHandler getPatternSlot();

    String getShowName();

    @Nullable
    MultiblockControllerBase getController();
}
