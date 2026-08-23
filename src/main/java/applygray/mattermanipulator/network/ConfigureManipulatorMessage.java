package applygray.mattermanipulator.network;

import applygray.mattermanipulator.building.BlockSpec;
import applygray.mattermanipulator.item.ItemMatterManipulator;
import applygray.mattermanipulator.server.MatterManipulatorBuildManager;
import applygray.mattermanipulator.state.ManipulatorCapability;
import applygray.mattermanipulator.state.ManipulatorLocation;
import applygray.mattermanipulator.state.ManipulatorPlaceMode;
import applygray.mattermanipulator.state.ManipulatorPickTarget;
import applygray.mattermanipulator.state.ManipulatorPendingAction;
import applygray.mattermanipulator.state.ManipulatorRemovalMode;
import applygray.mattermanipulator.state.ManipulatorSelectionActions;
import applygray.mattermanipulator.state.ManipulatorShape;
import applygray.mattermanipulator.state.ManipulatorState;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import io.netty.buffer.ByteBuf;

/** Bounded configuration intent. The server reads every actual inventory stack and capability itself. */
public final class ConfigureManipulatorMessage implements IMessage {

    private EnumHand hand;
    private Action action;
    private int value;

    public ConfigureManipulatorMessage() {}

    public ConfigureManipulatorMessage(EnumHand hand, Action action) {
        this(hand, action, 0);
    }

    public ConfigureManipulatorMessage(EnumHand hand, Action action, int value) {
        this.hand = hand;
        this.action = action;
        this.value = value;
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        hand = buffer.readBoolean() ? EnumHand.MAIN_HAND : EnumHand.OFF_HAND;
        int actionId = buffer.readUnsignedByte();
        action = actionId < Action.values().length ? Action.values()[actionId] : null;
        value = buffer.readInt();
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeBoolean(hand == EnumHand.MAIN_HAND);
        buffer.writeByte(action.ordinal());
        buffer.writeInt(value);
    }

    public enum Action {
        SET_PLACE_MODE,
        SET_SHAPE,
        SET_REMOVAL_MODE,
        ROTATE_X_NEGATIVE,
        ROTATE_X_POSITIVE,
        ROTATE_Y_NEGATIVE,
        ROTATE_Y_POSITIVE,
        ROTATE_Z_NEGATIVE,
        ROTATE_Z_POSITIVE,
        FLIP_X,
        FLIP_Y,
        FLIP_Z,
        SET_COPY_REPEAT_X,
        SET_COPY_REPEAT_Y,
        SET_COPY_REPEAT_Z,
        TOGGLE_SMART_COPY,
        CLEAR_ALL_GEOMETRY,
        CLEAR_EXCHANGE_WHITELIST,
        REQUEST_UPLINK_MISSING,
        REQUEST_UPLINK_ALL,
        CANCEL_UPLINK_CRAFTING,
        MARK_COPY,
        MARK_CUT,
        MARK_PASTE,
        MARK_ARRAY,
        RESET_SELECTIONS,
        CLEAR_SELECTION_A,
        CLEAR_SELECTION_B,
        CLEAR_SELECTION_C,
        RESET_COPY_TRANSFORM,
        RESET_COPY_REPEATS,
        SWAP_MOVE_REGION,
        SET_SELECTION_A_X,
        SET_SELECTION_A_Y,
        SET_SELECTION_A_Z,
        SET_SELECTION_B_X,
        SET_SELECTION_B_Y,
        SET_SELECTION_B_Z,
        SET_SELECTION_C_X,
        SET_SELECTION_C_Y,
        SET_SELECTION_C_Z,
        SHIFT_SELECTIONS_X,
        SHIFT_SELECTIONS_Y,
        SHIFT_SELECTIONS_Z,
        PICK_CORNER,
        PICK_EDGE,
        PICK_FACE,
        PICK_VOLUME,
        PICK_ALL,
        PICK_EXCHANGE_WHITELIST_SET,
        PICK_EXCHANGE_WHITELIST_ADD,
        PICK_EXCHANGE_REPLACEMENT,
        PICK_CABLE
    }

    public static final class Handler implements IMessageHandler<ConfigureManipulatorMessage, IMessage> {

        @Override
        public IMessage onMessage(ConfigureManipulatorMessage message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> update(player, message));
            return null;
        }

