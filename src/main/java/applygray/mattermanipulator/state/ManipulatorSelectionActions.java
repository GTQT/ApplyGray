package applygray.mattermanipulator.state;

import java.util.Objects;

import applygray.mattermanipulator.config.MatterManipulatorConfig;
import applygray.mattermanipulator.planning.CopyArraySpan;

import net.minecraft.util.math.BlockPos;

/** Target-only state transitions shared by the cut, copy, paste, and reset input actions. */
public final class ManipulatorSelectionActions {

    private ManipulatorSelectionActions() {}

    public static void beginCopy(ManipulatorState state) {
        beginSelection(state, ManipulatorPlaceMode.COPYING);
        state.setPendingAction(ManipulatorPendingAction.MARK_COPY_A);
    }

    public static void beginMove(ManipulatorState state) {
        beginSelection(state, ManipulatorPlaceMode.MOVING);
        state.setPendingAction(ManipulatorPendingAction.MARK_CUT_A);
    }

    public static void preparePaste(ManipulatorState state) {
        Objects.requireNonNull(state, "state");
        if (state.placeMode() != ManipulatorPlaceMode.COPYING && state.placeMode() != ManipulatorPlaceMode.MOVING) {
            state.setPlaceMode(ManipulatorPlaceMode.COPYING);
        }
        if (MatterManipulatorConfig.autoClearPaste) state.setSelectionC(null);
        state.setPendingAction(ManipulatorPendingAction.MARK_PASTE);
    }

