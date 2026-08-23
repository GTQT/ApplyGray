package applygray.mattermanipulator.state;

import java.util.EnumSet;
import java.util.Objects;

import applygray.mattermanipulator.building.GeometryConfiguration;
import applygray.mattermanipulator.building.BlockSpec;
import applygray.mattermanipulator.building.WeightedBlockList;
import applygray.mattermanipulator.planning.GeometrySelection;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;

/**
 * Target-only persistent state for one Matter Manipulator item.
 *
 * <p>Unknown schemas are discarded instead of being interpreted as the old 1.7.10 JSON payload.</p>
 */
public final class ManipulatorState {

    public static final int SCHEMA_VERSION = 11;

    private static final String KEY_SCHEMA = "Schema";
    private static final String KEY_SHAPE = "Shape";
    private static final String KEY_PLACE_MODE = "PlaceMode";
    private static final String KEY_REMOVAL_MODE = "RemovalMode";
    private static final String KEY_UPGRADES = "Upgrades";
    private static final String KEY_GEOMETRY = "Geometry";
    private static final String KEY_SELECTION_A = "SelectionA";
    private static final String KEY_SELECTION_B = "SelectionB";
    private static final String KEY_SELECTION_C = "SelectionC";
    private static final String KEY_COPY_TRANSFORM = "CopyTransform";
    private static final String KEY_COPY_REPEAT_X = "CopyRepeatX";
    private static final String KEY_COPY_REPEAT_Y = "CopyRepeatY";
    private static final String KEY_COPY_REPEAT_Z = "CopyRepeatZ";
    private static final String KEY_SMART_COPY = "SmartCopy";
    private static final String KEY_EXCHANGE_WHITELIST = "ExchangeWhitelist";
    private static final String KEY_EXCHANGE_REPLACEMENT = "ExchangeReplacement";
    private static final String KEY_CABLE_MATERIAL = "CableMaterial";
    private static final String KEY_AE2_NETWORK = "Ae2Network";
    private static final String KEY_UPLINK_ADDRESS = "UplinkAddress";
    private static final String KEY_PICK_TARGET = "PickTarget";
    private static final String KEY_PENDING_ACTION = "PendingAction";

    private ManipulatorShape shape = ManipulatorShape.LINE;
    private ManipulatorPlaceMode placeMode = ManipulatorPlaceMode.GEOMETRY;
    private ManipulatorRemovalMode removalMode = ManipulatorRemovalMode.ALL;
    private final EnumSet<ManipulatorUpgrade> installedUpgrades = EnumSet.noneOf(ManipulatorUpgrade.class);
    private GeometryConfiguration geometryConfiguration = new GeometryConfiguration();
    private ManipulatorLocation selectionA;
    private ManipulatorLocation selectionB;
    private ManipulatorLocation selectionC;
    private ManipulatorTransform copyTransform = ManipulatorTransform.identity();
    private int copyRepeatX = 1;
    private int copyRepeatY = 1;
    private int copyRepeatZ = 1;
    private boolean smartCopy;
    private WeightedBlockList exchangeWhitelist = new WeightedBlockList();
    private WeightedBlockList exchangeReplacement = new WeightedBlockList(BlockSpec.air());
    private BlockSpec cableMaterial = BlockSpec.air();
    private ManipulatorLocation ae2NetworkLocation;
    private Long uplinkAddress;
    private ManipulatorPickTarget pickTarget = ManipulatorPickTarget.ALL;
    private ManipulatorPendingAction pendingAction = ManipulatorPendingAction.NONE;

    public ManipulatorShape shape() {
        return shape;
    }

    public void setShape(ManipulatorShape shape) {
        this.shape = Objects.requireNonNull(shape, "shape");
    }

    public ManipulatorPlaceMode placeMode() {
        return placeMode;
    }

    public void setPlaceMode(ManipulatorPlaceMode placeMode) {
        this.placeMode = Objects.requireNonNull(placeMode, "placeMode");
    }

    public ManipulatorRemovalMode removalMode() {
        return removalMode;
    }

