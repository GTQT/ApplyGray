package applygray.mattermanipulator.network;

import java.util.Objects;

import applygray.mattermanipulator.state.ManipulatorState;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumHand;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;

import io.netty.buffer.ByteBuf;

/** Server-confirmed compact state update for the currently configured tool. */
public final class ManipulatorStateSyncMessage implements IMessage {

    private EnumHand hand;
    private NBTTagCompound state;

    public ManipulatorStateSyncMessage() {}

    public ManipulatorStateSyncMessage(EnumHand hand, ManipulatorState state) {
        this.hand = Objects.requireNonNull(hand, "hand");
        this.state = Objects.requireNonNull(state, "state").writeToNbt();
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        hand = buffer.readBoolean() ? EnumHand.MAIN_HAND : EnumHand.OFF_HAND;
        state = ByteBufUtils.readTag(buffer);
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeBoolean(hand == EnumHand.MAIN_HAND);
        ByteBufUtils.writeTag(buffer, state);
    }

    public EnumHand hand() {
        return hand;
    }

    public ManipulatorState state() {
        return ManipulatorState.readFromNbt(state);
    }
}
