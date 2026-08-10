package applygray.common;

import applygray.mattermanipulator.item.MatterManipulatorItems;

import gregtech.api.GTValues;
import gregtech.api.recipes.ModHandler;
import gregtech.api.recipes.RecipeMaps;
import gregtech.api.util.GTUtility;
import gregtech.api.util.Mods;
import gregtech.common.metatileentities.MetaTileEntities;
import static gregtech.api.GTValues.VA;
import static gregtech.api.GTValues.EV;
import static gregtech.api.GTValues.HV;
import static gregtech.api.GTValues.IV;
import static gregtech.api.GTValues.LuV;
import static gregtech.api.GTValues.UHV;
import static gregtech.api.GTValues.ZPM;

import net.minecraft.item.ItemStack;
import static gregtech.api.recipes.RecipeMaps.ASSEMBLER_RECIPES;
import static gregtech.common.metatileentities.MetaTileEntities.FLUID_EXPORT_HATCH;
import static gregtech.common.metatileentities.MetaTileEntities.FLUID_IMPORT_HATCH;
import static gregtech.common.metatileentities.MetaTileEntities.ITEM_EXPORT_BUS;
import static gregtech.common.metatileentities.MetaTileEntities.ITEM_IMPORT_BUS;
import static gregtech.common.items.MetaItems.CONVEYOR_MODULE_IV;
import static gregtech.common.items.MetaItems.SENSOR_IV;
import static gregtech.common.items.MetaItems.ELECTRIC_PUMP_IV;
import static gregtech.api.unification.ore.OrePrefix.circuit;
import static gregtech.common.metatileentities.MetaTileEntities.DUAL_EXPORT_HATCH;
import static gregtech.common.metatileentities.MetaTileEntities.DUAL_IMPORT_HATCH;
import static gregtech.common.metatileentities.MetaTileEntities.ENERGY_INPUT_HATCH;

import gregtech.api.unification.material.MarkerMaterial;

/**
 * Registers recipes for ME (Applied Energistics 2) machines.
 * These recipes were previously registered in GregTech's recipe loaders.
 */
public class ApplyGrayRecipes {

    public static void init() {
        registerMEAssemblerRecipes();
        registerMEDualHatchRecipes();
        registerMatterManipulatorRecipes();
    }

    private static void registerMEDualHatchRecipes() {
        ASSEMBLER_RECIPES.recipeBuilder()
                .input(ApplyGrayMetaTileEntities.ITEM_IMPORT_BUS_ME)
                .input(ApplyGrayMetaTileEntities.FLUID_IMPORT_HATCH_ME)
                .input(DUAL_IMPORT_HATCH[LuV])
                .input(circuit, MarkerMaterial.create(GTValues.VN[LuV].toLowerCase()), 4)
                .output(ApplyGrayMetaTileEntities.ME_DUAL_IMPORT_HATCH)
                .duration(100).EUt(VA[IV]).buildAndRegister();

        ASSEMBLER_RECIPES.recipeBuilder()
                .input(ApplyGrayMetaTileEntities.ITEM_EXPORT_BUS_ME)
                .input(ApplyGrayMetaTileEntities.FLUID_EXPORT_HATCH_ME)
                .input(DUAL_EXPORT_HATCH[LuV])
                .input(circuit, MarkerMaterial.create(GTValues.VN[LuV].toLowerCase()), 4)
                .output(ApplyGrayMetaTileEntities.ME_DUAL_EXPORT_HATCH)
                .duration(100).EUt(VA[IV]).buildAndRegister();
    }

    public static void postInit() {
        registerMEConversionRecipes();
    }

