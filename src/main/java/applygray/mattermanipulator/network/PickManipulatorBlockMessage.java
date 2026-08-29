package applygray.mattermanipulator.network;

import applygray.ApplyGrayMod;
import applygray.mattermanipulator.building.BlockSpec;
import applygray.mattermanipulator.item.ItemMatterManipulator;
import applygray.mattermanipulator.state.ManipulatorMaterialPicker;
import applygray.mattermanipulator.state.ManipulatorState;
import applygray.mattermanipulator.state.ManipulatorPlaceMode;
import applygray.mattermanipulator.state.ManipulatorPickTarget;
import applygray.mattermanipulator.state.ManipulatorPendingAction;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import io.netty.buffer.ByteBuf;

/**
 * Middle-click intent.
 *
 * <p>The crosshair target travels with the request because a plain server ray trace cannot reproduce it: the client
 * highlight comes from each block's own collision shape, which is how an AE2 cable bus distinguishes the cable from
 * the bus next to it. The server still resolves the material itself from the world, and it re-traces on its own when
 * the reported target is missing or out of reach.</p>
 */
public final class PickManipulatorBlockMessage implements IMessage {

    /** Distance budget for a reported target: creative block reach plus one block of slack, squared. */
    private static final double MAX_TARGET_DISTANCE_SQUARED = 49.0D;

    private EnumHand hand;
    private BlockPos target;
    private Vec3d hitVector;
    private EnumFacing side;

    public PickManipulatorBlockMessage() {}

    public PickManipulatorBlockMessage(EnumHand hand, RayTraceResult crosshairTarget) {
        this.hand = hand;
        if (crosshairTarget == null || crosshairTarget.typeOfHit != RayTraceResult.Type.BLOCK) return;
        if (crosshairTarget.getBlockPos() == null || crosshairTarget.hitVec == null) return;
        this.target = crosshairTarget.getBlockPos();
        this.hitVector = crosshairTarget.hitVec;
        this.side = crosshairTarget.sideHit == null ? EnumFacing.UP : crosshairTarget.sideHit;
    }
    @Override
    public void fromBytes(ByteBuf buffer) {
        hand = buffer.readBoolean() ? EnumHand.MAIN_HAND : EnumHand.OFF_HAND;
        if (!buffer.readBoolean()) return;
        target = BlockPos.fromLong(buffer.readLong());
        hitVector = new Vec3d(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        side = EnumFacing.VALUES[Math.floorMod(buffer.readByte(), EnumFacing.VALUES.length)];
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeBoolean(hand == EnumHand.MAIN_HAND);
        buffer.writeBoolean(target != null);
        if (target == null) return;
        buffer.writeLong(target.toLong());
        buffer.writeDouble(hitVector.x);
        buffer.writeDouble(hitVector.y);
        buffer.writeDouble(hitVector.z);
        buffer.writeByte(side.getIndex());
    }
    public static final class Handler implements IMessageHandler<PickManipulatorBlockMessage, IMessage> {

        @Override
        public IMessage onMessage(PickManipulatorBlockMessage message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> pick(player, message));
            return null;
        }

        private static void pick(EntityPlayerMP player, PickManipulatorBlockMessage message) {
            ItemStack stack = player.getHeldItem(message.hand);
            if (!(stack.getItem() instanceof ItemMatterManipulator manipulator)) return;

            RayTraceResult hit = reportedTarget(player, message);
            if (hit == null) hit = traceFromServer(player);
            ManipulatorState state = manipulator.state(stack);

            ManipulatorPickTarget target = switch (state.placeMode()) {
                case GEOMETRY -> state.pickTarget();
                case EXCHANGING -> player.isSneaking()
                        ? ManipulatorPickTarget.EXCHANGE_WHITELIST_SET
                        : ManipulatorPickTarget.EXCHANGE_REPLACEMENT;
                case CABLES -> ManipulatorPickTarget.CABLE;
                case COPYING, MOVING -> null;
            };
            if (target == null) return;
            // A middle-click miss is the canonical way to select air, matching the source manipulator behavior.
            BlockSpec specification = BlockSpec.fromPickBlock(player.world, player, hit);
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
            MatterManipulatorNetwork.sendStateTo(player, message.hand, state);
            Object materialName = specification.isAir() ? new TextComponentTranslation("tile.air.name")
                    : specification.isFluid() ? specification.fluidStack().getLocalizedName()
                    : specification.toStack().getDisplayName();
            player.sendStatusMessage(new TextComponentTranslation(result == ManipulatorMaterialPicker.Result.ADDED
                    ? "applygray.matter_manipulator.pick.add" : "applygray.matter_manipulator.pick.set",
                    materialName), true);
        }
        /** Rebuilds the client's crosshair hit after bounding it to a loaded, in-reach, non-air position. */
        private static RayTraceResult reportedTarget(EntityPlayerMP player, PickManipulatorBlockMessage message) {
            if (message.target == null || message.hitVector == null || message.side == null) return null;
            if (!player.world.isBlockLoaded(message.target)) return null;
            if (player.getPositionEyes(1.0F).squareDistanceTo(message.hitVector) > MAX_TARGET_DISTANCE_SQUARED) {
                return null;
            }
            IBlockState state = player.world.getBlockState(message.target);
            if (state.getBlock().isAir(state, player.world, message.target)) return null;
            return new RayTraceResult(message.hitVector, message.side, message.target);
        }

        private static RayTraceResult traceFromServer(EntityPlayerMP player) {
            Vec3d start = player.getPositionEyes(1.0F);
            // Include liquid collision shapes, matching GT5's middle-click material pick path.
            return player.world.rayTraceBlocks(start, start.add(player.getLook(1.0F).scale(6.0D)), true, true, false);
        }
    }
}
