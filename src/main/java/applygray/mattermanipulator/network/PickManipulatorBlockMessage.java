package applygray.mattermanipulator.network;

import applygray.ApplyGrayMod;
import applygray.mattermanipulator.building.BlockSpec;
import applygray.mattermanipulator.item.ItemMatterManipulator;
import applygray.mattermanipulator.state.ManipulatorMaterialPicker;
import applygray.mattermanipulator.state.ManipulatorState;
import applygray.mattermanipulator.state.ManipulatorPlaceMode;
import applygray.mattermanipulator.state.ManipulatorPickTarget;
import applygray.mattermanipulator.state.ManipulatorPendingAction;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import io.netty.buffer.ByteBuf;

/** Middle-click intent; the server performs its own bounded block ray trace. */
public final class PickManipulatorBlockMessage implements IMessage {

    private EnumHand hand;

    public PickManipulatorBlockMessage() {}

    public PickManipulatorBlockMessage(EnumHand hand) {
        this.hand = hand;
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        hand = buffer.readBoolean() ? EnumHand.MAIN_HAND : EnumHand.OFF_HAND;
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeBoolean(hand == EnumHand.MAIN_HAND);
    }

    public static final class Handler implements IMessageHandler<PickManipulatorBlockMessage, IMessage> {

        @Override
        public IMessage onMessage(PickManipulatorBlockMessage message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> pick(player, message.hand));
            return null;
        }

        private static void pick(EntityPlayerMP player, EnumHand hand) {
            ItemStack stack = player.getHeldItem(hand);
            if (!(stack.getItem() instanceof ItemMatterManipulator manipulator)) return;

            Vec3d start = player.getPositionEyes(1.0F);
            // Include liquid collision shapes, matching GT5's middle-click material pick path.
            RayTraceResult hit = player.world.rayTraceBlocks(start, start.add(player.getLook(1.0F).scale(6.0D)),
                    true, true, false);
            if (hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK) return;
            ManipulatorState state = manipulator.state(stack);
            BlockSpec specification = BlockSpec.fromState(player.world.getBlockState(hit.getBlockPos()));
            if (specification.isAir()) return;

            ManipulatorPickTarget target = switch (state.placeMode()) {
                case GEOMETRY -> state.pickTarget();
                case EXCHANGING -> player.isSneaking()
                        ? ManipulatorPickTarget.EXCHANGE_WHITELIST_SET
                        : ManipulatorPickTarget.EXCHANGE_REPLACEMENT;
                case CABLES -> ManipulatorPickTarget.CABLE;
                case COPYING, MOVING -> null;
            };
            if (target == null) return;
            state.setPickTarget(target);
            ManipulatorMaterialPicker.Result result = ManipulatorMaterialPicker.apply(state, specification,
                    player.isSneaking() && state.placeMode() == ManipulatorPlaceMode.GEOMETRY);
            state.setPendingAction(ManipulatorPendingAction.NONE);
            manipulator.saveState(stack, state);
            ApplyGrayMod.LOGGER.info("Matter Manipulator material picked by {}: target={}, geometry={}/{}/{}/{}",
                    player.getName(), state.pickTarget(), state.geometryConfiguration().corners().select(new java.util.Random(0L)),
                    state.geometryConfiguration().edges().select(new java.util.Random(0L)),
                    state.geometryConfiguration().faces().select(new java.util.Random(0L)),
                    state.geometryConfiguration().volumes().select(new java.util.Random(0L)));
            player.inventory.markDirty();
            player.inventoryContainer.detectAndSendChanges();
            MatterManipulatorNetwork.sendStateTo(player, hand, state);
            String materialName = specification.isFluid() ? specification.fluidStack().getLocalizedName()
                    : specification.toStack().getDisplayName();
            player.sendStatusMessage(new TextComponentTranslation(result == ManipulatorMaterialPicker.Result.ADDED
                    ? "applygray.matter_manipulator.pick.add" : "applygray.matter_manipulator.pick.set",
                    materialName), true);
        }
    }
}