    private static void registerMEAssemblerRecipes() {
        if (!Mods.AppliedEnergistics2.isModLoaded()) return;

        ItemStack fluidInterface = Mods.AppliedEnergistics2.getItem("fluid_interface");
        ItemStack normalInterface = Mods.AppliedEnergistics2.getItem("interface");
        ItemStack accelerationCard = Mods.AppliedEnergistics2.getItem("material", 30, 2);

        ASSEMBLER_RECIPES.recipeBuilder()
                .input(FLUID_EXPORT_HATCH[EV])
                .inputs(fluidInterface.copy())
                .inputs(accelerationCard.copy())
                .output(ApplyGrayMetaTileEntities.FLUID_EXPORT_HATCH_ME)
                .duration(300).EUt(VA[HV]).buildAndRegister();

        ASSEMBLER_RECIPES.recipeBuilder()
                .input(FLUID_IMPORT_HATCH[EV])
                .inputs(fluidInterface.copy())
                .inputs(accelerationCard.copy())
                .output(ApplyGrayMetaTileEntities.FLUID_IMPORT_HATCH_ME)
                .duration(300).EUt(VA[HV]).buildAndRegister();

        ASSEMBLER_RECIPES.recipeBuilder()
                .input(ITEM_EXPORT_BUS[EV])
                .inputs(normalInterface.copy())
                .inputs(accelerationCard.copy())
                .output(ApplyGrayMetaTileEntities.ITEM_EXPORT_BUS_ME)
                .duration(300).EUt(VA[HV]).buildAndRegister();

        ASSEMBLER_RECIPES.recipeBuilder()
                .input(ITEM_IMPORT_BUS[EV])
                .inputs(normalInterface.copy())
                .inputs(accelerationCard.copy())
                .output(ApplyGrayMetaTileEntities.ITEM_IMPORT_BUS_ME)
                .duration(300).EUt(VA[HV]).buildAndRegister();

        ASSEMBLER_RECIPES.recipeBuilder()
                .input(ITEM_IMPORT_BUS[IV])
                .inputs(normalInterface.copy())
                .input(CONVEYOR_MODULE_IV)
                .input(SENSOR_IV)
                .inputs(GTUtility.copy(4, accelerationCard))
                .output(ApplyGrayMetaTileEntities.STOCKING_BUS_ME)
                .duration(300).EUt(VA[IV]).buildAndRegister();

        ASSEMBLER_RECIPES.recipeBuilder()
                .input(FLUID_IMPORT_HATCH[IV])
                .inputs(fluidInterface.copy())
                .input(ELECTRIC_PUMP_IV)
                .input(SENSOR_IV)
                .inputs(GTUtility.copy(4, accelerationCard))
                .output(ApplyGrayMetaTileEntities.STOCKING_HATCH_ME)
                .duration(300).EUt(VA[IV]).buildAndRegister();


        ASSEMBLER_RECIPES.recipeBuilder()
                .input(ApplyGrayMetaTileEntities.ME_PATTERN_PROVIDER[UHV - HV])
                .input(ApplyGrayMetaTileEntities.ME_ORE_PREFIX_PATTERN_PROVIDER)
                .input(ApplyGrayMetaTileEntities.ME_PATTERN_PROVIDER_PROXY)
                .input(circuit, MarkerMaterial.create(GTValues.VN[UHV].toLowerCase()), 4)
                .output(ApplyGrayMetaTileEntities.ME_RECIPE_MAP_PATTERN_PROVIDER)
                .duration(600).EUt(VA[UHV]).buildAndRegister();
    }

    private static void registerMEConversionRecipes() {
        if (!Mods.AppliedEnergistics2.isModLoaded()) return;

        ModHandler.addShapedRecipe("me_fluid_hatch_output_to_input", ApplyGrayMetaTileEntities.FLUID_IMPORT_HATCH_ME.getStackForm(), "d", "B",
                'B', ApplyGrayMetaTileEntities.FLUID_EXPORT_HATCH_ME.getStackForm());
        ModHandler.addShapedRecipe("me_fluid_hatch_input_to_output", ApplyGrayMetaTileEntities.FLUID_EXPORT_HATCH_ME.getStackForm(), "d", "B",
                'B', ApplyGrayMetaTileEntities.FLUID_IMPORT_HATCH_ME.getStackForm());
        ModHandler.addShapedRecipe("me_item_bus_output_to_input", ApplyGrayMetaTileEntities.ITEM_IMPORT_BUS_ME.getStackForm(), "d", "B", 'B',
                ApplyGrayMetaTileEntities.ITEM_EXPORT_BUS_ME.getStackForm());
        ModHandler.addShapedRecipe("me_item_bus_input_to_output", ApplyGrayMetaTileEntities.ITEM_EXPORT_BUS_ME.getStackForm(), "d", "B", 'B',
                ApplyGrayMetaTileEntities.ITEM_IMPORT_BUS_ME.getStackForm());
    }

