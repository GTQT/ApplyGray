package applygray.common.metatileentities.multi.electric.eco;

import applygray.common.ApplyGrayMetaTileEntities;
import applygray.common.items.ApplyGrayMetaItems;

import applygray.common.metatileentities.multi.multiblockpart.eco.EcoPartRole;

import gregtech.api.GTValues;
import gregtech.api.items.metaitem.MetaItem;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.unification.material.MarkerMaterial;
import gregtech.api.unification.material.Material;
import gregtech.api.unification.material.Materials;
import gregtech.common.blocks.BlockComputerCasing;
import gregtech.common.blocks.MetaBlocks;
import gregtech.common.items.MetaItems;
import gregtech.common.metatileentities.MetaTileEntities;

import java.util.Locale;

import static gregtech.api.GTValues.IV;
import static gregtech.api.GTValues.LuV;
import static gregtech.api.GTValues.UHV;
import static gregtech.api.GTValues.UV;
import static gregtech.api.GTValues.VA;
import static gregtech.api.GTValues.ZPM;
import static gregtech.api.recipes.RecipeMaps.ASSEMBLER_RECIPES;
import static gregtech.api.recipes.RecipeMaps.ASSEMBLY_LINE_RECIPES;
import static gregtech.api.recipes.RecipeMaps.CIRCUIT_ASSEMBLER_RECIPES;
import static gregtech.api.unification.ore.OrePrefix.circuit;
import static gregtech.api.unification.ore.OrePrefix.frameGt;
import static gregtech.api.unification.ore.OrePrefix.gear;
import static gregtech.api.unification.ore.OrePrefix.plate;
import static gregtech.api.unification.ore.OrePrefix.rotor;
import static gregtech.api.unification.ore.OrePrefix.wireGtDouble;

/**
 * ECO 配方：五档电压（IV → UHV），仓室按 PrismPlan 的 {@code VA[IV + i]} 递进，
 * 控制器用 LuV 装配线。
 */
public final class EcoRecipes {

    private static final int[] VOLTAGES = { IV, LuV, ZPM, UV, UHV };

    private static final Material[] PLATES = {
            Materials.Titanium,
            Materials.TungstenSteel,
            Materials.RhodiumPlatedPalladium,
            Materials.NaquadahAlloy,
            Materials.Darmstadtium
    };

    private static final MetaItem<?>.MetaValueItem[] EMITTERS = {
            MetaItems.EMITTER_IV,
            MetaItems.EMITTER_LuV,
            MetaItems.EMITTER_ZPM,
            MetaItems.EMITTER_UV,
            MetaItems.EMITTER_UHV
    };

    private static final MetaItem<?>.MetaValueItem[] FIELD_GENERATORS = {
            MetaItems.FIELD_GENERATOR_IV,
            MetaItems.FIELD_GENERATOR_LuV,
            MetaItems.FIELD_GENERATOR_ZPM,
            MetaItems.FIELD_GENERATOR_UV,
            MetaItems.FIELD_GENERATOR_UHV
    };

    private static final MetaItem<?>.MetaValueItem[] ROBOT_ARMS = {
            MetaItems.ROBOT_ARM_IV,
            MetaItems.ROBOT_ARM_LuV,
            MetaItems.ROBOT_ARM_ZPM,
            MetaItems.ROBOT_ARM_UV,
            MetaItems.ROBOT_ARM_UHV
    };

    private static final MetaItem<?>.MetaValueItem[] PUMPS = {
            MetaItems.ELECTRIC_PUMP_IV,
            MetaItems.ELECTRIC_PUMP_LuV,
            MetaItems.ELECTRIC_PUMP_ZPM,
            MetaItems.ELECTRIC_PUMP_UV,
            MetaItems.ELECTRIC_PUMP_UHV
    };

    private static final MetaItem<?>.MetaValueItem[] SENSORS = {
            MetaItems.SENSOR_IV,
            MetaItems.SENSOR_LuV,
            MetaItems.SENSOR_ZPM,
            MetaItems.SENSOR_UV,
            MetaItems.SENSOR_UHV
    };

    private static final MetaItem<?>.MetaValueItem[] CONVEYORS = {
            MetaItems.CONVEYOR_MODULE_IV,
            MetaItems.CONVEYOR_MODULE_LuV,
            MetaItems.CONVEYOR_MODULE_ZPM,
            MetaItems.CONVEYOR_MODULE_UV,
            MetaItems.CONVEYOR_MODULE_UHV
    };

