package applygray.mattermanipulator.state;

/** Server-authoritative interaction state matching GT5U pending actions. */
public enum ManipulatorPendingAction {
    NONE, MOVING_COORDS, MARK_COPY_A, MARK_COPY_B, MARK_CUT_A, MARK_CUT_B, MARK_PASTE,
    GEOM_SELECTING_BLOCK, EXCH_SET_TARGET, EXCH_SET_REPLACE, EXCH_ADD_REPLACE, PICK_CABLE
}