        private static void update(EntityPlayerMP player, ConfigureManipulatorMessage message) {
            if (message.action == null) return;
            ItemStack stack = player.getHeldItem(message.hand);
            if (!(stack.getItem() instanceof ItemMatterManipulator manipulator)) return;

            ManipulatorState state = manipulator.state(stack);
            try {
                switch (message.action) {
                    case SET_PLACE_MODE -> setPlaceMode(manipulator, stack, state, message.value);
                    case SET_SHAPE -> {
                        requireConfiguration(manipulator, stack, state);
                        state.setShape(enumValue(ManipulatorShape.values(), message.value));
                        state.clearSelections();
                    }
                    case SET_REMOVAL_MODE -> {
                        if (!manipulator.hasCapability(stack, ManipulatorCapability.REMOVAL)) {
                            throw new IllegalArgumentException("This manipulator cannot remove blocks");
                        }
                        state.setRemovalMode(enumValue(ManipulatorRemovalMode.values(), message.value));
                    }
                    case ROTATE_X_NEGATIVE, ROTATE_X_POSITIVE, ROTATE_Y_NEGATIVE, ROTATE_Y_POSITIVE,
                            ROTATE_Z_NEGATIVE, ROTATE_Z_POSITIVE -> {
                        requireCapability(manipulator, stack, ManipulatorCapability.COPYING);
                        net.minecraft.util.EnumFacing.Axis axis = switch (message.action) {
                            case ROTATE_X_NEGATIVE, ROTATE_X_POSITIVE -> net.minecraft.util.EnumFacing.Axis.X;
                            case ROTATE_Y_NEGATIVE, ROTATE_Y_POSITIVE -> net.minecraft.util.EnumFacing.Axis.Y;
                            case ROTATE_Z_NEGATIVE, ROTATE_Z_POSITIVE -> net.minecraft.util.EnumFacing.Axis.Z;
                            default -> throw new AssertionError();
                        };
                        boolean positive = message.action == Action.ROTATE_X_POSITIVE ||
                                message.action == Action.ROTATE_Y_POSITIVE ||
                                message.action == Action.ROTATE_Z_POSITIVE;
                        state.setCopyTransform(state.copyTransform().rotate(axis, positive));
                    }
                    case FLIP_X, FLIP_Y, FLIP_Z -> {
                        requireCapability(manipulator, stack, ManipulatorCapability.COPYING);
                        net.minecraft.util.EnumFacing.Axis axis = switch (message.action) {
                            case FLIP_X -> net.minecraft.util.EnumFacing.Axis.X;
                            case FLIP_Y -> net.minecraft.util.EnumFacing.Axis.Y;
                            case FLIP_Z -> net.minecraft.util.EnumFacing.Axis.Z;
                            default -> throw new AssertionError();
                        };
                        state.setCopyTransform(state.copyTransform().flip(axis));
                    }
                    case SET_COPY_REPEAT_X -> updateRepeats(state, message.value, state.copyRepeatY(),
                            state.copyRepeatZ(), manipulator, stack);
                    case SET_COPY_REPEAT_Y -> updateRepeats(state, state.copyRepeatX(), message.value,
                            state.copyRepeatZ(), manipulator, stack);
                    case SET_COPY_REPEAT_Z -> updateRepeats(state, state.copyRepeatX(), state.copyRepeatY(),
                            message.value, manipulator, stack);
                    case TOGGLE_SMART_COPY -> {
                        requireCapability(manipulator, stack, ManipulatorCapability.SMART_COPY);
                        state.setSmartCopy(!state.smartCopy());
                    }
                    case CLEAR_ALL_GEOMETRY -> {
                        requireConfiguration(manipulator, stack, state);
                        state.geometryConfiguration().setAll(BlockSpec.air());
                    }
                    case CLEAR_EXCHANGE_WHITELIST -> {
                        requireCapability(manipulator, stack, ManipulatorCapability.EXCHANGING);
                        state.exchangeWhitelist().clear();
                    }
                    case REQUEST_UPLINK_MISSING -> {
                        MatterManipulatorBuildManager.requestUplinkCrafting(player, message.hand, false);
                        return;
                    }
                    case REQUEST_UPLINK_ALL -> {
                        MatterManipulatorBuildManager.requestUplinkCrafting(player, message.hand, true);
                        return;
                    }
                    case CANCEL_UPLINK_CRAFTING -> {
                        MatterManipulatorBuildManager.cancelUplinkCrafting(player, message.hand);
                        return;
                    }
                    case MARK_COPY -> {
                        requireCapability(manipulator, stack, ManipulatorCapability.COPYING);
                        ManipulatorSelectionActions.beginCopy(state);
                    }
                    case MARK_CUT -> {
                        requireCapability(manipulator, stack, ManipulatorCapability.MOVING);
                        ManipulatorSelectionActions.beginMove(state);
                    }
                    case MARK_PASTE -> preparePaste(manipulator, stack, state);
                    case MARK_ARRAY -> {
                        requireCapability(manipulator, stack, ManipulatorCapability.COPYING);
                        state.setPendingAction(ManipulatorPendingAction.MARK_ARRAY);
                    }
                    case RESET_SELECTIONS -> ManipulatorSelectionActions.reset(state);
                    case CLEAR_SELECTION_A -> state.setSelectionA(null);
                    case CLEAR_SELECTION_B -> state.setSelectionB(null);
                    case CLEAR_SELECTION_C -> state.setSelectionC(null);
                    case RESET_COPY_TRANSFORM -> ManipulatorSelectionActions.resetTransform(state);
                    case RESET_COPY_REPEATS -> state.setCopyRepeats(1, 1, 1);
                    case SWAP_MOVE_REGION -> swapMoveRegion(state);
                    case SET_SELECTION_A_X -> setCoordinate(player, state, 0, 0, message.value);
                    case SET_SELECTION_A_Y -> setCoordinate(player, state, 0, 1, message.value);
                    case SET_SELECTION_A_Z -> setCoordinate(player, state, 0, 2, message.value);
                    case SET_SELECTION_B_X -> setCoordinate(player, state, 1, 0, message.value);
                    case SET_SELECTION_B_Y -> setCoordinate(player, state, 1, 1, message.value);
                    case SET_SELECTION_B_Z -> setCoordinate(player, state, 1, 2, message.value);
                    case SET_SELECTION_C_X -> setCoordinate(player, state, 2, 0, message.value);
                    case SET_SELECTION_C_Y -> setCoordinate(player, state, 2, 1, message.value);
                    case SET_SELECTION_C_Z -> setCoordinate(player, state, 2, 2, message.value);
                    case SHIFT_SELECTIONS_X -> ManipulatorSelectionActions.shiftSourceRegion(state,
                            new BlockPos(message.value, 0, 0));
                    case SHIFT_SELECTIONS_Y -> ManipulatorSelectionActions.shiftSourceRegion(state,
                            new BlockPos(0, message.value, 0));
                    case SHIFT_SELECTIONS_Z -> ManipulatorSelectionActions.shiftSourceRegion(state,
                            new BlockPos(0, 0, message.value));
                    case PICK_CORNER -> setPick(state, ManipulatorPickTarget.CORNER, ManipulatorPendingAction.GEOM_SELECTING_BLOCK);
                    case PICK_EDGE -> setPick(state, ManipulatorPickTarget.EDGE, ManipulatorPendingAction.GEOM_SELECTING_BLOCK);
                    case PICK_FACE -> setPick(state, ManipulatorPickTarget.FACE, ManipulatorPendingAction.GEOM_SELECTING_BLOCK);
                    case PICK_VOLUME -> setPick(state, ManipulatorPickTarget.VOLUME, ManipulatorPendingAction.GEOM_SELECTING_BLOCK);
                    case PICK_ALL -> setPick(state, ManipulatorPickTarget.ALL, ManipulatorPendingAction.GEOM_SELECTING_BLOCK);
                    case PICK_EXCHANGE_WHITELIST_SET -> setPick(state, ManipulatorPickTarget.EXCHANGE_WHITELIST_SET, ManipulatorPendingAction.EXCH_SET_REPLACE);
                    case PICK_EXCHANGE_WHITELIST_ADD -> setPick(state, ManipulatorPickTarget.EXCHANGE_WHITELIST_ADD, ManipulatorPendingAction.EXCH_ADD_REPLACE);
                    case PICK_EXCHANGE_REPLACEMENT -> setPick(state, ManipulatorPickTarget.EXCHANGE_REPLACEMENT, ManipulatorPendingAction.EXCH_SET_TARGET);
                    case PICK_CABLE -> setPick(state, ManipulatorPickTarget.CABLE, ManipulatorPendingAction.PICK_CABLE);
                }
            } catch (IllegalArgumentException exception) {
                player.sendStatusMessage(new TextComponentTranslation("applygray.matter_manipulator.config.denied"), true);
                return;
            }

            manipulator.saveState(stack, state);
            player.inventory.markDirty();
            player.inventoryContainer.detectAndSendChanges();
            MatterManipulatorNetwork.sendStateTo(player, message.hand, state);
        }