    /**
     * Target-native progression for the four tool tiers, their installable upgrades, and the Quantum Uplink pair.
     *
     * <p>These recipes intentionally use the current GregTech component chain instead of retaining 1.7.10 meta-item
     * identifiers. The controller and connector therefore remain discoverable through normal JEI recipe lookup.</p>
     */
    private static void registerMatterManipulatorRecipes() {
        ASSEMBLER_RECIPES.recipeBuilder()
                .input(gregtech.common.items.MetaItems.POWER_UNIT_IV)
                .input(gregtech.common.items.MetaItems.FIELD_GENERATOR_HV)
                .input(gregtech.common.items.MetaItems.SENSOR_HV)
                .input(circuit, MarkerMaterial.create(GTValues.VN[HV].toLowerCase()), 2)
                .output(MatterManipulatorItems.MK0)
                .duration(400).EUt(VA[HV]).buildAndRegister();

        ASSEMBLER_RECIPES.recipeBuilder()
                .input(MatterManipulatorItems.MK0)
                .input(gregtech.common.items.MetaItems.FIELD_GENERATOR_EV)
                .input(gregtech.common.items.MetaItems.QUANTUM_PROCESSOR_EV)
                .input(circuit, MarkerMaterial.create(GTValues.VN[EV].toLowerCase()), 2)
                .output(MatterManipulatorItems.MK1)
                .duration(600).EUt(VA[EV]).buildAndRegister();

        ASSEMBLER_RECIPES.recipeBuilder()
                .input(MatterManipulatorItems.MK1)
                .input(gregtech.common.items.MetaItems.FIELD_GENERATOR_IV)
                .input(gregtech.common.items.MetaItems.QUANTUM_ASSEMBLY_IV)
                .input(gregtech.common.items.MetaItems.EMITTER_IV)
                .input(circuit, MarkerMaterial.create(GTValues.VN[IV].toLowerCase()), 2)
                .output(MatterManipulatorItems.MK2)
                .duration(800).EUt(VA[IV]).buildAndRegister();

        ASSEMBLER_RECIPES.recipeBuilder()
                .input(MatterManipulatorItems.MK2)
                .input(gregtech.common.items.MetaItems.FIELD_GENERATOR_ZPM)
                .input(gregtech.common.items.MetaItems.QUANTUM_MAINFRAME_ZPM)
                .input(gregtech.common.items.MetaItems.EMITTER_ZPM)
                .input(circuit, MarkerMaterial.create(GTValues.VN[ZPM].toLowerCase()), 2)
                .output(MatterManipulatorItems.MK3)
                .duration(1_200).EUt(VA[ZPM]).buildAndRegister();

        ASSEMBLER_RECIPES.recipeBuilder()
                .input(gregtech.common.items.MetaItems.FIELD_GENERATOR_ZPM)
                .input(gregtech.common.items.MetaItems.EMITTER_ZPM)
                .input(gregtech.common.items.MetaItems.SENSOR_ZPM)
                .input(circuit, MarkerMaterial.create(GTValues.VN[ZPM].toLowerCase()), 4)
                .output(ApplyGrayMetaTileEntities.QUANTUM_UPLINK)
                .duration(1_200).EUt(VA[ZPM]).buildAndRegister();

        ASSEMBLER_RECIPES.recipeBuilder()
                .input(ApplyGrayMetaTileEntities.ITEM_IMPORT_BUS_ME)
                .input(ENERGY_INPUT_HATCH[ZPM])
                .input(gregtech.common.items.MetaItems.EMITTER_ZPM)
                .input(gregtech.common.items.MetaItems.SENSOR_ZPM)
                .input(circuit, MarkerMaterial.create(GTValues.VN[ZPM].toLowerCase()), 2)
                .output(ApplyGrayMetaTileEntities.QUANTUM_UPLINK_ME_HATCH)
                .duration(800).EUt(VA[ZPM]).buildAndRegister();

        ASSEMBLER_RECIPES.recipeBuilder()
                .input(gregtech.common.items.MetaItems.FIELD_GENERATOR_ZPM)
                .input(gregtech.common.items.MetaItems.EMITTER_ZPM)
                .output(MatterManipulatorItems.POWER_P2P_UPGRADE)
                .duration(400).EUt(VA[ZPM]).buildAndRegister();

        ASSEMBLER_RECIPES.recipeBuilder()
                .input(gregtech.common.items.MetaItems.ROBOT_ARM_HV)
                .input(gregtech.common.items.MetaItems.SENSOR_HV)
                .output(MatterManipulatorItems.MINING_UPGRADE)
                .duration(200).EUt(VA[HV]).buildAndRegister();

        ASSEMBLER_RECIPES.recipeBuilder()
                .input(gregtech.common.items.MetaItems.CONVEYOR_MODULE_IV)
                .input(gregtech.common.items.MetaItems.ELECTRIC_MOTOR_IV)
                .output(MatterManipulatorItems.SPEED_UPGRADE)
                .duration(200).EUt(VA[IV]).buildAndRegister();

        ASSEMBLER_RECIPES.recipeBuilder()
                .input(gregtech.common.items.MetaItems.FIELD_GENERATOR_IV)
                .input(gregtech.common.items.MetaItems.QUANTUM_PROCESSOR_EV)
                .output(MatterManipulatorItems.POWER_EFFICIENCY_UPGRADE)
                .duration(200).EUt(VA[IV]).buildAndRegister();
    }
}
