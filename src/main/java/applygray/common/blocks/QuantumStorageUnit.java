package applygray.common.blocks;

import applygray.api.quantum.QuantumStorageStats;

import gregtech.api.block.IStateHarvestLevel;
import gregtech.api.items.toolitem.ToolClasses;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.IStringSerializable;

import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;

/**
 * The nine tiers of the quantum storage unit casing block.
 * <p>
 * Stats are <em>per single block</em>: every matched block inside a formed
 * storage multiblock adds its {@link #distinctSlots()} and {@link #totalCapacity()}
 * to the controller's store.
 * <p>
 * Tier names follow the LiteCore original: T1 mirrors a LuV look through
 * T9 (MAX). Values per tier: distinct slots scale {@code 256 * 4^n} and total
 * capacity {@code 10^(9 + 3n)}.
 */
public enum QuantumStorageUnit implements QuantumStorageStats, IStringSerializable, IStateHarvestLevel {

    T1("t1"),
    T2("t2"),
    T3("t3"),
    T4("t4"),
    T5("t5"),
    T6("t6"),
    T7("t7"),
    T8("t8"),
    T9("t9");

    private static final int BASE_DISTINCT_SLOTS = 256;
    private static final int BASE_CAPACITY_EXPONENT = 9;

    private final String name;

    QuantumStorageUnit(String name) {
        this.name = name;
    }

    @Override
    public int distinctSlots() {
        return BASE_DISTINCT_SLOTS << (2 * ordinal());
    }

    @Override
    public BigInteger totalCapacity() {
        return BigInteger.TEN.pow(BASE_CAPACITY_EXPONENT + 3 * ordinal());
    }

    @Override
    @NotNull
    public String getName() {
        return name;
    }

    @Override
    public int getHarvestLevel(IBlockState state) {
        return 3 + ordinal() / 3;
    }

    @Override
    public String getHarvestTool(IBlockState state) {
        return ToolClasses.WRENCH;
    }
}
