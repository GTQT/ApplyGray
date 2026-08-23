package applygray.mattermanipulator.util;

import java.util.Random;

/** Minimal source-compatible port of StructureLib/GT5U XSTR for deterministic plan snapshots. */
public final class SourceCompatibleRandom extends Random {

    private long state;

    public SourceCompatibleRandom(long seed) {
        this.state = seed;
    }

    @Override
    protected int next(int bits) {
        long value = state;
        value ^= value << 21;
        value ^= value >>> 35;
        value ^= value << 4;
        state = value;
        return (int) (value & ((1L << bits) - 1L));
    }

    @Override
    public int nextInt(int bound) {
        if (bound <= 0) throw new IllegalArgumentException("bound must be positive");
        long value = state;
        value ^= value << 21;
        value ^= value >>> 35;
        value ^= value << 4;
        state = value;
        int result = (int) (value % bound);
        return result < 0 ? -result : result;
    }
}
