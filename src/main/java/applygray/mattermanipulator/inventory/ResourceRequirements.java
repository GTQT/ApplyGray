package applygray.mattermanipulator.inventory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import applygray.mattermanipulator.building.BlockSpec;
import applygray.mattermanipulator.planning.BoundGeometryPlan;

import net.minecraft.item.ItemStack;

/** Aggregates a material-bound plan into exact inventory reservations. */
public final class ResourceRequirements {

    private final List<ResourceRequirement> entries;

    private ResourceRequirements(List<ResourceRequirement> entries) {
        this.entries = List.copyOf(entries);
    }

    public static ResourceRequirements empty() {
        return new ResourceRequirements(List.of());
    }

    public static ResourceRequirements from(BoundGeometryPlan plan) {
        Map<BlockSpec, Long> amounts = new LinkedHashMap<>();
        plan.operations().forEach(operation -> {
            BlockSpec specification = operation.block();
            if (specification.isAir()) return;
            amounts.merge(specification, 1L, Math::addExact);
        });
        return new ResourceRequirements(amounts.entrySet().stream()
                .map(entry -> new ResourceRequirement(entry.getKey(), entry.getValue()))
                .toList());
    }

    public static ResourceRequirements of(ResourceRequirement... requirements) {
        Map<BlockSpec, Long> amounts = new LinkedHashMap<>();
        for (ResourceRequirement requirement : requirements) {
            amounts.merge(requirement.specification(), requirement.amount(), Math::addExact);
        }
        return new ResourceRequirements(amounts.entrySet().stream()
                .map(entry -> new ResourceRequirement(entry.getKey(), entry.getValue()))
                .toList());
    }

    /** Aggregates ordinary item outputs while retaining exact metadata and NBT. */
    public static ResourceRequirements fromStacks(Iterable<ItemStack> stacks) {
        Map<BlockSpec, Long> amounts = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) continue;
            amounts.merge(BlockSpec.of(stack), (long) stack.getCount(), Math::addExact);
        }
        return new ResourceRequirements(amounts.entrySet().stream()
                .map(entry -> new ResourceRequirement(entry.getKey(), entry.getValue()))
                .toList());
    }

    public List<ResourceRequirement> entries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