    public void setRemovalMode(ManipulatorRemovalMode removalMode) {
        this.removalMode = Objects.requireNonNull(removalMode, "removalMode");
    }

    public ManipulatorLocation selectionA() {
        return selectionA;
    }

    public void setSelectionA(ManipulatorLocation selectionA) {
        this.selectionA = selectionA;
    }

    public ManipulatorLocation selectionB() {
        return selectionB;
    }

    public void setSelectionB(ManipulatorLocation selectionB) {
        this.selectionB = selectionB;
    }

    public ManipulatorLocation selectionC() {
        return selectionC;
    }

    public void setSelectionC(ManipulatorLocation selectionC) {
        this.selectionC = selectionC;
    }

    public void clearSelections() {
        selectionA = null;
        selectionB = null;
        selectionC = null;
    }

    public ManipulatorTransform copyTransform() {
        return copyTransform;
    }

    public void setCopyTransform(ManipulatorTransform copyTransform) {
        this.copyTransform = Objects.requireNonNull(copyTransform, "copyTransform");
    }

    public int copyRepeatX() {
        return copyRepeatX;
    }

    public int copyRepeatY() {
        return copyRepeatY;
    }

    public int copyRepeatZ() {
        return copyRepeatZ;
    }

    public void setCopyRepeats(int x, int y, int z) {
        copyRepeatX = validateRepeat(x);
        copyRepeatY = validateRepeat(y);
        copyRepeatZ = validateRepeat(z);
    }

    public boolean smartCopy() {
        return smartCopy;
    }

    public void setSmartCopy(boolean smartCopy) {
        this.smartCopy = smartCopy;
    }

    public WeightedBlockList exchangeWhitelist() {
        return exchangeWhitelist;
    }

    public WeightedBlockList exchangeReplacement() {
        return exchangeReplacement;
    }

    public BlockSpec cableMaterial() {
        return cableMaterial;
    }

    public void setCableMaterial(BlockSpec cableMaterial) {
        this.cableMaterial = Objects.requireNonNull(cableMaterial, "cableMaterial");
    }

    /** Location of the AE2 wireless access point selected through the security terminal linking slot. */
    public ManipulatorLocation ae2NetworkLocation() {
        return ae2NetworkLocation;
    }

    public void setAe2NetworkLocation(ManipulatorLocation location) {
        this.ae2NetworkLocation = location;
    }

    public Long uplinkAddress() {
        return uplinkAddress;
    }

    public void setUplinkAddress(Long uplinkAddress) {
        if (uplinkAddress != null && uplinkAddress == 0L) {
            throw new IllegalArgumentException("Uplink addresses must be non-zero");
        }
        this.uplinkAddress = uplinkAddress;
    }

    public ManipulatorPickTarget pickTarget() {
        return pickTarget;
    }

    public void setPickTarget(ManipulatorPickTarget pickTarget) {
        this.pickTarget = Objects.requireNonNull(pickTarget, "pickTarget");
    }

    public ManipulatorPendingAction pendingAction() { return pendingAction; }

    public void setPendingAction(ManipulatorPendingAction action) {
        this.pendingAction = Objects.requireNonNull(action, "action");
    }

    public boolean hasUpgrade(ManipulatorUpgrade upgrade) {
        return installedUpgrades.contains(upgrade);
    }

    public boolean installUpgrade(ManipulatorUpgrade upgrade) {
        return installedUpgrades.add(Objects.requireNonNull(upgrade, "upgrade"));
    }

    public boolean removeUpgrade(ManipulatorUpgrade upgrade) {
        return installedUpgrades.remove(upgrade);
    }

    public EnumSet<ManipulatorUpgrade> installedUpgrades() {
        return EnumSet.copyOf(installedUpgrades);
    }

    public GeometryConfiguration geometryConfiguration() {
        return geometryConfiguration;
    }

    public GeometrySelection geometrySelection() {
        return new GeometrySelection(shape, selectionA, selectionB, selectionC);
    }

    public boolean hasCompleteGeometrySelection() {
        return geometrySelection().isComplete();
    }