    private static final MetaItem<?>.MetaValueItem[] MEMORY_CELLS = {
            ApplyGrayMetaItems.ECO_MEMORY_T1.stack,
            ApplyGrayMetaItems.ECO_MEMORY_T2.stack,
            ApplyGrayMetaItems.ECO_MEMORY_T3.stack,
            ApplyGrayMetaItems.ECO_MEMORY_T4.stack,
            ApplyGrayMetaItems.ECO_MEMORY_T5.stack
    };

    private static final MetaItem<?>.MetaValueItem[] ITEM_CELLS = {
            ApplyGrayMetaItems.ECO_ITEM_CELL_1.stack,
            ApplyGrayMetaItems.ECO_ITEM_CELL_2.stack,
            ApplyGrayMetaItems.ECO_ITEM_CELL_3.stack,
            ApplyGrayMetaItems.ECO_ITEM_CELL_4.stack,
            ApplyGrayMetaItems.ECO_ITEM_CELL_5.stack
    };

    private static final MetaItem<?>.MetaValueItem[] FLUID_CELLS = {
            ApplyGrayMetaItems.ECO_FLUID_CELL_1.stack,
            ApplyGrayMetaItems.ECO_FLUID_CELL_2.stack,
            ApplyGrayMetaItems.ECO_FLUID_CELL_3.stack,
            ApplyGrayMetaItems.ECO_FLUID_CELL_4.stack,
            ApplyGrayMetaItems.ECO_FLUID_CELL_5.stack
    };

    private EcoRecipes() {}

    public static void register() {
        registerControllers();
        registerParts();
        registerCells();
    }

    // ## 控制器：参考 PrismPlan 的 LuV 装配线配方

    private static void registerControllers() {
        controller(ApplyGrayMetaTileEntities.ECO_COMPUTER, MetaItems.EMITTER_LuV);
        controller(ApplyGrayMetaTileEntities.ECO_STORAGE, MetaItems.ROBOT_ARM_LuV);
        controller(ApplyGrayMetaTileEntities.ECO_MOLECULAR_ASSEMBLER, MetaItems.CONVEYOR_MODULE_LuV);
        controller(ApplyGrayMetaTileEntities.ECO_ENERGY_STORAGE, MetaItems.SENSOR_LuV);
    }

    private static void controller(MetaTileEntity output, MetaItem<?>.MetaValueItem signature) {
        ASSEMBLY_LINE_RECIPES.recipeBuilder()
                .inputs(MetaBlocks.COMPUTER_CASING.getItemVariant(
                        BlockComputerCasing.CasingType.COMPUTER_CASING, 4))
                .input(frameGt, Materials.StainlessSteel, 4)
                .input(circuit, MarkerMaterial.create(GTValues.VN[ZPM].toLowerCase(Locale.ROOT)), 4)
                .input(MetaItems.FIELD_GENERATOR_LuV, 16)
                .input(signature, 16)
                .input(rotor, Materials.RhodiumPlatedPalladium, 4)
                .input(gear, Materials.Ruridit, 4)
                .input(wireGtDouble, Materials.IndiumTinBariumTitaniumCuprate, 64)
                .fluidInputs(Materials.SolderingAlloy, 1152)
                .fluidInputs(Materials.VanadiumGallium, 1152)
                .fluidInputs(Materials.Polybenzimidazole, 2304)
                .fluidInputs(Materials.NaquadahAlloy, 1440)
                .output(output)
                .duration(1200).EUt(VA[LuV]).buildAndRegister();
    }

    // ## 仓室：五档硬件

