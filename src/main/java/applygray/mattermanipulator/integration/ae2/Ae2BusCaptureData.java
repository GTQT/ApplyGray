package applygray.mattermanipulator.integration.ae2;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import applygray.mattermanipulator.building.BlockSpec;
import applygray.mattermanipulator.building.CapturedBlockData;
import applygray.mattermanipulator.inventory.ResourceRequirement;
import applygray.mattermanipulator.inventory.ResourceRequirements;

import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Mirror;
import net.minecraft.util.Rotation;
import org.jetbrains.annotations.Nullable;

/** Explicit AE2 cable-bus state represented solely by public dismantle-item stacks and facade stacks. */
public final class Ae2BusCaptureData implements CapturedBlockData {

    private final List<Part> parts;
    private final List<Facade> facades;

    public Ae2BusCaptureData(List<Part> parts, List<Facade> facades) {
        this.parts = copyParts(parts);
        this.facades = copyFacades(facades);
        validateLayout(this.parts, this.facades);
    }

    public static Ae2BusCaptureData singleCable(ItemStack cable) {
        return new Ae2BusCaptureData(List.of(new Part(null, cable)), List.of());
    }

    public List<Part> parts() {
        return copyParts(parts);
    }

    public List<Facade> facades() {
        return copyFacades(facades);
    }

    /** Uses bare part items as copy inputs; exported dismantle settings are applied after the item is consumed. */
    public ResourceRequirements requiredResources() {
        List<ResourceRequirement> requirements = new ArrayList<>();
        for (Part part : parts) {
            requirements.add(new ResourceRequirement(withoutSettings(part.stack), 1L));
            for (ItemStack content : part.requiredContents()) {
                requirements.add(new ResourceRequirement(BlockSpec.of(content), content.getCount()));
            }
        }
        for (Facade facade : facades) {
            requirements.add(new ResourceRequirement(BlockSpec.of(facade.stack), 1L));
        }
        return ResourceRequirements.of(requirements.toArray(ResourceRequirement[]::new));
    }

    /** Dismantle-item and facade stacks are the exact recoverable outputs of a removed cable bus. */
    public ResourceRequirements producedResources() {
        List<ItemStack> drops = new ArrayList<>(parts.size() + facades.size());
        for (Part part : parts) {
            drops.add(part.stack.copy());
            drops.addAll(part.producedContents());
        }
        for (Facade facade : facades) {
            drops.add(facade.stack.copy());
        }
        return ResourceRequirements.fromStacks(drops);
    }

    /** Produces a side-correct snapshot for a mirrored and rotated copy operation. */
    public Ae2BusCaptureData transformed(Mirror mirror, Rotation rotation) {
        Objects.requireNonNull(mirror, "mirror");
        Objects.requireNonNull(rotation, "rotation");
        List<Part> transformedParts = new ArrayList<>(parts.size());
        List<Facade> transformedFacades = new ArrayList<>(facades.size());
        for (Part part : parts) {
            transformedParts.add(new Part(transformFacing(part.side, mirror, rotation), part.stack,
                    part.patternProviderContents));
        }
        for (Facade facade : facades) {
            transformedFacades.add(new Facade(transformFacing(facade.side, mirror, rotation), facade.stack));
        }
        return new Ae2BusCaptureData(transformedParts, transformedFacades);
    }

    /** A canonical item identity for exchange matching and generic diagnostics. */
    public BlockSpec primaryMaterial() {
        for (Part part : parts) {
            if (part.side == null) return withoutSettings(part.stack);
        }
        return withoutSettings(parts.getFirst().stack);
    }

    private static EnumFacing transformFacing(EnumFacing side, Mirror mirror, Rotation rotation) {
        return side == null ? null : rotation.rotate(mirror.mirror(side));
    }

    private static BlockSpec withoutSettings(ItemStack stack) {
        ItemStack bare = stack.copy();
        bare.setCount(1);
        bare.setTagCompound(null);
        return BlockSpec.of(bare);
    }