    public NBTTagCompound writeToNbt() {
        NBTTagCompound data = new NBTTagCompound();
        data.setInteger(KEY_SCHEMA, SCHEMA_VERSION);
        data.setString(KEY_SHAPE, shape.name());
        data.setString(KEY_PLACE_MODE, placeMode.name());
        data.setString(KEY_REMOVAL_MODE, removalMode.name());
        data.setInteger(KEY_UPGRADES, ManipulatorUpgrade.toMask(installedUpgrades));
        data.setTag(KEY_GEOMETRY, geometryConfiguration.writeToNbt());

        writeLocation(data, KEY_SELECTION_A, selectionA);
        writeLocation(data, KEY_SELECTION_B, selectionB);
        writeLocation(data, KEY_SELECTION_C, selectionC);
        data.setTag(KEY_COPY_TRANSFORM, copyTransform.writeToNbt());
        data.setInteger(KEY_COPY_REPEAT_X, copyRepeatX);
        data.setInteger(KEY_COPY_REPEAT_Y, copyRepeatY);
        data.setInteger(KEY_COPY_REPEAT_Z, copyRepeatZ);
        data.setBoolean(KEY_SMART_COPY, smartCopy);
        data.setTag(KEY_EXCHANGE_WHITELIST, exchangeWhitelist.writeToNbt());
        data.setTag(KEY_EXCHANGE_REPLACEMENT, exchangeReplacement.writeToNbt());
        data.setTag(KEY_CABLE_MATERIAL, cableMaterial.writeToNbt());
        writeLocation(data, KEY_AE2_NETWORK, ae2NetworkLocation);
        if (uplinkAddress != null) data.setLong(KEY_UPLINK_ADDRESS, uplinkAddress);
        data.setString(KEY_PICK_TARGET, pickTarget.name());
        data.setString(KEY_PENDING_ACTION, pendingAction.name());
        return data;
    }

    public static ManipulatorState readFromNbt(NBTTagCompound data) {
        if (data == null || !data.hasKey(KEY_SCHEMA, Constants.NBT.TAG_INT)) {
            return new ManipulatorState();
        }

        int schema = data.getInteger(KEY_SCHEMA);
        if (schema != SCHEMA_VERSION) return new ManipulatorState();

        ManipulatorState state = new ManipulatorState();
        state.shape = readEnum(data, KEY_SHAPE, ManipulatorShape.class, state.shape);
        state.placeMode = readEnum(data, KEY_PLACE_MODE, ManipulatorPlaceMode.class, state.placeMode);
        state.removalMode = readEnum(data, KEY_REMOVAL_MODE, ManipulatorRemovalMode.class, state.removalMode);
        if (data.hasKey(KEY_UPGRADES, Constants.NBT.TAG_INT)) {
            state.installedUpgrades.addAll(ManipulatorUpgrade.fromMask(data.getInteger(KEY_UPGRADES)));
        }
        if (data.hasKey(KEY_GEOMETRY, Constants.NBT.TAG_COMPOUND)) {
            state.geometryConfiguration = GeometryConfiguration.readFromNbt(data.getCompoundTag(KEY_GEOMETRY));
        }
        state.selectionA = ManipulatorLocation.readFrom(data, KEY_SELECTION_A);
        state.selectionB = ManipulatorLocation.readFrom(data, KEY_SELECTION_B);
        state.selectionC = ManipulatorLocation.readFrom(data, KEY_SELECTION_C);
        if (data.hasKey(KEY_COPY_TRANSFORM, Constants.NBT.TAG_COMPOUND)) {
            state.copyTransform = ManipulatorTransform.readFromNbt(data.getCompoundTag(KEY_COPY_TRANSFORM));
        }
        state.copyRepeatX = readRepeat(data, KEY_COPY_REPEAT_X);
        state.copyRepeatY = readRepeat(data, KEY_COPY_REPEAT_Y);
        state.copyRepeatZ = readRepeat(data, KEY_COPY_REPEAT_Z);
        state.smartCopy = data.getBoolean(KEY_SMART_COPY);
        if (data.hasKey(KEY_EXCHANGE_WHITELIST, Constants.NBT.TAG_COMPOUND)) {
            state.exchangeWhitelist = WeightedBlockList.readFromNbt(data.getCompoundTag(KEY_EXCHANGE_WHITELIST), null);
        }
        if (data.hasKey(KEY_EXCHANGE_REPLACEMENT, Constants.NBT.TAG_COMPOUND)) {
            state.exchangeReplacement = WeightedBlockList.readFromNbt(data.getCompoundTag(KEY_EXCHANGE_REPLACEMENT),
                    BlockSpec.air());
        }
        if (data.hasKey(KEY_CABLE_MATERIAL, Constants.NBT.TAG_COMPOUND)) {
            state.cableMaterial = BlockSpec.readFromNbt(data.getCompoundTag(KEY_CABLE_MATERIAL));
        }
        state.ae2NetworkLocation = ManipulatorLocation.readFrom(data, KEY_AE2_NETWORK);
        if (data.hasKey(KEY_UPLINK_ADDRESS, Constants.NBT.TAG_LONG)) {
            long address = data.getLong(KEY_UPLINK_ADDRESS);
            state.uplinkAddress = address == 0L ? null : address;
        }
        state.pickTarget = readEnum(data, KEY_PICK_TARGET, ManipulatorPickTarget.class, state.pickTarget);
        state.pendingAction = readEnum(data, KEY_PENDING_ACTION, ManipulatorPendingAction.class, state.pendingAction);
        return state;
    }

