package applygray.common;

import applygray.api.ApplyGrayAPI;
import applygray.ApplyGrayMod;
import applygray.common.metatileentities.multiblock.storage.MetaTileEntityQuantumAccessHatch;
import applygray.common.metatileentities.multiblock.storage.MetaTileEntityQuantumFluidStorage;
import applygray.common.metatileentities.multiblock.storage.MetaTileEntityQuantumItemStorage;
import applygray.mattermanipulator.uplink.MetaTileEntityQuantumUplink;
import applygray.mattermanipulator.uplink.MetaTileEntityQuantumUplinkHatch;

import gregtech.api.GTValues;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityMEDualExportHatch;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityMEDualInputHatch;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityMEGasHatch;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityMEMufflerHatch;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityMEOreDictBus;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityMEOrePrefixPatternProvider;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityMEPatternManager;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityMEPatternProvider;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityMEPatternProviderProxy;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityMERecipeMapPatternProvider;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityMEInputBus;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityMEInputHatch;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityMEOutputBus;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityMEOutputHatch;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityMEStockingBus;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityMEStockingHatch;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityPatternProviderMappingSlave;

import static gregtech.api.GTValues.VN;
import static gregtech.common.metatileentities.MetaTileEntities.registerMetaTileEntity;

/**
 * Registers all ME (Applied Energistics 2) MetaTileEntities for ApplyGray.
 * These are registered through GregTech's current MetaTileEntities API.
 * The same IDs and ResourceLocations are used for backward compatibility with existing worlds.
 */
public class ApplyGrayMetaTileEntities {

    // Basic ME Hatches
    public static MetaTileEntityMEOutputHatch FLUID_EXPORT_HATCH_ME;
    public static MetaTileEntityMEOutputBus ITEM_EXPORT_BUS_ME;
    public static MetaTileEntityMEInputHatch FLUID_IMPORT_HATCH_ME;
    public static MetaTileEntityMEInputBus ITEM_IMPORT_BUS_ME;
    public static MetaTileEntityMEStockingBus STOCKING_BUS_ME;
    public static MetaTileEntityMEStockingHatch STOCKING_HATCH_ME;

    // Extended ME Devices
    public static MetaTileEntityMEDualInputHatch ME_DUAL_IMPORT_HATCH;
    public static MetaTileEntityMEDualExportHatch ME_DUAL_EXPORT_HATCH;
    public static MetaTileEntityMEOreDictBus ME_ORE_DICT_BUS;
    public static MetaTileEntityMEPatternManager ME_PATTERN_MANAGER;
    public static MetaTileEntityMEPatternProviderProxy ME_PATTERN_PROVIDER_PROXY;
    public static MetaTileEntityMEOrePrefixPatternProvider ME_ORE_PREFIX_PATTERN_PROVIDER;
    public static MetaTileEntityMERecipeMapPatternProvider ME_RECIPE_MAP_PATTERN_PROVIDER;

    // ME Pattern Providers
    public static MetaTileEntityMEPatternProvider[] ME_PATTERN_PROVIDER = new MetaTileEntityMEPatternProvider[GTValues.V.length - 1 - GTValues.HV];

    // Pattern Mapping Slaves
    public static MetaTileEntityPatternProviderMappingSlave[] PATTERN_MAPPING_SLAVE = new MetaTileEntityPatternProviderMappingSlave[GTValues.V.length - 1 - GTValues.HV];

    // ME Muffler Hatches
    public static final MetaTileEntityMEMufflerHatch[] ME_MUFFLER_HATCH = new MetaTileEntityMEMufflerHatch[GTValues.UHV + 1];

    // ME Gas Hatches
    public static final MetaTileEntityMEGasHatch[] ME_GAS_HATCH = new MetaTileEntityMEGasHatch[GTValues.V.length - 1];

    // Matter Manipulator Quantum Uplink
    public static MetaTileEntityQuantumUplink QUANTUM_UPLINK;
    public static MetaTileEntityQuantumUplinkHatch QUANTUM_UPLINK_ME_HATCH;

    // Quantum Storage
    public static MetaTileEntityQuantumItemStorage QUANTUM_ITEM_STORAGE;
    public static MetaTileEntityQuantumFluidStorage QUANTUM_FLUID_STORAGE;
    public static MetaTileEntityQuantumAccessHatch QUANTUM_ACCESS_HATCH;

    public static void init() {
        registerMEHatches();
    }

