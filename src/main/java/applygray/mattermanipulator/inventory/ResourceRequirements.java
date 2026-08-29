package applygray.mattermanipulator.inventory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import applygray.mattermanipulator.building.BlockSpec;
import applygray.mattermanipulator.planning.BoundGeometryPlan;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

/** Aggregates a material-bound plan into exact inventory reservations. */
public final class ResourceRequirements {

    private final List<ResourceRequirement> entries;
    private final List<FluidRequirement> fluidEntries;

    private ResourceRequirements(List<ResourceRequirement> entries, List<FluidRequirement> fluidEntries) {
        this.entries = List.copyOf(entries);
        this.fluidEntries = List.copyOf(fluidEntries);
    }

    public static ResourceRequirements empty() {
        return new ResourceRequirements(List.of(), List.of());
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
                .toList(), List.of());
    }

    public static ResourceRequirements of(ResourceRequirement... requirements) {
        Map<BlockSpec, Long> amounts = new LinkedHashMap<>();
        for (ResourceRequirement requirement : requirements) {
            amounts.merge(requirement.specification(), requirement.amount(), Math::addExact);
        }
        return new ResourceRequirements(amounts.entrySet().stream()
                .map(entry -> new ResourceRequirement(entry.getKey(), entry.getValue()))
                .toList(), List.of());
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
                .toList(), List.of());
    }

    public static ResourceRequirements fluids(FluidRequirement... requirements) {
        Map<FluidKey, FluidRequirement> amounts = new LinkedHashMap<>();
        for (FluidRequirement requirement : requirements) {
            amounts.merge(FluidKey.of(requirement), requirement, (first, second) ->
                    new FluidRequirement(first.fluidName(), first.tag(), Math.addExact(first.amount(), second.amount())));
        }
        return new ResourceRequirements(List.of(), amounts.values().stream().toList());
    }

    public static ResourceRequirements fromFluids(Iterable<FluidStack> stacks) {
        Map<FluidKey, FluidRequirement> amounts = new LinkedHashMap<>();
        for (FluidStack stack : stacks) {
            if (stack == null || stack.amount <= 0) continue;
            FluidRequirement requirement = new FluidRequirement(stack, stack.amount);
            amounts.merge(FluidKey.of(requirement), requirement, (first, second) ->
                    new FluidRequirement(first.fluidName(), first.tag(), Math.addExact(first.amount(), second.amount())));
        }
        return new ResourceRequirements(List.of(), amounts.values().stream().toList());
    }

    public static ResourceRequirements combine(ResourceRequirements first, ResourceRequirements second) {
        Map<BlockSpec, Long> items = new LinkedHashMap<>();
        first.entries.forEach(entry -> items.merge(entry.specification(), entry.amount(), Math::addExact));
        second.entries.forEach(entry -> items.merge(entry.specification(), entry.amount(), Math::addExact));
        Map<FluidKey, FluidRequirement> fluids = new LinkedHashMap<>();
        java.util.stream.Stream.concat(first.fluidEntries.stream(), second.fluidEntries.stream()).forEach(entry ->
                fluids.merge(FluidKey.of(entry), entry, (a, b) -> new FluidRequirement(a.fluidName(), a.tag(),
                        Math.addExact(a.amount(), b.amount()))));
        return new ResourceRequirements(items.entrySet().stream().map(e -> new ResourceRequirement(e.getKey(), e.getValue())).toList(),
                fluids.values().stream().toList());
    }

    public List<ResourceRequirement> entries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty() && fluidEntries.isEmpty();
    }

    public List<FluidRequirement> fluidEntries() {
        return fluidEntries;
    }

    private record FluidKey(String name, net.minecraft.nbt.NBTTagCompound tag) {
        private static FluidKey of(FluidRequirement requirement) {
            return new FluidKey(requirement.fluidName(), requirement.tag());
        }
    }
}
