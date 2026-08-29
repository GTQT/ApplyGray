package applygray.mattermanipulator.integration.ae2;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import applygray.mattermanipulator.building.BlockSpec;
import applygray.mattermanipulator.building.CapturedBlockData;
import applygray.mattermanipulator.inventory.FluidRequirement;
import applygray.mattermanipulator.inventory.ResourceRequirement;
import applygray.mattermanipulator.inventory.ResourceRequirements;
import applygray.mattermanipulator.state.ManipulatorTransform;

import ae2.block.crafting.PushDirection;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fluids.FluidStack;

/** Explicit AE2 block-entity state; no raw tile NBT is used as a portable payload. */
public final class Ae2TileCaptureData implements CapturedBlockData {

    private final ItemStack placementStack;
    private final List<ItemStack> blockDrops;
    private final List<ItemStack> additionalDrops;
    private final NBTTagCompound settings;
    private final EnumFacing forward;
    private final EnumFacing up;
    private final List<Ae2BusCaptureData.InventoryStack> inventory;
    private final List<Ae2BusCaptureData.InventoryStack> viewCells;
    private final List<Ae2BusCaptureData.InventoryStack> upgrades;
    private final List<Ae2BusCaptureData.InventoryStack> patterns;
    private final List<ResourceRequirement> configuredItems;
    private final List<FluidRequirement> configuredFluids;
    private final List<GenericInventoryStack> genericInventory;
    private final List<FluidStack> storedFluids;
    private final double storedEnergy;
    private final boolean patternProvider;

    public Ae2TileCaptureData(ItemStack placementStack, List<ItemStack> blockDrops, List<ItemStack> additionalDrops,
                              NBTTagCompound settings, EnumFacing forward, EnumFacing up,
                              List<Ae2BusCaptureData.InventoryStack> inventory,
                              List<Ae2BusCaptureData.InventoryStack> viewCells,
                              List<Ae2BusCaptureData.InventoryStack> upgrades,
                              List<Ae2BusCaptureData.InventoryStack> patterns,
                              List<ResourceRequirement> configuredItems,
                              List<FluidRequirement> configuredFluids,
                              List<GenericInventoryStack> genericInventory,
                              List<FluidStack> storedFluids, double storedEnergy, boolean patternProvider) {
        this.placementStack = checkedStack(placementStack);
        this.blockDrops = copyStacks(blockDrops);
        this.additionalDrops = copyStacks(additionalDrops);
        this.settings = Objects.requireNonNull(settings, "settings").copy();
        this.forward = forward;
        this.up = up;
        this.inventory = copyInventory(inventory);
        this.viewCells = copyInventory(viewCells);
        this.upgrades = copyInventory(upgrades);
        this.patterns = copyInventory(patterns);
        this.configuredItems = List.copyOf(configuredItems);
        this.configuredFluids = List.copyOf(configuredFluids);
        this.genericInventory = copyGenericInventory(genericInventory);
        this.storedFluids = copyFluids(storedFluids);
        if (!Double.isFinite(storedEnergy) || storedEnergy < 0.0D) {
            throw new IllegalArgumentException("AE2 stored energy must be finite and non-negative");
        }
        this.storedEnergy = storedEnergy;
        this.patternProvider = patternProvider;
        if ((forward == null) != (up == null)) {
            throw new IllegalArgumentException("AE2 tile orientation must contain both forward and up");
        }
    }

    public static Ae2TileCaptureData forMaterial(ItemStack material) {
        ItemStack stack = checkedStack(material);
        return new Ae2TileCaptureData(stack, List.of(stack), List.of(), new NBTTagCompound(), null, null, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0.0D, false);
    }

    public BlockSpec primaryMaterial() {
        return bare(placementStack);
    }

