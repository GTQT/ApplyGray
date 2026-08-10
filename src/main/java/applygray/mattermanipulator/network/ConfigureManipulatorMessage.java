package applygray.mattermanipulator.network;

import java.util.ArrayList;
import java.util.List;

import applygray.mattermanipulator.building.BlockSpec;
import applygray.mattermanipulator.building.WeightedBlockList;
import applygray.mattermanipulator.item.ItemMatterManipulator;
import applygray.mattermanipulator.planning.VoxelRole;
import applygray.mattermanipulator.server.MatterManipulatorBuildManager;
import applygray.mattermanipulator.state.ManipulatorCapability;
import applygray.mattermanipulator.state.ManipulatorMirror;
import applygray.mattermanipulator.state.ManipulatorPlaceMode;
import applygray.mattermanipulator.state.ManipulatorRemovalMode;
import applygray.mattermanipulator.state.ManipulatorRotation;
import applygray.mattermanipulator.state.ManipulatorShape;
import applygray.mattermanipulator.state.ManipulatorState;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import io.netty.buffer.ByteBuf;

/** Bounded configuration intent. The server reads every actual inventory stack and capability itself. */
public final class ConfigureManipulatorMessage implements IMessage {

    private static final int MAX_CONFIG_LIST_ENTRIES = 64;
    private static final int MAX_CONFIG_LIST_WEIGHT = 64;

    private EnumHand hand;
    private Action action;
    private VoxelRole role;
    private int inventorySlot;

    public ConfigureManipulatorMessage() {}

    public ConfigureManipulatorMessage(EnumHand hand, Action action) {
        this(hand, action, null, -1);
    }

    public ConfigureManipulatorMessage(EnumHand hand, VoxelRole role, int inventorySlot) {
        this(hand, Action.SET_ROLE_MATERIAL, role, inventorySlot);
    }

    public ConfigureManipulatorMessage(EnumHand hand, Action action, VoxelRole role, int inventorySlot) {
        this.hand = hand;
        this.action = action;
        this.role = role;
        this.inventorySlot = inventorySlot;
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        hand = buffer.readBoolean() ? EnumHand.MAIN_HAND : EnumHand.OFF_HAND;
        int actionId = buffer.readUnsignedByte();
        action = actionId < Action.values().length ? Action.values()[actionId] : null;
        int roleId = buffer.readUnsignedByte();
        role = roleId < VoxelRole.values().length ? VoxelRole.values()[roleId] : null;
        inventorySlot = buffer.readUnsignedByte() - 1;
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeBoolean(hand == EnumHand.MAIN_HAND);
        buffer.writeByte(action.ordinal());
        buffer.writeByte(role == null ? VoxelRole.values().length : role.ordinal());
        buffer.writeByte(inventorySlot + 1);
    }