    private static void writeLocation(NBTTagCompound data, String key, ManipulatorLocation location) {
        if (location != null) location.writeTo(data, key);
    }

    private static <T extends Enum<T>> T readEnum(NBTTagCompound data, String key, Class<T> type, T fallback) {
        if (!data.hasKey(key, Constants.NBT.TAG_STRING)) return fallback;
        try {
            return Enum.valueOf(type, data.getString(key));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static int readRepeat(NBTTagCompound data, String key) {
        if (!data.hasKey(key, Constants.NBT.TAG_INT)) return 1;
        int repeat = data.getInteger(key);
        return repeat != 0 && repeat >= -64 && repeat <= 64 ? repeat : 1;
    }

    private static int validateRepeat(int repeat) {
        if (repeat == 0 || repeat < -64 || repeat > 64) throw new IllegalArgumentException("copy spans must be between -64 and 64, excluding zero");
        return repeat;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ManipulatorState state)) return false;
        return shape == state.shape && placeMode == state.placeMode && removalMode == state.removalMode &&
                copyTransform.equals(state.copyTransform) && smartCopy == state.smartCopy &&
                copyRepeatX == state.copyRepeatX && copyRepeatY == state.copyRepeatY && copyRepeatZ == state.copyRepeatZ &&
                exchangeWhitelist.equals(state.exchangeWhitelist) && exchangeReplacement.equals(state.exchangeReplacement) &&
                 cableMaterial.equals(state.cableMaterial) && Objects.equals(ae2NetworkLocation, state.ae2NetworkLocation) &&
                 Objects.equals(uplinkAddress, state.uplinkAddress) &&
                pickTarget == state.pickTarget && pendingAction == state.pendingAction &&
                installedUpgrades.equals(state.installedUpgrades) &&
                geometryConfiguration.equals(state.geometryConfiguration) &&
                Objects.equals(selectionA, state.selectionA) && Objects.equals(selectionB, state.selectionB) &&
                Objects.equals(selectionC, state.selectionC);
    }

    @Override
    public int hashCode() {
        return Objects.hash(shape, placeMode, removalMode, installedUpgrades, geometryConfiguration, selectionA,
                selectionB, selectionC, copyTransform, copyRepeatX, copyRepeatY, copyRepeatZ, smartCopy,
                exchangeWhitelist, exchangeReplacement, cableMaterial, ae2NetworkLocation, uplinkAddress, pickTarget,
                pendingAction);
    }
}
