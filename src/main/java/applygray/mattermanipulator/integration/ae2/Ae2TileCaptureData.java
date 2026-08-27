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

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;

/** Explicit AE2 block-entity state; no raw tile NBT is used as a portable payload. */
public final class Ae2TileCaptureData implements CapturedBlockData {

    private final ItemStack placementStack;
    private final List<ItemStack> blockDrops;
    private final List<ItemStack> additionalDrops;
    private final NBTTagCompound settings;
    private final EnumFacing forward;
    private final EnumFacing up;
    private final List<Ae2BusCaptureData.InventoryStack> inventory;
    private final List<Ae2BusCaptureData.InventoryStack> upgrades;
    private final List<Ae2BusCaptureData.InventoryStack> patterns;
    private final List<ResourceRequirement> configuredItems;
    private final List<FluidRequirement> configuredFluids;
    private final boolean patternProvider;

    public Ae2TileCaptureData(ItemStack placementStack, List<ItemStack> blockDrops, List<ItemStack> additionalDrops,
                              NBTTagCompound settings, EnumFacing forward, EnumFacing up,
                              List<Ae2BusCaptureData.InventoryStack> inventory,
                              List<Ae2BusCaptureData.InventoryStack> upgrades,
                              List<Ae2BusCaptureData.InventoryStack> patterns,
                              List<ResourceRequirement> configuredItems,
                              List<FluidRequirement> configuredFluids, boolean patternProvider) {
        this.placementStack = checkedStack(placementStack);
        this.blockDrops = copyStacks(blockDrops);
        this.additionalDrops = copyStacks(additionalDrops);
        this.settings = Objects.requireNonNull(settings, "settings").copy();
        this.forward = forward;
        this.up = up;
        this.inventory = copyInventory(inventory);
        this.upgrades = copyInventory(upgrades);
        this.patterns = copyInventory(patterns);
        this.configuredItems = List.copyOf(configuredItems);
        this.configuredFluids = List.copyOf(configuredFluids);
        this.patternProvider = patternProvider;
        if ((forward == null) != (up == null)) {
            throw new IllegalArgumentException("AE2 tile orientation must contain both forward and up");
        }
    }

    public static Ae2TileCaptureData forMaterial(ItemStack material) {
        ItemStack stack = checkedStack(material);
        return new Ae2TileCaptureData(stack, List.of(stack), List.of(), new NBTTagCompound(), null, null, List.of(),
                List.of(), List.of(), List.of(), List.of(), false);
    }

    public BlockSpec primaryMaterial() {
        return bare(placementStack);
    }

    public ResourceRequirements requiredResources() {
        List<ResourceRequirement> itemRequirements = new ArrayList<>();
        itemRequirements.add(new ResourceRequirement(primaryMaterial(), 1L));
        for (ItemStack stack : additionalDrops) {
            itemRequirements.add(new ResourceRequirement(BlockSpec.of(stack), stack.getCount()));
        }
        itemRequirements.addAll(configuredItems);
        ResourceRequirements items = ResourceRequirements.of(itemRequirements.toArray(ResourceRequirement[]::new));
        ResourceRequirements fluids = ResourceRequirements.fluids(configuredFluids.toArray(FluidRequirement[]::new));
        return ResourceRequirements.combine(items, fluids);
    }

    public ResourceRequirements producedResources() {
        List<ItemStack> outputs = new ArrayList<>(blockDrops.size() + additionalDrops.size());
        outputs.addAll(copyStacks(blockDrops));
        outputs.addAll(copyStacks(additionalDrops));
        return ResourceRequirements.fromStacks(outputs);
    }

    public Ae2TileCaptureData transformed(ManipulatorTransform transform) {
        Objects.requireNonNull(transform, "transform");
        return new Ae2TileCaptureData(placementStack, blockDrops, additionalDrops, settings,
                forward == null ? null : transform.apply(forward), up == null ? null : transform.apply(up), inventory,
                upgrades, patterns, configuredItems, configuredFluids, patternProvider);
    }

    public ItemStack placementStack() {
        return placementStack.copy();
    }

    public NBTTagCompound settings() {
        return settings.copy();
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

    public List<Ae2BusCaptureData.InventoryStack> upgrades() {
        return copyInventory(upgrades);
    }

    public List<Ae2BusCaptureData.InventoryStack> patterns() {
        return copyInventory(patterns);
    }

    public boolean patternProvider() {
        return patternProvider;
    }

    public int componentCount() {
        return 2 + additionalDrops.size() + configuredItems.size() + configuredFluids.size();
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
        return contents.stream().map(content -> new Ae2BusCaptureData.InventoryStack(content.slot(), content.stack()))
                .toList();
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
                inventory.equals(data.inventory) && upgrades.equals(data.upgrades) && patterns.equals(data.patterns) &&
                configuredItems.equals(data.configuredItems) && configuredFluids.equals(data.configuredFluids) &&
                patternProvider == data.patternProvider;
    }

    @Override
    public int hashCode() {
        return Objects.hash(placementStack.getItem().getRegistryName(), placementStack.getMetadata(),
                placementStack.getTagCompound(), stackHash(blockDrops), stackHash(additionalDrops), settings, forward,
                up, inventory, upgrades, patterns, configuredItems, configuredFluids, patternProvider);
    }

    private static int stackHash(List<ItemStack> stacks) {
        int result = 1;
        for (ItemStack stack : stacks) {
            result = 31 * result + Objects.hash(stack.getItem().getRegistryName(), stack.getMetadata(), stack.getCount(),
                    stack.getTagCompound());
        }
        return result;
    }
}