    public enum Action {
        NEXT_PLACE_MODE,
        NEXT_SHAPE,
        NEXT_REMOVAL_MODE,
        NEXT_COPY_ROTATION,
        NEXT_COPY_MIRROR,
        NEXT_COPY_REPEAT_X,
        NEXT_COPY_REPEAT_Y,
        NEXT_COPY_REPEAT_Z,
        TOGGLE_SMART_COPY,
        SET_ROLE_MATERIAL,
        CLEAR_ROLE_MATERIAL,
        SET_EXCHANGE_WHITELIST,
        ADD_EXCHANGE_WHITELIST,
        CLEAR_EXCHANGE_WHITELIST,
        SET_EXCHANGE_REPLACEMENT,
        ADD_EXCHANGE_REPLACEMENT,
        CLEAR_EXCHANGE_REPLACEMENT,
        SET_CABLE_MATERIAL,
        CLEAR_CABLE_MATERIAL,
        REQUEST_UPLINK_MISSING,
        REQUEST_UPLINK_ALL,
        CANCEL_UPLINK_CRAFTING
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
                    case NEXT_PLACE_MODE -> nextPlaceMode(manipulator, stack, state);
                    case NEXT_SHAPE -> {
                        requireConfiguration(manipulator, stack, state);
                        state.setShape(next(state.shape(), ManipulatorShape.values()));
                        state.clearSelections();
                    }
                    case NEXT_REMOVAL_MODE -> {
                        if (!manipulator.hasCapability(stack, ManipulatorCapability.REMOVAL)) {
                            throw new IllegalArgumentException("This manipulator cannot remove blocks");
                        }
                        state.setRemovalMode(next(state.removalMode(), ManipulatorRemovalMode.values()));
                    }
                    case NEXT_COPY_ROTATION -> {
                        requireCapability(manipulator, stack, ManipulatorCapability.COPYING);
                        state.setCopyRotation(next(state.copyRotation(), ManipulatorRotation.values()));
                    }
                    case NEXT_COPY_MIRROR -> {
                        requireCapability(manipulator, stack, ManipulatorCapability.COPYING);
                        state.setCopyMirror(next(state.copyMirror(), ManipulatorMirror.values()));
                    }
                    case NEXT_COPY_REPEAT_X -> updateRepeats(state, nextRepeat(state.copyRepeatX()), state.copyRepeatY(),
                            state.copyRepeatZ(), manipulator, stack);
                    case NEXT_COPY_REPEAT_Y -> updateRepeats(state, state.copyRepeatX(), nextRepeat(state.copyRepeatY()),
                            state.copyRepeatZ(), manipulator, stack);
                    case NEXT_COPY_REPEAT_Z -> updateRepeats(state, state.copyRepeatX(), state.copyRepeatY(),
                            nextRepeat(state.copyRepeatZ()), manipulator, stack);
                    case TOGGLE_SMART_COPY -> {
                        requireCapability(manipulator, stack, ManipulatorCapability.SMART_COPY);
                        state.setSmartCopy(!state.smartCopy());
                    }
                    case SET_ROLE_MATERIAL -> setRoleMaterial(player, state, message.role, message.inventorySlot,
                            manipulator, stack);
                    case CLEAR_ROLE_MATERIAL -> clearRoleMaterial(state, message.role, manipulator, stack);
                    case SET_EXCHANGE_WHITELIST -> setExchangeMaterial(player, state, message.inventorySlot,
                            manipulator, stack, state.exchangeWhitelist(), true);
                    case ADD_EXCHANGE_WHITELIST -> setExchangeMaterial(player, state, message.inventorySlot,
                            manipulator, stack, state.exchangeWhitelist(), false);
                    case CLEAR_EXCHANGE_WHITELIST -> clearExchangeList(state, manipulator, stack,
                            state.exchangeWhitelist());
                    case SET_EXCHANGE_REPLACEMENT -> setExchangeMaterial(player, state, message.inventorySlot,
                            manipulator, stack, state.exchangeReplacement(), true);
                    case ADD_EXCHANGE_REPLACEMENT -> setExchangeMaterial(player, state, message.inventorySlot,
                            manipulator, stack, state.exchangeReplacement(), false);
                    case CLEAR_EXCHANGE_REPLACEMENT -> clearExchangeList(state, manipulator, stack,
                            state.exchangeReplacement());
                    case SET_CABLE_MATERIAL -> setCableMaterial(player, state, message.inventorySlot, manipulator,
                            stack);
                    case CLEAR_CABLE_MATERIAL -> clearCableMaterial(state, manipulator, stack);
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
                }
            } catch (ConfigurationException exception) {
                player.sendStatusMessage(new TextComponentTranslation(exception.translationKey), true);
                return;
            } catch (IllegalArgumentException exception) {
                player.sendStatusMessage(new TextComponentTranslation("applygray.matter_manipulator.config.denied"), true);
                return;
            }

            manipulator.saveState(stack, state);
            player.inventory.markDirty();
            player.inventoryContainer.detectAndSendChanges();
            MatterManipulatorNetwork.sendStateTo(player, message.hand, state);
        }

        private static void nextPlaceMode(ItemMatterManipulator manipulator, ItemStack stack, ManipulatorState state) {
            List<ManipulatorPlaceMode> allowed = new ArrayList<>();
            allowed.add(ManipulatorPlaceMode.GEOMETRY);
            if (manipulator.hasCapability(stack, ManipulatorCapability.COPYING)) {
                allowed.add(ManipulatorPlaceMode.COPYING);
            }
            if (manipulator.hasCapability(stack, ManipulatorCapability.MOVING)) {
                allowed.add(ManipulatorPlaceMode.MOVING);
            }
            if (manipulator.hasCapability(stack, ManipulatorCapability.EXCHANGING)) {
                allowed.add(ManipulatorPlaceMode.EXCHANGING);
            }
            if (manipulator.hasCapability(stack, ManipulatorCapability.CABLES)) {
                allowed.add(ManipulatorPlaceMode.CABLES);
            }
            int nextIndex = (allowed.indexOf(state.placeMode()) + 1) % allowed.size();
            state.setPlaceMode(allowed.get(nextIndex));
            state.clearSelections();
        }

        private static void updateRepeats(ManipulatorState state, int x, int y, int z, ItemMatterManipulator manipulator,
                                          ItemStack stack) {
            requireCapability(manipulator, stack, ManipulatorCapability.COPYING);
            state.setCopyRepeats(x, y, z);
        }

        private static void setRoleMaterial(EntityPlayerMP player, ManipulatorState state, VoxelRole role,
                                            int inventorySlot, ItemMatterManipulator manipulator, ItemStack stack) {
            requireConfiguration(manipulator, stack, state);
            if (role == null || inventorySlot < 0 || inventorySlot >= player.inventory.getSizeInventory()) {
                throw new IllegalArgumentException("Invalid material selection");
            }
            BlockSpec specification = materialAt(player, inventorySlot);
            state.geometryConfiguration().setSingle(role, specification);
        }

        private static void clearRoleMaterial(ManipulatorState state, VoxelRole role, ItemMatterManipulator manipulator,
                                              ItemStack stack) {
            requireConfiguration(manipulator, stack, state);
            if (role == null) throw new IllegalArgumentException("Invalid material role");
            state.geometryConfiguration().setSingle(role, BlockSpec.air());
        }

        private static void setExchangeMaterial(EntityPlayerMP player, ManipulatorState state, int inventorySlot,
                                                ItemMatterManipulator manipulator, ItemStack stack,
                                                WeightedBlockList list, boolean replace) {
            requireCapability(manipulator, stack, ManipulatorCapability.EXCHANGING);
            BlockSpec specification = materialAt(player, inventorySlot);
            if (replace) {
                list.setSingle(specification);
            } else {
                addBounded(list, specification);
            }
        }

        private static void clearExchangeList(ManipulatorState state, ItemMatterManipulator manipulator,
                                              ItemStack stack, WeightedBlockList list) {
            requireCapability(manipulator, stack, ManipulatorCapability.EXCHANGING);
            list.clear();
        }

        private static void setCableMaterial(EntityPlayerMP player, ManipulatorState state, int inventorySlot,
                                             ItemMatterManipulator manipulator, ItemStack stack) {
            requireCapability(manipulator, stack, ManipulatorCapability.CABLES);
            state.setCableMaterial(materialAt(player, inventorySlot));
        }

        private static void clearCableMaterial(ManipulatorState state, ItemMatterManipulator manipulator,
                                               ItemStack stack) {
            requireCapability(manipulator, stack, ManipulatorCapability.CABLES);
            state.setCableMaterial(BlockSpec.air());
        }

        private static BlockSpec materialAt(EntityPlayerMP player, int inventorySlot) {
            if (inventorySlot < 0 || inventorySlot >= player.inventory.getSizeInventory()) {
                throw new ConfigurationException("applygray.matter_manipulator.config.invalid_material");
            }
            BlockSpec specification = BlockSpec.of(player.inventory.getStackInSlot(inventorySlot));
            if (specification.isAir()) {
                throw new ConfigurationException("applygray.matter_manipulator.config.invalid_material");
            }
            return specification;
        }

        private static void addBounded(WeightedBlockList list, BlockSpec specification) {
            WeightedBlockList.Entry existing = list.entries().stream()
                    .filter(entry -> entry.spec().equals(specification))
                    .findFirst()
                    .orElse(null);
            if (existing == null && list.entries().size() >= MAX_CONFIG_LIST_ENTRIES) {
                throw new ConfigurationException("applygray.matter_manipulator.config.list_full");
            }
            if (existing != null && existing.weight() >= MAX_CONFIG_LIST_WEIGHT) {
                throw new ConfigurationException("applygray.matter_manipulator.config.list_full");
            }
            list.add(specification);
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

        private static int nextRepeat(int current) {
            return switch (current) {
                case 1 -> 2;
                case 2 -> 4;
                case 4 -> 8;
                case 8 -> 16;
                case 16 -> 32;
                case 32 -> 64;
                default -> 1;
            };
        }

        private static <T extends Enum<T>> T next(T current, T[] values) {
            return values[(current.ordinal() + 1) % values.length];
        }

        private static final class ConfigurationException extends IllegalArgumentException {

            private final String translationKey;

            private ConfigurationException(String translationKey) {
                this.translationKey = translationKey;
            }
        }
    }
}
