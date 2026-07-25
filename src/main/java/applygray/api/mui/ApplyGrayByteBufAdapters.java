package applygray.api.mui;

import gregtech.api.mui.GTByteBufAdapters;

import ae2.api.stacks.GenericStack;
import com.cleanroommc.modularui.utils.serialization.IByteBufAdapter;

public final class ApplyGrayByteBufAdapters {

    public static final IByteBufAdapter<GenericStack> GENERIC_STACK = GTByteBufAdapters.makeAdapter(
            GenericStack::readBuffer, (buffer, stack) -> GenericStack.writeBuffer(stack, buffer));

    private ApplyGrayByteBufAdapters() {}
}