    /** Starts the A/B(/C) selection flow used directly by geometry, exchange, and cable modes. */
    public static void beginCoordinates(ManipulatorState state, ManipulatorLocation location) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(location, "location");
        state.clearSelections();
        state.setSelectionA(location);
        state.setPendingAction(ManipulatorPendingAction.MOVING_COORDS);
    }

    /**
     * Projects the coordinate currently under the crosshair without advancing the persistent selection state.
     * Client previews and server confirmation deliberately share the same pending-action rules.
     */
    public static void projectCoordinates(ManipulatorState state, ManipulatorLocation location) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(location, "location");
        switch (state.pendingAction()) {
            case MARK_COPY_A, MARK_CUT_A -> state.setSelectionA(location);
            case MARK_COPY_B, MARK_CUT_B -> state.setSelectionB(location);
            case MARK_PASTE -> state.setSelectionC(location);
            case MARK_ARRAY -> {
                if (hasCompleteCopySelection(state)) {
                    BlockPos span = CopyArraySpan.calculate(state.selectionA(), state.selectionB(), state.selectionC(),
                            location.position(), state.copyTransform());
                    state.setCopyRepeats(span.getX(), span.getY(), span.getZ());
                }
            }
            case MOVING_COORDS -> setNextCoordinate(state, location);
            default -> {
                // Material-picking and idle actions do not project coordinates.
            }
        }
    }

    /** Confirms one coordinate and advances the same state machine used by {@link #projectCoordinates}. */
    public static SelectionResult confirmCoordinates(ManipulatorState state, ManipulatorLocation location) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(location, "location");

        if (state.pendingAction() == ManipulatorPendingAction.NONE) {
            beginCoordinates(state, location);
            return SelectionResult.marked(SelectionSlot.A);
        }

        return switch (state.pendingAction()) {
            case MARK_COPY_A -> markAndAdvance(state, location, SelectionSlot.A,
                    ManipulatorPendingAction.MARK_COPY_B);
            case MARK_COPY_B -> markAndFinish(state, location, SelectionSlot.B);
            case MARK_CUT_A -> markAndAdvance(state, location, SelectionSlot.A,
                    ManipulatorPendingAction.MARK_CUT_B);
            case MARK_CUT_B -> markAndFinish(state, location, SelectionSlot.B);
            case MARK_PASTE -> markAndFinish(state, location, SelectionSlot.C);
            case MARK_ARRAY -> confirmArray(state, location);
            case MOVING_COORDS -> confirmMovingCoordinate(state, location);
            default -> SelectionResult.rejected();
        };
    }

    public static void reset(ManipulatorState state) {
        Objects.requireNonNull(state, "state");
        state.clearSelections();
        state.setPendingAction(ManipulatorPendingAction.NONE);
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

    private static SelectionResult confirmMovingCoordinate(ManipulatorState state, ManipulatorLocation location) {
        SelectionSlot slot = setNextCoordinate(state, location);
        if (slot == null) {
            state.setPendingAction(ManipulatorPendingAction.NONE);
            return SelectionResult.rejected();
        }
        if (state.selectionB() != null && (!requiresThirdCoordinate(state) || state.selectionC() != null)) {
            state.setPendingAction(ManipulatorPendingAction.NONE);
        }
        return SelectionResult.marked(slot);
    }

    private static SelectionSlot setNextCoordinate(ManipulatorState state, ManipulatorLocation location) {
        if (state.selectionA() == null) {
            state.setSelectionA(location);
            return SelectionSlot.A;
        }
        if (state.selectionB() == null) {
            state.setSelectionB(location);
            return SelectionSlot.B;
        }
        if (requiresThirdCoordinate(state) && state.selectionC() == null) {
            state.setSelectionC(location);
            return SelectionSlot.C;
        }
        return null;
    }

    private static SelectionResult markAndAdvance(ManipulatorState state, ManipulatorLocation location,
                                                    SelectionSlot slot, ManipulatorPendingAction nextAction) {
        setCoordinate(state, slot, location);
        state.setPendingAction(nextAction);
        return SelectionResult.marked(slot);
    }

    private static SelectionResult markAndFinish(ManipulatorState state, ManipulatorLocation location,
                                                   SelectionSlot slot) {
        setCoordinate(state, slot, location);
        state.setPendingAction(ManipulatorPendingAction.NONE);
        return SelectionResult.marked(slot);
    }

    private static SelectionResult confirmArray(ManipulatorState state, ManipulatorLocation location) {
        if (!hasCompleteCopySelection(state)) {
            state.setPendingAction(ManipulatorPendingAction.NONE);
            return SelectionResult.incompleteArray();
        }
        BlockPos span = CopyArraySpan.calculate(state.selectionA(), state.selectionB(), state.selectionC(),
                location.position(), state.copyTransform());
        state.setCopyRepeats(span.getX(), span.getY(), span.getZ());
        state.setPendingAction(ManipulatorPendingAction.NONE);
        return SelectionResult.array(span);
    }

    private static void setCoordinate(ManipulatorState state, SelectionSlot slot, ManipulatorLocation location) {
        switch (slot) {
            case A -> state.setSelectionA(location);
            case B -> state.setSelectionB(location);
            case C -> state.setSelectionC(location);
        }
    }

    private static boolean requiresThirdCoordinate(ManipulatorState state) {
        return switch (state.placeMode()) {
            case COPYING, MOVING -> true;
            case GEOMETRY -> state.shape().requiresThirdPoint();
            case EXCHANGING, CABLES -> false;
        };
    }

    private static boolean hasCompleteCopySelection(ManipulatorState state) {
        return state.selectionA() != null && state.selectionB() != null && state.selectionC() != null;
    }

    private static void beginSelection(ManipulatorState state, ManipulatorPlaceMode mode) {
        Objects.requireNonNull(state, "state");
        state.setPlaceMode(mode);
        state.clearSelections();
        if (MatterManipulatorConfig.clearTransformWithSelections) resetTransform(state);
    }

    public enum SelectionSlot {
        A,
        B,
        C
    }

    public enum SelectionOutcome {
        MARKED,
        ARRAY_MARKED,
        ARRAY_INCOMPLETE,
        REJECTED
    }

    public record SelectionResult(SelectionOutcome outcome, SelectionSlot slot, BlockPos arraySpan) {

        private static SelectionResult marked(SelectionSlot slot) {
            return new SelectionResult(SelectionOutcome.MARKED, slot, null);
        }

        private static SelectionResult array(BlockPos span) {
            return new SelectionResult(SelectionOutcome.ARRAY_MARKED, null, span);
        }

        private static SelectionResult incompleteArray() {
            return new SelectionResult(SelectionOutcome.ARRAY_INCOMPLETE, null, null);
        }

        private static SelectionResult rejected() {
            return new SelectionResult(SelectionOutcome.REJECTED, null, null);
        }
    }
}
