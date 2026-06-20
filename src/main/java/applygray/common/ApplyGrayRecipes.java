package applygray.common;

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
import static gregtech.common.metatileentities.GTQTMetaTileEntities.DUAL_EXPORT_HATCH;
import static gregtech.common.metatileentities.GTQTMetaTileEntities.DUAL_IMPORT_HATCH;

import gregtech.api.unification.material.MarkerMaterial;

/**
 * Registers recipes for ME (Applied Energistics 2) machines.
 * These recipes were previously registered in GregTech's recipe loaders.
 */
public class ApplyGrayRecipes {

    public static void init() {
        registerMEAssemblerRecipes();
        registerMEDualHatchRecipes();
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
}