        private static void setPlaceMode(ItemMatterManipulator manipulator, ItemStack stack, ManipulatorState state,
                                         int modeId) {
            ManipulatorPlaceMode mode = enumValue(ManipulatorPlaceMode.values(), modeId);
            ManipulatorCapability capability = switch (mode) {
                case GEOMETRY -> ManipulatorCapability.GEOMETRY;
                case COPYING -> ManipulatorCapability.COPYING;
                case MOVING -> ManipulatorCapability.MOVING;
                case EXCHANGING -> ManipulatorCapability.EXCHANGING;
                case CABLES -> ManipulatorCapability.CABLES;
            };
            requireCapability(manipulator, stack, capability);
            state.setPlaceMode(mode);
            state.setPendingAction(ManipulatorPendingAction.NONE);
            state.setPickTarget(switch (mode) {
                case GEOMETRY -> ManipulatorPickTarget.ALL;
                case EXCHANGING -> ManipulatorPickTarget.EXCHANGE_REPLACEMENT;
                case CABLES -> ManipulatorPickTarget.CABLE;
                case COPYING, MOVING -> state.pickTarget();
            });
            state.clearSelections();
            if (applygray.mattermanipulator.config.MatterManipulatorConfig.clearTransformWithSelections) {
                ManipulatorSelectionActions.resetTransform(state);
            }
        }