    private static List<Part> copyParts(List<Part> parts) {
        Objects.requireNonNull(parts, "parts");
        return parts.stream().map(Part::copy).toList();
    }

    private static List<Facade> copyFacades(List<Facade> facades) {
        Objects.requireNonNull(facades, "facades");
        return facades.stream().map(Facade::copy).toList();
    }

    private static List<InventoryStack> copyInventoryStacks(List<InventoryStack> stacks) {
        Objects.requireNonNull(stacks, "stacks");
        Set<Integer> occupied = new java.util.HashSet<>();
        List<InventoryStack> copied = new ArrayList<>(stacks.size());
        for (InventoryStack stack : stacks) {
            if (!occupied.add(stack.slot())) {
                throw new IllegalArgumentException("A Pattern Provider inventory capture has duplicate slots");
            }
            copied.add(stack.copy());
        }
        return List.copyOf(copied);
    }

    private static void validateLayout(List<Part> parts, List<Facade> facades) {
        if (parts.isEmpty()) throw new IllegalArgumentException("An AE2 cable bus capture must contain a part");

        boolean hasCenter = false;
        Set<EnumFacing> occupiedSides = EnumSet.noneOf(EnumFacing.class);
        for (Part part : parts) {
            if (part.side == null) {
                if (hasCenter) throw new IllegalArgumentException("An AE2 cable bus capture has multiple center parts");
                hasCenter = true;
            } else if (!occupiedSides.add(part.side)) {
                throw new IllegalArgumentException("An AE2 cable bus capture has multiple parts on " + part.side);
            }
        }
        if (!hasCenter) throw new IllegalArgumentException("An AE2 cable bus capture must contain a center cable");

        Set<EnumFacing> facadeSides = EnumSet.noneOf(EnumFacing.class);
        for (Facade facade : facades) {
            if (!facadeSides.add(facade.side)) {
                throw new IllegalArgumentException("An AE2 cable bus capture has multiple facades on " + facade.side);
            }
        }
    }

    public static final class Part {

        private final EnumFacing side;
        private final ItemStack stack;
        @Nullable
        private final PatternProviderContents patternProviderContents;

        public Part(EnumFacing side, ItemStack stack) {
            this(side, stack, null);
        }

        public Part(EnumFacing side, ItemStack stack, @Nullable PatternProviderContents patternProviderContents) {
            this.side = side;
            this.stack = checkedStack(stack);
            this.patternProviderContents = patternProviderContents == null ? null : patternProviderContents.copy();
        }

        public EnumFacing side() {
            return side;
        }

        public ItemStack stack() {
            return stack.copy();
        }

        @Nullable
        public PatternProviderContents patternProviderContents() {
            return patternProviderContents == null ? null : patternProviderContents.copy();
        }

        private List<ItemStack> requiredContents() {
            return patternProviderContents == null ? List.of() : patternProviderContents.requiredStacks();
        }

        private List<ItemStack> producedContents() {
            return patternProviderContents == null ? List.of() : patternProviderContents.producedStacks();
        }

        private Part copy() {
            return new Part(side, stack, patternProviderContents);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Part part && side == part.side && ItemStack.areItemStacksEqual(stack, part.stack) &&
                    Objects.equals(patternProviderContents, part.patternProviderContents);
        }

        @Override
        public int hashCode() {
            return Objects.hash(side, stack.getItem().getRegistryName(), stack.getMetadata(), stack.getTagCompound(),
                    patternProviderContents);
        }
    }

    /** Explicit Pattern Provider payload that keeps normal card transfer separate from Smart Copy links. */
    public static final class PatternProviderContents {

        private final List<InventoryStack> patterns;
        private final List<InventoryStack> upgrades;
        @Nullable
        private final SmartCopyPatternProviderLink smartCopyLink;

        public PatternProviderContents(List<InventoryStack> patterns, List<InventoryStack> upgrades) {
            this(patterns, upgrades, null);
        }

