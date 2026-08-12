package applygray.mattermanipulator.state;

import java.util.Objects;

import applygray.mattermanipulator.building.BlockSpec;
import applygray.mattermanipulator.planning.VoxelRole;

/** Applies one server-validated middle-click material sample to the selected configuration target. */
public final class ManipulatorMaterialPicker {

    private ManipulatorMaterialPicker() {}

    public static Result apply(ManipulatorState state, BlockSpec specification) {
        return apply(state, specification, false);
    }

    public static Result apply(ManipulatorState state, BlockSpec specification, boolean add) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(specification, "specification");
        if (specification.isAir()) throw new IllegalArgumentException("Cannot pick air as a material");

        return switch (state.pickTarget()) {
            case ALL -> applyAllGeometry(state, specification, add);
            case CORNER -> applyGeometry(state, VoxelRole.CORNER, specification, add);
            case EDGE -> applyGeometry(state, VoxelRole.EDGE, specification, add);
            case FACE -> applyGeometry(state, VoxelRole.FACE, specification, add);
            case VOLUME -> applyGeometry(state, VoxelRole.VOLUME, specification, add);
            case EXCHANGE_WHITELIST_SET -> applyList(state.exchangeWhitelist(), specification, false);
            case EXCHANGE_WHITELIST_ADD -> applyList(state.exchangeWhitelist(), specification, true);
            case EXCHANGE_REPLACEMENT -> applyList(state.exchangeReplacement(), specification, false);
            case CABLE -> {
                state.setCableMaterial(specification);
                yield Result.SET;
            }
        };
    }

    private static Result applyAllGeometry(ManipulatorState state, BlockSpec specification, boolean add) {
        if (!add) {
            state.geometryConfiguration().setAll(specification);
            return Result.SET;
        }
        for (VoxelRole role : VoxelRole.values()) {
            applyGeometry(state, role, specification, true);
        }
        return Result.ADDED;
    }

    private static Result applyGeometry(ManipulatorState state, VoxelRole role, BlockSpec specification, boolean add) {
        if (add) {
            listFor(state, role).add(specification);
        } else {
            state.geometryConfiguration().setSingle(role, specification);
        }
        return add ? Result.ADDED : Result.SET;
    }

    private static Result applyList(applygray.mattermanipulator.building.WeightedBlockList list,
                                    BlockSpec specification, boolean add) {
        if (add) {
            list.add(specification);
        } else {
            list.setSingle(specification);
        }
        return add ? Result.ADDED : Result.SET;
    }

    private static applygray.mattermanipulator.building.WeightedBlockList listFor(ManipulatorState state,
                                                                                  VoxelRole role) {
        return switch (role) {
            case CORNER -> state.geometryConfiguration().corners();
            case EDGE -> state.geometryConfiguration().edges();
            case FACE -> state.geometryConfiguration().faces();
            case VOLUME -> state.geometryConfiguration().volumes();
        };
    }

    public enum Result {
        SET,
        ADDED
    }
}