    private static void registerMEHatches() {
        // ME Hatches, IDs 0-
        FLUID_EXPORT_HATCH_ME = registerMetaTileEntity(0,
                new MetaTileEntityMEOutputHatch(ApplyGrayAPI.id("me_export_fluid_hatch")));
        ITEM_EXPORT_BUS_ME = registerMetaTileEntity(1,
                new MetaTileEntityMEOutputBus(ApplyGrayAPI.id("me_export_item_bus")));
        FLUID_IMPORT_HATCH_ME = registerMetaTileEntity(2,
                new MetaTileEntityMEInputHatch(ApplyGrayAPI.id("me_import_fluid_hatch"), GTValues.EV));
        ITEM_IMPORT_BUS_ME = registerMetaTileEntity(3,
                new MetaTileEntityMEInputBus(ApplyGrayAPI.id("me_import_item_bus"), GTValues.EV));
        STOCKING_BUS_ME = registerMetaTileEntity(4,
                new MetaTileEntityMEStockingBus(ApplyGrayAPI.id("me_stocking_item_bus"), GTValues.IV));
        STOCKING_HATCH_ME = registerMetaTileEntity(5,
                new MetaTileEntityMEStockingHatch(ApplyGrayAPI.id("me_stocking_fluid_hatch"), GTValues.IV));

        // ME Devices, IDs 10-
        ME_DUAL_IMPORT_HATCH = registerMetaTileEntity(10,
                new MetaTileEntityMEDualInputHatch(ApplyGrayAPI.id("me_dual_hatch.import")));
        ME_DUAL_EXPORT_HATCH = registerMetaTileEntity(11,
                new MetaTileEntityMEDualExportHatch(ApplyGrayAPI.id("me_dual_hatch.export")));
        ME_ORE_DICT_BUS = registerMetaTileEntity(12,
                new MetaTileEntityMEOreDictBus(ApplyGrayAPI.id("me_ore_dict_bus"), GTValues.IV));
        ME_PATTERN_MANAGER = registerMetaTileEntity(13,
                new MetaTileEntityMEPatternManager(ApplyGrayAPI.id("me_pattern_manager"), GTValues.UV, false));
        ME_PATTERN_PROVIDER_PROXY = registerMetaTileEntity(14,
                new MetaTileEntityMEPatternProviderProxy(ApplyGrayAPI.id("me_pattern_provider_proxy"), GTValues.UHV));
        ME_ORE_PREFIX_PATTERN_PROVIDER = registerMetaTileEntity(15,
                new MetaTileEntityMEOrePrefixPatternProvider(ApplyGrayAPI.id("me_ore_prefix_pattern_provider"), GTValues.UHV));
        ME_RECIPE_MAP_PATTERN_PROVIDER = registerMetaTileEntity(16,
                new MetaTileEntityMERecipeMapPatternProvider(ApplyGrayAPI.id("me_recipe_map_pattern_provider"), GTValues.UHV));

        // ME Pattern Providers, IDs 20+
        for (int i = 0; i < ME_PATTERN_PROVIDER.length; i++) {
            int tier = GTValues.HV + i;
            String voltageName = VN[tier].toLowerCase();
            ME_PATTERN_PROVIDER[i] = new MetaTileEntityMEPatternProvider(
                    ApplyGrayAPI.id("me_pattern_provider." + voltageName), tier);
            registerMetaTileEntity(20 + i, ME_PATTERN_PROVIDER[i]);
        }

        // Pattern Mapping Slaves, IDs 35+
        for (int i = 0; i < PATTERN_MAPPING_SLAVE.length; i++) {
            int tier = GTValues.HV + i;
            String voltageName = VN[tier].toLowerCase();
            PATTERN_MAPPING_SLAVE[i] = new MetaTileEntityPatternProviderMappingSlave(
                    ApplyGrayAPI.id("pattern_mapping_slave." + voltageName), tier);
            registerMetaTileEntity(35 + i, PATTERN_MAPPING_SLAVE[i]);
        }

        // ME Muffler Hatches, IDs 50-
        for (int i = 0; i < ME_MUFFLER_HATCH.length; i++) {
            int tier = i + 1;
            String voltageName = VN[tier].toLowerCase();
            ME_MUFFLER_HATCH[i] = new MetaTileEntityMEMufflerHatch(ApplyGrayAPI.id("me_muffler_hatch." + voltageName), tier);
            registerMetaTileEntity(50 + i, ME_MUFFLER_HATCH[i]);
        }

        // ME Gas Hatches, IDs 65-
        for (int i = 0; i < ME_GAS_HATCH.length; i++) {
            int tier = i + 1;
            String voltageName = VN[tier].toLowerCase();
            ME_GAS_HATCH[i] = new MetaTileEntityMEGasHatch(ApplyGrayAPI.id("me_gas_hatch." + voltageName), tier);
            registerMetaTileEntity(65 + i, ME_GAS_HATCH[i]);
        }

        // Quantum Storage, IDs 100-
        QUANTUM_ITEM_STORAGE = registerMetaTileEntity(100,
                new MetaTileEntityQuantumItemStorage(ApplyGrayAPI.id("quantum_storage.chest")));
        QUANTUM_FLUID_STORAGE = registerMetaTileEntity(101,
                new MetaTileEntityQuantumFluidStorage(ApplyGrayAPI.id("quantum_storage.tank")));
        QUANTUM_ACCESS_HATCH = registerMetaTileEntity(102,
                new MetaTileEntityQuantumAccessHatch(ApplyGrayAPI.id("quantum_access_hatch")));
        QUANTUM_UPLINK = registerMetaTileEntity(103,
                new MetaTileEntityQuantumUplink(ApplyGrayAPI.id("matter_manipulator.quantum_uplink")));
        QUANTUM_UPLINK_ME_HATCH = registerMetaTileEntity(104,
                new MetaTileEntityQuantumUplinkHatch(ApplyGrayAPI.id("matter_manipulator.quantum_uplink_hatch")));
    }
}
