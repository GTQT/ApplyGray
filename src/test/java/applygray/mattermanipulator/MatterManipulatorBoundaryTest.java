package applygray.mattermanipulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import applygray.mattermanipulator.planning.CopyArraySpan;
import applygray.mattermanipulator.planning.CablePathPlanner;
import applygray.mattermanipulator.state.ManipulatorLocation;
import applygray.mattermanipulator.state.ManipulatorPendingAction;
import applygray.mattermanipulator.state.ManipulatorPlaceMode;
import applygray.mattermanipulator.state.ManipulatorSelectionActions;
import applygray.mattermanipulator.state.ManipulatorSelectionActions.SelectionOutcome;
import applygray.mattermanipulator.state.ManipulatorSelectionActions.SelectionSlot;
import applygray.mattermanipulator.state.ManipulatorSelectionDimensions;
import applygray.mattermanipulator.state.ManipulatorShape;
import applygray.mattermanipulator.state.ManipulatorState;
import applygray.mattermanipulator.state.ManipulatorTransform;
import applygray.mattermanipulator.util.ManipulatorTargeting;
import applygray.mattermanipulator.util.SourceCompatibleRandom;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

class MatterManipulatorBoundaryTest {

    @Test
    void markKeepsSignedArraySpans() {
        ManipulatorLocation sourceA = location(0, 0, 0, 0);
        ManipulatorLocation sourceB = location(0, 0, 2, 0);
        ManipulatorLocation destination = location(0, 0, 10, 0);

        BlockPos span = CopyArraySpan.calculate(sourceA, sourceB, destination, new BlockPos(0, 5, 0),
                ManipulatorTransform.identity());

        assertEquals(new BlockPos(1, -2, 1), span);
    }

    @Test
    void unknownStateSchemaIsDiscardedInsteadOfInterpreted() {
        NBTTagCompound data = new ManipulatorState().writeToNbt();
        data.setInteger("Schema", ManipulatorState.SCHEMA_VERSION + 1);

        assertEquals(new ManipulatorState(), ManipulatorState.readFromNbt(data));
    }

    @Test
    void beginningCoordinateSelectionReplacesThePreviousRegion() {
        ManipulatorState state = new ManipulatorState();
        state.setSelectionA(location(0, 1, 2, 3));
        state.setSelectionB(location(0, 4, 5, 6));
        state.setSelectionC(location(0, 7, 8, 9));
        ManipulatorLocation nextA = location(0, 10, 11, 12);

        ManipulatorSelectionActions.beginCoordinates(state, nextA);

        assertEquals(nextA, state.selectionA());
        assertEquals(null, state.selectionB());
        assertEquals(null, state.selectionC());
        assertEquals(ManipulatorPendingAction.MOVING_COORDS, state.pendingAction());
    }

    @Test
    void onlyCoordinateActionsCaptureAnAirRightClick() {
        assertTrue(ManipulatorPendingAction.MOVING_COORDS.selectsCoordinates());
        assertTrue(ManipulatorPendingAction.MARK_COPY_B.selectsCoordinates());
        assertTrue(ManipulatorPendingAction.MARK_ARRAY.selectsCoordinates());
        assertFalse(ManipulatorPendingAction.NONE.selectsCoordinates());
        assertFalse(ManipulatorPendingAction.PICK_CABLE.selectsCoordinates());
    }

    @Test
    void sneakingSelectsTheHitBlockInsteadOfTheAdjacentFace() {
        BlockPos hit = new BlockPos(3, 4, 5);

        assertEquals(hit, ManipulatorTargeting.blockTarget(hit, EnumFacing.UP, true));
        assertEquals(new BlockPos(3, 5, 5), ManipulatorTargeting.blockTarget(hit, EnumFacing.UP, false));
    }

    @Test
    void directCopySelectionUsesTheSharedMovingCoordinateStateMachine() {
        ManipulatorState state = new ManipulatorState();
        state.setPlaceMode(ManipulatorPlaceMode.COPYING);

        ManipulatorSelectionActions.SelectionResult first = ManipulatorSelectionActions.confirmCoordinates(state,
                location(0, 1, 2, 3));
        assertEquals(SelectionSlot.A, first.slot());
        assertEquals(ManipulatorPendingAction.MOVING_COORDS, state.pendingAction());

        ManipulatorState preview = ManipulatorState.readFromNbt(state.writeToNbt());
        ManipulatorSelectionActions.projectCoordinates(preview, location(0, 4, 6, 8));
        assertEquals(location(0, 4, 6, 8), preview.selectionB());
        assertEquals(null, state.selectionB());

        ManipulatorSelectionActions.SelectionResult second = ManipulatorSelectionActions.confirmCoordinates(state,
                location(0, 4, 6, 8));
        assertEquals(SelectionSlot.B, second.slot());
        assertEquals(ManipulatorPendingAction.MOVING_COORDS, state.pendingAction());

        ManipulatorSelectionActions.SelectionResult third = ManipulatorSelectionActions.confirmCoordinates(state,
                location(0, 10, 11, 12));
        assertEquals(SelectionSlot.C, third.slot());
        assertEquals(ManipulatorPendingAction.NONE, state.pendingAction());
    }