    public ResourceRequirements requiredResources() {
        List<ResourceRequirement> itemRequirements = new ArrayList<>();
        itemRequirements.add(new ResourceRequirement(primaryMaterial(), 1L));
        addInventoryRequirements(itemRequirements, inventory);
        addInventoryRequirements(itemRequirements, viewCells);
        addInventoryRequirements(itemRequirements, upgrades);
        addInventoryRequirements(itemRequirements, patterns);
        for (GenericInventoryStack stack : genericInventory) {
            if (stack.item() != null) itemRequirements.add(new ResourceRequirement(BlockSpec.of(stack.item()), stack.amount()));
        }
        itemRequirements.addAll(configuredItems);
        ResourceRequirements items = ResourceRequirements.of(itemRequirements.toArray(ResourceRequirement[]::new));
        List<FluidRequirement> fluidRequirements = new ArrayList<>(configuredFluids);
        for (GenericInventoryStack stack : genericInventory) {
            if (stack.fluid() != null) fluidRequirements.add(new FluidRequirement(stack.fluid(), stack.amount()));
        }
        for (FluidStack stack : storedFluids) fluidRequirements.add(new FluidRequirement(stack, stack.amount));
        ResourceRequirements fluids = ResourceRequirements.fluids(fluidRequirements.toArray(FluidRequirement[]::new));
        return ResourceRequirements.combine(items, fluids);
    }

    public ResourceRequirements producedResources() {
        List<ItemStack> outputs = new ArrayList<>(
                blockDrops.size() + inventory.size() + viewCells.size() + upgrades.size() + patterns.size());
        outputs.addAll(copyStacks(blockDrops));
        outputs.addAll(stacksFrom(inventory));
        outputs.addAll(stacksFrom(viewCells));
        outputs.addAll(stacksFrom(upgrades));
        outputs.addAll(stacksFrom(patterns));
        for (GenericInventoryStack stack : genericInventory) {
            if (stack.item() != null) {
                ItemStack item = stack.item();
                long remaining = stack.amount();
                while (remaining > 0) {
                    ItemStack output = item.copy();
                    output.setCount((int) Math.min(remaining, output.getMaxStackSize()));
                    outputs.add(output);
                    remaining -= output.getCount();
                }
            }
        }
        ResourceRequirements items = ResourceRequirements.fromStacks(outputs);
        List<FluidRequirement> fluids = new ArrayList<>();
        for (GenericInventoryStack stack : genericInventory) {
            if (stack.fluid() != null) fluids.add(new FluidRequirement(stack.fluid(), stack.amount()));
        }
        for (FluidStack stack : storedFluids) fluids.add(new FluidRequirement(stack, stack.amount));
        return ResourceRequirements.combine(items, ResourceRequirements.fluids(fluids.toArray(FluidRequirement[]::new)));
    }

    public Ae2TileCaptureData transformed(ManipulatorTransform transform) {
        Objects.requireNonNull(transform, "transform");
        NBTTagCompound transformedSettings = settings.copy();
        if (patternProvider && transformedSettings.hasKey("pushDirection", Constants.NBT.TAG_INT)) {
            int ordinal = transformedSettings.getInteger("pushDirection");
            PushDirection[] directions = PushDirection.values();
            if (ordinal >= 0 && ordinal < directions.length && directions[ordinal].getDirection() != null) {
                transformedSettings.setInteger("pushDirection",
                        PushDirection.fromDirection(transform.apply(directions[ordinal].getDirection())).ordinal());
            }
        }
        if (transformedSettings.hasKey("applygray_output_sides", Constants.NBT.TAG_INT)) {
            transformedSettings.setInteger("applygray_output_sides",
                    transform.applyFacingMask(transformedSettings.getInteger("applygray_output_sides")));
        }
        return new Ae2TileCaptureData(placementStack, blockDrops, additionalDrops, transformedSettings,
                forward == null ? null : transform.apply(forward), up == null ? null : transform.apply(up), inventory,
                viewCells, upgrades, patterns, configuredItems, configuredFluids, genericInventory, storedFluids,
                storedEnergy,
                patternProvider);
    }

    public ItemStack placementStack() {
        return placementStack.copy();
    }

    public NBTTagCompound settings() {
        return settings.copy();
    }

    public List<ItemStack> blockDrops() {
        return copyStacks(blockDrops);
    }

