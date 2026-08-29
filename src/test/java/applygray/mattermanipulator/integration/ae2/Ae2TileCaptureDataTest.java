package applygray.mattermanipulator.integration.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import applygray.mattermanipulator.building.BlockSpec;
import applygray.mattermanipulator.inventory.FluidRequirement;
import applygray.mattermanipulator.inventory.ResourceRequirement;
import applygray.mattermanipulator.state.ManipulatorTransform;

import ae2.block.crafting.PushDirection;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class Ae2TileCaptureDataTest {

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        Bootstrap.register();
    }

    @Test
    void transformsTileOrientationAndPatternProviderPushDirection() {
        NBTTagCompound settings = new NBTTagCompound();
        settings.setInteger("pushDirection", PushDirection.NORTH.ordinal());
        settings.setInteger("applygray_output_sides", 1 << EnumFacing.NORTH.getIndex() | 1 << EnumFacing.UP.getIndex());
        Ae2TileCaptureData source = data(settings, EnumFacing.NORTH, EnumFacing.UP, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), 0.0D, true);
        ManipulatorTransform transform = ManipulatorTransform.identity().rotate(EnumFacing.Axis.Y, true);

        Ae2TileCaptureData transformed = source.transformed(transform);

        assertEquals(transform.apply(EnumFacing.NORTH), transformed.forward());
        assertEquals(transform.apply(EnumFacing.UP), transformed.up());
        assertEquals(PushDirection.fromDirection(transform.apply(EnumFacing.NORTH)).ordinal(),
                transformed.settings().getInteger("pushDirection"));
        assertEquals(1 << transform.apply(EnumFacing.NORTH).getIndex() | 1 << transform.apply(EnumFacing.UP).getIndex(),
                transformed.settings().getInteger("applygray_output_sides"));
    }

    @Test
    void separatesRealContentsFromConfigurationRequirementsAndOutputs() {
        Fluid fluid = fluid("ae2_tile_capture_resource");
        List<Ae2BusCaptureData.InventoryStack> inventory = List.of(
                new Ae2BusCaptureData.InventoryStack(0, new ItemStack(Items.APPLE, 3)));
        List<Ae2BusCaptureData.InventoryStack> upgrades = List.of(
                new Ae2BusCaptureData.InventoryStack(1, new ItemStack(Items.REDSTONE)));
        List<Ae2BusCaptureData.InventoryStack> patterns = List.of(
                new Ae2BusCaptureData.InventoryStack(2, new ItemStack(Items.PAPER, 2)));
        List<ResourceRequirement> configuredItems = List.of(
                new ResourceRequirement(BlockSpec.of(new ItemStack(Items.IRON_INGOT)), 5));
        List<FluidRequirement> configuredFluids = List.of(
                new FluidRequirement(new FluidStack(fluid, 1), 500));
        List<Ae2TileCaptureData.GenericInventoryStack> generic = List.of(
                new Ae2TileCaptureData.GenericInventoryStack(0, new ItemStack(Items.DIAMOND), null, 7),
                new Ae2TileCaptureData.GenericInventoryStack(1, null, new FluidStack(fluid, 1), 250));
        Ae2TileCaptureData captured = data(new NBTTagCompound(), EnumFacing.NORTH, EnumFacing.UP, inventory,
                upgrades, patterns, configuredItems, configuredFluids, generic,
                List.of(new FluidStack(fluid, 1000)), 0.0D, false);

        Map<String, Long> requiredItems = captured.requiredResources().entries().stream().collect(Collectors.toMap(
                entry -> entry.specification().sortKey(), ResourceRequirement::amount));
        Map<String, Long> producedItems = captured.producedResources().entries().stream().collect(Collectors.toMap(
                entry -> entry.specification().sortKey(), ResourceRequirement::amount));

        assertEquals(1L, requiredItems.get(BlockSpec.of(new ItemStack(Blocks.STONE)).sortKey()));
        assertEquals(3L, requiredItems.get(BlockSpec.of(new ItemStack(Items.APPLE)).sortKey()));
        assertEquals(5L, requiredItems.get(BlockSpec.of(new ItemStack(Items.IRON_INGOT)).sortKey()));
        assertEquals(7L, requiredItems.get(BlockSpec.of(new ItemStack(Items.DIAMOND)).sortKey()));
        assertEquals(1L, producedItems.get(BlockSpec.of(new ItemStack(Blocks.STONE)).sortKey()));
        assertEquals(3L, producedItems.get(BlockSpec.of(new ItemStack(Items.APPLE)).sortKey()));
        assertEquals(null, producedItems.get(BlockSpec.of(new ItemStack(Items.IRON_INGOT)).sortKey()));
        assertEquals(1750L, captured.requiredResources().fluidEntries().getFirst().amount());
        assertEquals(1250L, captured.producedResources().fluidEntries().getFirst().amount());
    }

    @Test
    void defensivelyCopiesMutableStacksTagsAndFluids() {
        ItemStack stored = new ItemStack(Items.APPLE, 2);
        NBTTagCompound settings = new NBTTagCompound();
        settings.setString("mode", "before");
        FluidStack fluid = new FluidStack(fluid("ae2_tile_capture_copy"), 400);
        Ae2TileCaptureData captured = data(settings, EnumFacing.NORTH, EnumFacing.UP,
                List.of(new Ae2BusCaptureData.InventoryStack(0, stored)), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(fluid), 0.0D, false);

        stored.setCount(1);
        settings.setString("mode", "after");
        fluid.amount = 1;
        ItemStack returned = captured.inventory().getFirst().stack();
        returned.setCount(1);
        FluidStack returnedFluid = captured.storedFluids().getFirst();
        returnedFluid.amount = 1;

        assertEquals(2, captured.inventory().getFirst().stack().getCount());
        assertEquals("before", captured.settings().getString("mode"));
        assertEquals(400, captured.storedFluids().getFirst().amount);
        assertNotEquals(returned.getCount(), captured.inventory().getFirst().stack().getCount());
    }

    private static Ae2TileCaptureData data(NBTTagCompound settings, EnumFacing forward, EnumFacing up,
                                           List<Ae2BusCaptureData.InventoryStack> inventory,
                                           List<Ae2BusCaptureData.InventoryStack> upgrades,
                                           List<Ae2BusCaptureData.InventoryStack> patterns,
                                           List<ResourceRequirement> configuredItems,
                                           List<FluidRequirement> configuredFluids,
                                           List<Ae2TileCaptureData.GenericInventoryStack> generic,
                                           List<FluidStack> storedFluids, double storedEnergy,
                                           boolean patternProvider) {
        ItemStack block = new ItemStack(Blocks.STONE);
        return new Ae2TileCaptureData(block, List.of(block), List.of(), settings, forward, up, inventory, upgrades,
                patterns, configuredItems, configuredFluids, generic, storedFluids, storedEnergy, patternProvider);
    }

    private static Fluid fluid(String name) {
        ResourceLocation texture = new ResourceLocation("applygray", name);
        Fluid fluid = new Fluid(name, texture, texture);
        if (!FluidRegistry.registerFluid(fluid)) fluid = FluidRegistry.getFluid(name);
        return fluid;
    }
}
