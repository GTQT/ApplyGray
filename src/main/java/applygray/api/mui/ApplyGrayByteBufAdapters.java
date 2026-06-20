package applygray.api.mui;

import gregtech.api.mui.GTByteBufAdapters;

import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.fluids.util.AEFluidStack;
import appeng.util.item.AEItemStack;
import com.cleanroommc.modularui.utils.serialization.IByteBufAdapter;

public final class ApplyGrayByteBufAdapters {

    public static final IByteBufAdapter<IAEItemStack> AE_ITEM_STACK = GTByteBufAdapters.makeAdapter(
            AEItemStack::fromPacket, (buf, stack) -> stack.writeToPacket(buf));

    public static final IByteBufAdapter<IAEFluidStack> AE_FLUID_STACK = GTByteBufAdapters.makeAdapter(
            AEFluidStack::fromPacket, (buf, stack) -> stack.writeToPacket(buf));

    private ApplyGrayByteBufAdapters() {}
}
