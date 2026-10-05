package applygray.common.quantum;

import applygray.common.ApplyGrayBlocks;
import applygray.common.blocks.BlockQuantumStorageUnit;
import applygray.common.blocks.QuantumStorageUnit;

import gregtech.api.pattern.FormedStructureView;
import gregtech.api.pattern.StructureContributionKey;
import gregtech.api.pattern.StructureEvaluationContext;
import gregtech.api.pattern.element.ITypedStructureElement;
import gregtech.api.pattern.element.IStructureElement;
import gregtech.api.pattern.element.StructureElementCapability;
import gregtech.api.util.BlockInfo;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;

import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;
import java.util.Set;

/**
 * Match-time accounting for the quantum storage units of a formed storage array.
 * <p>
 * The unit core of both arrays is a repeatable piece whose layer count is chosen by the player, so the totals cannot be
 * recovered from a fixed geometry. Like GT Lite's traceability predicate, every matched unit contributes its tier
 * totals while the pattern is matched, and the controller reads the sum back from the formed structure view.
 * <p>
 * Contributing through {@link StructureContributionKey} keeps the element's incremental contract honest: it declares
 * {@link gregtech.api.pattern.StructureIncrementalSupport#TYPED_CONTRIBUTION}, so the incremental evaluator knows all
 * cross-piece effects are reported as typed contributions.
 */
public final class QuantumStorageElement {

    private static final BlockInfo[] CANDIDATES = createCandidates();

    /** Immutable per-structure totals contributed by every matched {@link QuantumStorageUnit}. */
    public record Totals(BigInteger totalCapacity, long distinctSlots, int unitBlocks) {

        public static final Totals EMPTY = new Totals(BigInteger.ZERO, 0L, 0);

        Totals plus(QuantumStorageUnit unit) {
            return new Totals(totalCapacity.add(unit.totalCapacity()),
                    distinctSlots + unit.distinctSlots(),
                    unitBlocks + 1);
        }
    }

    public static final StructureContributionKey<QuantumStorageUnit, Totals> UNIT_TOTALS =
            StructureContributionKey.create("applygray:quantum_storage_units",
                    () -> Totals.EMPTY,
                    (totals, unit) -> totals.plus(unit));

    private QuantumStorageElement() {
    }

    /**
     * Air or any quantum storage unit. Units are counted when matched, so the same element serves both the item and the
     * fluid array regardless of how many core layers the player built.
     */
    public static IStructureElement<Object> airOrStorageUnit() {
        return new StorageUnitElement();
    }

    /** Reads the totals contributed while {@code formed} was matched. */
    public static Totals read(FormedStructureView formed) {
        Totals totals = formed.getAggregate(UNIT_TOTALS);
        return totals == null ? Totals.EMPTY : totals;
    }

    private static BlockInfo[] createCandidates() {
        QuantumStorageUnit[] units = QuantumStorageUnit.values();
        BlockInfo[] infos = new BlockInfo[units.length + 1];
        for (int i = 0; i < units.length; i++) {
            infos[i] = new BlockInfo(ApplyGrayBlocks.QUANTUM_STORAGE_UNIT.getState(units[i]));
        }
        // Empty core cells are legal, so air is the last candidate offered to previews and auto-building.
        infos[units.length] = new BlockInfo(Blocks.AIR.getDefaultState());
        return infos;
    }

    private static final class StorageUnitElement implements ITypedStructureElement<Object> {

        @Override
        public boolean check(@NotNull StructureEvaluationContext<Object> context) {
            IBlockState state = context.getBlockState();
            Block block = state.getBlock();
            if (block.isAir(state, context.getBlockAccess(), context.getPos())) {
                return true;
            }
            if (!(block instanceof BlockQuantumStorageUnit unitBlock)) {
                return false;
            }
            context.getCollector().emit(UNIT_TOTALS, unitBlock.getState(state));
            return true;
        }

        @NotNull
        @Override
        public Set<StructureElementCapability> getCapabilities() {
            return StructureElementCapability.snapshotSafe();
        }

        @NotNull
        @Override
        public BlockInfo[] getCandidates() {
            return CANDIDATES;
        }
    }
}