        private PatternProviderContents(List<InventoryStack> patterns, List<InventoryStack> upgrades,
                                        @Nullable SmartCopyPatternProviderLink smartCopyLink) {
            this.patterns = copyInventoryStacks(patterns);
            this.upgrades = copyInventoryStacks(upgrades);
            this.smartCopyLink = smartCopyLink;
            if (smartCopyLink != null && (!this.patterns.isEmpty() || !this.upgrades.isEmpty())) {
                throw new IllegalArgumentException("Smart Copy Pattern Providers cannot carry local cards");
            }
        }

        public static PatternProviderContents smartCopy(SmartCopyPatternProviderLink source) {
            return new PatternProviderContents(List.of(), List.of(), Objects.requireNonNull(source, "source"));
        }

        public List<InventoryStack> patterns() {
            return copyInventoryStacks(patterns);
        }

        public List<InventoryStack> upgrades() {
            return copyInventoryStacks(upgrades);
        }

        @Nullable
        public SmartCopyPatternProviderLink smartCopyLink() {
            return smartCopyLink;
        }

        private List<ItemStack> requiredStacks() {
            return smartCopyLink == null ? storedStacks() : List.of();
        }

        private List<ItemStack> producedStacks() {
            return smartCopyLink == null ? storedStacks() : List.of();
        }

        private List<ItemStack> storedStacks() {
            List<ItemStack> result = new ArrayList<>(patterns.size() + upgrades.size());
            for (InventoryStack pattern : patterns) {
                result.add(pattern.stack());
            }
            for (InventoryStack upgrade : upgrades) {
                result.add(upgrade.stack());
            }
            return result;
        }

        private PatternProviderContents copy() {
            return new PatternProviderContents(patterns, upgrades, smartCopyLink);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof PatternProviderContents contents && patterns.equals(contents.patterns) &&
                    upgrades.equals(contents.upgrades) && Objects.equals(smartCopyLink, contents.smartCopyLink);
        }

        @Override
        public int hashCode() {
            return Objects.hash(patterns, upgrades, smartCopyLink);
        }
    }

    /** One occupied public AE2 inventory slot, including its original slot index. */
    public static final class InventoryStack {

        private final int slot;
        private final ItemStack stack;

        public InventoryStack(int slot, ItemStack stack) {
            if (slot < 0) throw new IllegalArgumentException("Inventory slots cannot be negative");
            this.slot = slot;
            this.stack = checkedStack(stack);
        }

        public int slot() {
            return slot;
        }

        public ItemStack stack() {
            return stack.copy();
        }

        private InventoryStack copy() {
            return new InventoryStack(slot, stack);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof InventoryStack stored && slot == stored.slot &&
                    ItemStack.areItemStacksEqual(stack, stored.stack);
        }

        @Override
        public int hashCode() {
            return Objects.hash(slot, stack.getItem().getRegistryName(), stack.getMetadata(), stack.getTagCompound());
        }
    }

    public static final class Facade {

        private final EnumFacing side;
        private final ItemStack stack;

        public Facade(EnumFacing side, ItemStack stack) {
            this.side = Objects.requireNonNull(side, "side");
            this.stack = checkedStack(stack);
        }

        public EnumFacing side() {
            return side;
        }

        public ItemStack stack() {
            return stack.copy();
        }

        private Facade copy() {
            return new Facade(side, stack);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Facade facade && side == facade.side &&
                    ItemStack.areItemStacksEqual(stack, facade.stack);
        }

        @Override
        public int hashCode() {
            return Objects.hash(side, stack.getItem().getRegistryName(), stack.getMetadata(), stack.getTagCompound());
        }
    }

    private static ItemStack checkedStack(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty()) throw new IllegalArgumentException("AE2 cable-bus capture cannot contain an empty stack");
        ItemStack copy = stack.copy();
        copy.setCount(1);
        return copy;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Ae2BusCaptureData data && parts.equals(data.parts) && facades.equals(data.facades);
    }

    @Override
    public int hashCode() {
        return Objects.hash(parts, facades);
    }
}
