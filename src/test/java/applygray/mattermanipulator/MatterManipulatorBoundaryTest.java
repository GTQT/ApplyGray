package applygray.mattermanipulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import applygray.mattermanipulator.planning.CopyArraySpan;
import applygray.mattermanipulator.state.ManipulatorLocation;
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