    public List<ItemStack> additionalDrops() {
        return copyStacks(additionalDrops);
    }

    public EnumFacing forward() {
        return forward;
    }

    public EnumFacing up() {
        return up;
    }

    public List<Ae2BusCaptureData.InventoryStack> inventory() {
        return copyInventory(inventory);
    }

    public List<Ae2BusCaptureData.InventoryStack> viewCells() {
        return copyInventory(viewCells);
    }

    public List<Ae2BusCaptureData.InventoryStack> upgrades() {
        return copyInventory(upgrades);
    }

    public List<Ae2BusCaptureData.InventoryStack> patterns() {
        return copyInventory(patterns);
    }

    public List<GenericInventoryStack> genericInventory() {
        return copyGenericInventory(genericInventory);
    }

    public List<FluidStack> storedFluids() {
        return copyFluids(storedFluids);
    }

    public double storedEnergy() {
        return storedEnergy;
    }

    public boolean patternProvider() {
        return patternProvider;
    }

    public int componentCount() {
        return 1 + inventory.size() + viewCells.size() + upgrades.size() + patterns.size() + genericInventory.size() +
                storedFluids.size() + configuredItems.size() + configuredFluids.size();
    }

    public record GenericInventoryStack(int slot, ItemStack item, FluidStack fluid, long amount) {

        public GenericInventoryStack {
            if (slot < 0) throw new IllegalArgumentException("AE2 generic inventory slot must be non-negative");
            if ((item == null) == (fluid == null)) {
                throw new IllegalArgumentException("AE2 generic inventory entry must contain exactly one resource type");
            }
            if (item != null) {
                if (item.isEmpty()) throw new IllegalArgumentException("AE2 generic item cannot be empty");
                item = item.copy();
                item.setCount(1);
            }
            if (fluid != null) {
                if (fluid.amount <= 0) throw new IllegalArgumentException("AE2 generic fluid cannot be empty");
                fluid = fluid.copy();
                fluid.amount = 1;
            }
            if (amount <= 0) throw new IllegalArgumentException("AE2 generic inventory amount must be positive");
        }

        @Override
        public ItemStack item() {
            return item == null ? null : item.copy();
        }