    @Test
    void everyModeProjectsBFromTheSharedCoordinateState() {
        for (ManipulatorPlaceMode mode : ManipulatorPlaceMode.values()) {
            ManipulatorState state = new ManipulatorState();
            state.setPlaceMode(mode);
            ManipulatorSelectionActions.confirmCoordinates(state, location(0, 1, 2, 3));

            ManipulatorState preview = ManipulatorState.readFromNbt(state.writeToNbt());
            ManipulatorSelectionActions.projectCoordinates(preview, location(0, 7, 8, 9));

            assertEquals(location(0, 7, 8, 9), preview.selectionB(), mode.name());
            assertEquals(null, state.selectionB(), mode.name());
        }
    }

    @Test
    void menuCopySelectionStopsAfterTheSourceRegion() {
        ManipulatorState state = new ManipulatorState();
        ManipulatorSelectionActions.beginCopy(state);

        assertEquals(SelectionSlot.A, ManipulatorSelectionActions.confirmCoordinates(state,
                location(0, 0, 0, 0)).slot());
        assertEquals(ManipulatorPendingAction.MARK_COPY_B, state.pendingAction());
        assertEquals(SelectionSlot.B, ManipulatorSelectionActions.confirmCoordinates(state,
                location(0, 2, 2, 2)).slot());
        assertEquals(ManipulatorPendingAction.NONE, state.pendingAction());
    }

    @Test
    void selectionDimensionsCoverPinnedAndRepeatedModes() {
        ManipulatorState cable = new ManipulatorState();
        cable.setPlaceMode(ManipulatorPlaceMode.CABLES);
        cable.setSelectionA(location(0, 0, 0, 0));
        cable.setSelectionB(location(0, 5, 2, 1));
        assertEquals(new ManipulatorSelectionDimensions(6, 3, 2),
                ManipulatorSelectionDimensions.from(cable, 0));

        ManipulatorState cylinder = new ManipulatorState();
        cylinder.setShape(ManipulatorShape.CYLINDER);
        cylinder.setSelectionA(location(0, 0, 0, 0));
        cylinder.setSelectionB(location(0, 2, 3, 1));
        cylinder.setSelectionC(location(0, 4, 5, 6));
        assertEquals(new ManipulatorSelectionDimensions(3, 4, 7),
                ManipulatorSelectionDimensions.from(cylinder, 0));

        ManipulatorState copy = new ManipulatorState();
        copy.setPlaceMode(ManipulatorPlaceMode.COPYING);
        copy.setSelectionA(location(0, 0, 0, 0));
        copy.setSelectionB(location(0, 1, 2, 3));
        copy.setSelectionC(location(0, 10, 10, 10));
        copy.setCopyRepeats(2, -3, 1);
        ManipulatorSelectionDimensions repeated = ManipulatorSelectionDimensions.from(copy, 0);
        assertEquals(new ManipulatorSelectionDimensions(4, 9, 4), repeated);
        assertEquals("144", repeated.volume().toString());

        ManipulatorState move = ManipulatorState.readFromNbt(copy.writeToNbt());
        move.setPlaceMode(ManipulatorPlaceMode.MOVING);
        assertEquals(new ManipulatorSelectionDimensions(2, 3, 4),
                ManipulatorSelectionDimensions.from(move, 0));
    }

    @Test
    void cablePathReachesOffAxisEndpointThroughDeterministicTurns() {
        ManipulatorLocation start = location(0, 0, 0, 0);
        ManipulatorLocation end = location(0, 2, -4, 1);

        var plan = CablePathPlanner.plan(start, end);

        assertEquals(8, plan.operationCount());
        assertEquals(new BlockPos(0, 0, 0), plan.operations().getFirst().location().position());
        assertEquals(new BlockPos(2, -4, 1), plan.operations().getLast().location().position());
        assertEquals(java.util.List.of(
                new BlockPos(0, 0, 0),
                new BlockPos(0, -4, 0),
                new BlockPos(2, -4, 0),
                new BlockPos(2, -4, 1)), CablePathPlanner.waypoints(start.position(), end.position()));
        for (int index = 1; index < plan.operationCount(); index++) {
            BlockPos previous = plan.operations().get(index - 1).location().position();
            BlockPos current = plan.operations().get(index).location().position();
            assertEquals(1, Math.abs(current.getX() - previous.getX()) +
                    Math.abs(current.getY() - previous.getY()) + Math.abs(current.getZ() - previous.getZ()));
        }
    }

    @Test
    void incompleteArrayIsRejectedByTheSharedStateMachine() {
        ManipulatorState state = new ManipulatorState();
        state.setPendingAction(ManipulatorPendingAction.MARK_ARRAY);

        ManipulatorSelectionActions.SelectionResult result = ManipulatorSelectionActions.confirmCoordinates(state,
                location(0, 1, 1, 1));

        assertEquals(SelectionOutcome.ARRAY_INCOMPLETE, result.outcome());
        assertEquals(ManipulatorPendingAction.NONE, state.pendingAction());
    }

    @Test
    void sourceRandomIsStableAndDoesNotUseJavaRandomSequence() {
        int[] first = IntStream.range(0, 8).map(index -> new SourceCompatibleRandom(1234L + index).nextInt(97))
                .toArray();
        int[] second = IntStream.range(0, 8).map(index -> new SourceCompatibleRandom(1234L + index).nextInt(97))
                .toArray();

        assertEquals(java.util.Arrays.toString(first), java.util.Arrays.toString(second));
        assertNotEquals(new java.util.Random(1234L).nextInt(97), first[0]);
    }

    private static ManipulatorLocation location(int dimension, int x, int y, int z) {
        return new ManipulatorLocation(dimension, new BlockPos(x, y, z));
    }
}
