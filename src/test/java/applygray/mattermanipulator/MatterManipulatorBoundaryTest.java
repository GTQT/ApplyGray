package applygray.mattermanipulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import applygray.mattermanipulator.planning.CopyArraySpan;
import applygray.mattermanipulator.state.ManipulatorLocation;
import applygray.mattermanipulator.state.ManipulatorPendingAction;
import applygray.mattermanipulator.state.ManipulatorSelectionActions;
import applygray.mattermanipulator.state.ManipulatorState;
import applygray.mattermanipulator.state.ManipulatorTransform;
import applygray.mattermanipulator.util.SourceCompatibleRandom;
import net.minecraft.nbt.NBTTagCompound;
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
