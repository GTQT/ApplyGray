package applygray.mattermanipulator.state;

import java.util.Objects;

import net.minecraft.util.math.BlockPos;

/** Target-only state transitions shared by the cut, copy, paste, and reset input actions. */
public final class ManipulatorSelectionActions {

    private ManipulatorSelectionActions() {}

    public static void beginCopy(ManipulatorState state) {
        beginSelection(state, ManipulatorPlaceMode.COPYING);
    }

    public static void beginMove(ManipulatorState state) {
        beginSelection(state, ManipulatorPlaceMode.MOVING);
    }

    public static void preparePaste(ManipulatorState state) {
        Objects.requireNonNull(state, "state");
        if (state.placeMode() != ManipulatorPlaceMode.COPYING && state.placeMode() != ManipulatorPlaceMode.MOVING) {
            state.setPlaceMode(ManipulatorPlaceMode.COPYING);
        }
        state.setSelectionC(null);
    }

    public static void reset(ManipulatorState state) {
        Objects.requireNonNull(state, "state");
        state.clearSelections();
        resetTransform(state);
    }

    public static void resetTransform(ManipulatorState state) {
        Objects.requireNonNull(state, "state");
        state.setCopyTransform(ManipulatorTransform.identity());
        state.setCopyRepeats(1, 1, 1);
    }

    public static void shiftSourceRegion(ManipulatorState state, BlockPos offset) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(offset, "offset");
        if (state.selectionA() != null) state.setSelectionA(offset(state.selectionA(), offset));
        if (state.selectionB() != null) state.setSelectionB(offset(state.selectionB(), offset));
    }

    private static ManipulatorLocation offset(ManipulatorLocation location, BlockPos offset) {
        return new ManipulatorLocation(location.dimension(), location.position().add(offset));
    }

    private static void beginSelection(ManipulatorState state, ManipulatorPlaceMode mode) {
        Objects.requireNonNull(state, "state");
        state.setPlaceMode(mode);
        state.clearSelections();
    }
}