        private static void setPick(ManipulatorState state, ManipulatorPickTarget target,
                                    ManipulatorPendingAction action) {
            state.setPickTarget(target);
            state.setPendingAction(action);
        }

        private static void preparePaste(ItemMatterManipulator manipulator, ItemStack stack, ManipulatorState state) {
            ManipulatorPlaceMode mode = state.placeMode();
            if (mode != ManipulatorPlaceMode.COPYING && mode != ManipulatorPlaceMode.MOVING) {
                requireCapability(manipulator, stack, ManipulatorCapability.COPYING);
            }
            ManipulatorSelectionActions.preparePaste(state);
        }

        private static void updateRepeats(ManipulatorState state, int x, int y, int z, ItemMatterManipulator manipulator,
                                          ItemStack stack) {
            requireCapability(manipulator, stack, ManipulatorCapability.COPYING);
            state.setCopyRepeats(x, y, z);
        }

        private static void setCoordinate(EntityPlayerMP player, ManipulatorState state, int slot, int component,
                                          int value) {
            ManipulatorLocation current = switch (slot) {
                case 0 -> state.selectionA();
                case 1 -> state.selectionB();
                case 2 -> state.selectionC();
                default -> throw new IllegalArgumentException("Invalid selection slot");
            };
            int dimension = current == null ? player.dimension : current.dimension();
            BlockPos position = current == null ? BlockPos.ORIGIN : current.position();
            BlockPos updated = switch (component) {
                case 0 -> new BlockPos(value, position.getY(), position.getZ());
                case 1 -> new BlockPos(position.getX(), value, position.getZ());
                case 2 -> new BlockPos(position.getX(), position.getY(), value);
                default -> throw new IllegalArgumentException("Invalid coordinate component");
            };
            ManipulatorLocation location = new ManipulatorLocation(dimension, updated);
            switch (slot) {
                case 0 -> state.setSelectionA(location);
                case 1 -> state.setSelectionB(location);
                case 2 -> state.setSelectionC(location);
                default -> throw new AssertionError();
            }
        }

        private static void swapMoveRegion(ManipulatorState state) {
            ManipulatorLocation a = state.selectionA();
            ManipulatorLocation b = state.selectionB();
            ManipulatorLocation c = state.selectionC();
            if (a == null || b == null || c == null || a.dimension() != b.dimension() || a.dimension() != c.dimension()) {
                throw new IllegalArgumentException("A complete same-dimension move region is required");
            }
            BlockPos size = b.position().subtract(a.position());
            state.setSelectionA(c);
            state.setSelectionB(new ManipulatorLocation(c.dimension(), c.position().add(size)));
            state.setSelectionC(a);
        }

        private static void requireConfiguration(ItemMatterManipulator manipulator, ItemStack stack,
                                                 ManipulatorState state) {
            if (manipulator.hasCapability(stack, ManipulatorCapability.CONFIGURATION) ||
                    state.placeMode() == ManipulatorPlaceMode.EXCHANGING || state.placeMode() == ManipulatorPlaceMode.CABLES) {
                return;
            }
            throw new IllegalArgumentException("This manipulator cannot configure the selected mode");
        }

        private static void requireCapability(ItemMatterManipulator manipulator, ItemStack stack,
                                              ManipulatorCapability capability) {
            if (!manipulator.hasCapability(stack, capability)) {
                throw new IllegalArgumentException("This manipulator lacks the required capability");
            }
        }

        private static <T> T enumValue(T[] values, int ordinal) {
            if (ordinal < 0 || ordinal >= values.length) throw new IllegalArgumentException("Invalid enum value");
            return values[ordinal];
        }

    }
}