        @Override
        public FluidStack fluid() {
            return fluid == null ? null : fluid.copy();
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof GenericInventoryStack stack) || slot != stack.slot || amount != stack.amount) {
                return false;
            }
            boolean sameItem = item == null ? stack.item == null
                    : stack.item != null && ItemStack.areItemStacksEqual(item, stack.item);
            boolean sameFluid = fluid == null ? stack.fluid == null
                    : stack.fluid != null && fluid.isFluidEqual(stack.fluid) && Objects.equals(fluid.tag, stack.fluid.tag);
            return sameItem && sameFluid;
        }

        @Override
        public int hashCode() {
            return Objects.hash(slot, amount,
                    item == null ? null : Objects.hash(item.getItem().getRegistryName(), item.getMetadata(),
                            item.getTagCompound()),
                    fluid == null ? null : Objects.hash(fluid.getFluid().getName(), fluid.tag));
        }
    }

    private static BlockSpec bare(ItemStack stack) {
        ItemStack bare = stack.copy();
        bare.setCount(1);
        bare.setTagCompound(null);
        return BlockSpec.of(bare);
    }

    private static ItemStack checkedStack(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty()) throw new IllegalArgumentException("AE2 tile capture cannot contain an empty block item");
        ItemStack copy = stack.copy();
        copy.setCount(1);
        return copy;
    }

    private static List<ItemStack> copyStacks(List<ItemStack> stacks) {
        Objects.requireNonNull(stacks, "stacks");
        List<ItemStack> result = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) continue;
            result.add(stack.copy());
        }
        return List.copyOf(result);
    }

    private static List<Ae2BusCaptureData.InventoryStack> copyInventory(
            List<Ae2BusCaptureData.InventoryStack> contents) {
        Objects.requireNonNull(contents, "contents");
        return contents.stream().map(content -> new Ae2BusCaptureData.InventoryStack(content.slot(), content.stack()))
                .toList();
    }

    private static List<GenericInventoryStack> copyGenericInventory(List<GenericInventoryStack> contents) {
        Objects.requireNonNull(contents, "contents");
        return contents.stream().map(content -> new GenericInventoryStack(content.slot(), content.item(),
                content.fluid(), content.amount())).toList();
    }

    private static List<FluidStack> copyFluids(List<FluidStack> fluids) {
        Objects.requireNonNull(fluids, "fluids");
        List<FluidStack> result = new ArrayList<>(fluids.size());
        for (FluidStack fluid : fluids) {
            if (fluid == null || fluid.amount <= 0) continue;
            result.add(fluid.copy());
        }
        return List.copyOf(result);
    }

    private static void addInventoryRequirements(List<ResourceRequirement> requirements,
                                                 List<Ae2BusCaptureData.InventoryStack> contents) {
        for (Ae2BusCaptureData.InventoryStack content : contents) {
            ItemStack stack = content.stack();
            requirements.add(new ResourceRequirement(BlockSpec.of(stack), stack.getCount()));
        }
    }

    private static List<ItemStack> stacksFrom(List<Ae2BusCaptureData.InventoryStack> contents) {
        return contents.stream().map(Ae2BusCaptureData.InventoryStack::stack).toList();
    }

    private static boolean sameStacks(List<ItemStack> first, List<ItemStack> second) {
        if (first.size() != second.size()) return false;
        for (int index = 0; index < first.size(); index++) {
            ItemStack left = first.get(index);
            ItemStack right = second.get(index);
            if (left.getCount() != right.getCount() || !ItemStack.areItemStacksEqual(left, right)) return false;
        }
        return true;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Ae2TileCaptureData data &&
                ItemStack.areItemStacksEqual(placementStack, data.placementStack) &&
                sameStacks(blockDrops, data.blockDrops) && sameStacks(additionalDrops, data.additionalDrops) &&
                Objects.equals(settings, data.settings) && forward == data.forward && up == data.up &&
                inventory.equals(data.inventory) && viewCells.equals(data.viewCells) && upgrades.equals(data.upgrades) &&
                patterns.equals(data.patterns) &&
                configuredItems.equals(data.configuredItems) && configuredFluids.equals(data.configuredFluids) &&
                genericInventory.equals(data.genericInventory) && sameFluids(storedFluids, data.storedFluids) &&
                Double.compare(storedEnergy, data.storedEnergy) == 0 && patternProvider == data.patternProvider;
    }

    @Override
    public int hashCode() {
        return Objects.hash(placementStack.getItem().getRegistryName(), placementStack.getMetadata(),
                placementStack.getTagCompound(), stackHash(blockDrops), stackHash(additionalDrops), settings, forward,
                up, inventory, viewCells, upgrades, patterns, configuredItems, configuredFluids, genericInventory,
                fluidHash(storedFluids), storedEnergy, patternProvider);
    }

    private static int stackHash(List<ItemStack> stacks) {
        int result = 1;
        for (ItemStack stack : stacks) {
            result = 31 * result + Objects.hash(stack.getItem().getRegistryName(), stack.getMetadata(), stack.getCount(),
                    stack.getTagCompound());
        }
        return result;
    }

    private static boolean sameFluids(List<FluidStack> first, List<FluidStack> second) {
        if (first.size() != second.size()) return false;
        for (int index = 0; index < first.size(); index++) {
            FluidStack left = first.get(index);
            FluidStack right = second.get(index);
            if (left.amount != right.amount || !left.isFluidEqual(right) || !Objects.equals(left.tag, right.tag)) {
                return false;
            }
        }
        return true;
    }

    private static int fluidHash(List<FluidStack> fluids) {
        int result = 1;
        for (FluidStack fluid : fluids) {
            result = 31 * result + Objects.hash(fluid.getFluid().getName(), fluid.amount, fluid.tag);
        }
        return result;
    }
}