    private static void registerParts() {
        for (int i = 0; i < VOLTAGES.length; i++) {
            int voltage = VOLTAGES[i];
            // 每类仓室用一种独占组件做区分：GT 的配方冲突检测按“材料集合”判断，
            // 只改数量仍会被当作同一条配方。
            part(EcoPartRole.MEMORY, i, voltage, 2, SENSORS[i], 2, null);
            part(EcoPartRole.THREAD, i, voltage, 4, EMITTERS[i], 2, null);
            part(EcoPartRole.HYPER_THREAD, i, voltage, 8, FIELD_GENERATORS[i], 2, null);
            part(EcoPartRole.PARALLEL, i, voltage, 4, ROBOT_ARMS[i], 2, null);
            part(EcoPartRole.DRIVE, i, voltage, 2, CONVEYORS[i], 4, null);
            part(EcoPartRole.ENERGY, i, voltage, 1, PUMPS[i], 0, null);
            part(EcoPartRole.WORKER, i, voltage, 8, EMITTERS[i], 4, ROBOT_ARMS[i]);
        }
        part(EcoPartRole.PATTERN_BUS, 0, IV, 4, SENSORS[0], 2, ROBOT_ARMS[0]);
    }

    private static void part(EcoPartRole role, int tier, int voltage, int circuits,
                             MetaItem<?>.MetaValueItem signature, int plates,
                             MetaItem<?>.MetaValueItem extra) {
        var builder = ASSEMBLER_RECIPES.recipeBuilder()
                .input(MetaTileEntities.HULL[voltage], 1)
                .input(circuit, MarkerMaterial.create(GTValues.VN[voltage].toLowerCase(Locale.ROOT)), circuits)
                .input(signature, 2);
        if (extra != null) builder.input(extra, 1);
        if (plates > 0) builder.input(plate, PLATES[tier], plates);
        builder.fluidInputs(Materials.Polybenzimidazole, 576)
                .output(partOutput(role, tier + 1))
                .duration(400).EUt(VA[voltage]).buildAndRegister();
    }

    private static MetaTileEntity partOutput(EcoPartRole role, int tier) {
        int index = tier - 1;
        return switch (role) {
            case MEMORY -> ApplyGrayMetaTileEntities.ECO_MEMORY[index];
            case THREAD -> ApplyGrayMetaTileEntities.ECO_THREAD[index];
            case HYPER_THREAD -> ApplyGrayMetaTileEntities.ECO_HYPER_THREAD[index];
            case PARALLEL -> ApplyGrayMetaTileEntities.ECO_PARALLEL[index];
            case DRIVE -> ApplyGrayMetaTileEntities.ECO_DRIVE[index];
            case ENERGY -> ApplyGrayMetaTileEntities.ECO_ENERGY[index];
            case WORKER -> ApplyGrayMetaTileEntities.ECO_WORKER[index];
            case PATTERN_BUS -> ApplyGrayMetaTileEntities.ECO_PATTERN_BUS;
        };
    }

    // ## 元件：内存 / 物品 / 流体

    private static void registerCells() {
        for (int i = 0; i < VOLTAGES.length; i++) {
            int voltage = VOLTAGES[i];
            CIRCUIT_ASSEMBLER_RECIPES.recipeBuilder()
                    .input(circuit, MarkerMaterial.create(GTValues.VN[voltage].toLowerCase(Locale.ROOT)), 8)
                    .input(FIELD_GENERATORS[i], 2)
                    .input(EMITTERS[i], 2)
                    .input(plate, PLATES[i], 2)
                    .fluidInputs(Materials.Polybenzimidazole, 576)
                    .output(MEMORY_CELLS[i], 1)
                    .duration(400).EUt(VA[voltage]).buildAndRegister();

            CIRCUIT_ASSEMBLER_RECIPES.recipeBuilder()
                    .input(circuit, MarkerMaterial.create(GTValues.VN[voltage].toLowerCase(Locale.ROOT)), 4)
                    .input(EMITTERS[i], 2)
                    .input(ROBOT_ARMS[i], 1)
                    .input(plate, PLATES[i], 2)
                    .fluidInputs(Materials.Polybenzimidazole, 576)
                    .output(ITEM_CELLS[i], 1)
                    .duration(400).EUt(VA[voltage]).buildAndRegister();

            CIRCUIT_ASSEMBLER_RECIPES.recipeBuilder()
                    .input(circuit, MarkerMaterial.create(GTValues.VN[voltage].toLowerCase(Locale.ROOT)), 4)
                    .input(EMITTERS[i], 2)
                    .input(PUMPS[i], 2)
                    .input(plate, PLATES[i], 2)
                    .fluidInputs(Materials.Polybenzimidazole, 576)
                    .output(FLUID_CELLS[i], 1)
                    .duration(400).EUt(VA[voltage]).buildAndRegister();
        }
    }
}
