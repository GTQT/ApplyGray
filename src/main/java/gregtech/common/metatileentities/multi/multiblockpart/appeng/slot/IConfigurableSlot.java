package gregtech.common.metatileentities.multi.multiblockpart.appeng.slot;

import ae2.api.stacks.GenericStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface IConfigurableSlot {

    @Nullable
    GenericStack getConfig();

    @Nullable
    GenericStack getStock();

    void setConfig(@Nullable GenericStack val);

    void setStock(@Nullable GenericStack val);

    @NotNull
    IConfigurableSlot copy();
}
