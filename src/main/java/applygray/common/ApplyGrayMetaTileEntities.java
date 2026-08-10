package applygray.common;

import applygray.api.ApplyGrayAPI;
import applygray.ApplyGrayMod;
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

    // Basic ME Hatches (IDs 1900-1905)
    public static MetaTileEntityMEOutputHatch FLUID_EXPORT_HATCH_ME;
    public static MetaTileEntityMEOutputBus ITEM_EXPORT_BUS_ME;
    public static MetaTileEntityMEInputHatch FLUID_IMPORT_HATCH_ME;
    public static MetaTileEntityMEInputBus ITEM_IMPORT_BUS_ME;
    public static MetaTileEntityMEStockingBus STOCKING_BUS_ME;
    public static MetaTileEntityMEStockingHatch STOCKING_HATCH_ME;

    // Extended ME Devices (IDs 2700-2705)
    public static MetaTileEntityMEDualInputHatch ME_DUAL_IMPORT_HATCH;
    public static MetaTileEntityMEDualExportHatch ME_DUAL_EXPORT_HATCH;
    public static MetaTileEntityMEOreDictBus ME_ORE_DICT_BUS;
    public static MetaTileEntityMEPatternManager ME_PATTERN_MANAGER;
    public static MetaTileEntityMEPatternProviderProxy ME_PATTERN_PROVIDER_PROXY;
    public static MetaTileEntityMEOrePrefixPatternProvider ME_ORE_PREFIX_PATTERN_PROVIDER;
    public static MetaTileEntityMERecipeMapPatternProvider ME_RECIPE_MAP_PATTERN_PROVIDER;

    // ME Pattern Providers (IDs 2710+)
    public static MetaTileEntityMEPatternProvider[] ME_PATTERN_PROVIDER = new MetaTileEntityMEPatternProvider[GTValues.V.length - 1 - GTValues.HV];

    // Pattern Mapping Slaves (IDs 2730+)
    public static MetaTileEntityPatternProviderMappingSlave[] PATTERN_MAPPING_SLAVE = new MetaTileEntityPatternProviderMappingSlave[GTValues.V.length - 1 - GTValues.HV];

    // ME Muffler Hatches (IDs 2875-2890, LV-UHV)
    public static final MetaTileEntityMEMufflerHatch[] ME_MUFFLER_HATCH = new MetaTileEntityMEMufflerHatch[GTValues.UHV + 1];

    // ME Gas Hatches (IDs 2890-2905)
    public static final MetaTileEntityMEGasHatch[] ME_GAS_HATCH = new MetaTileEntityMEGasHatch[GTValues.V.length - 1];

    // Matter Manipulator Quantum Uplink (IDs 5000-5001)
    public static MetaTileEntityQuantumUplink QUANTUM_UPLINK;
    public static MetaTileEntityQuantumUplinkHatch QUANTUM_UPLINK_ME_HATCH;

    public static void init() {
        registerMEHatches();
        registerExtendedMEDevices();
        registerMatterManipulatorUplink();
        ApplyGrayMod.LOGGER.info("Registered {} AE-enabled GregTech machines",
                15 + ME_PATTERN_PROVIDER.length + PATTERN_MAPPING_SLAVE.length +
                        ME_MUFFLER_HATCH.length + ME_GAS_HATCH.length);
    }

    private static void registerMEHatches() {
        // ME Hatches, IDs 1900-1905
        FLUID_EXPORT_HATCH_ME = registerMetaTileEntity(1900,
                new MetaTileEntityMEOutputHatch(ApplyGrayAPI.id("me_export_fluid_hatch")));
        ITEM_EXPORT_BUS_ME = registerMetaTileEntity(1901,
                new MetaTileEntityMEOutputBus(ApplyGrayAPI.id("me_export_item_bus")));
        FLUID_IMPORT_HATCH_ME = registerMetaTileEntity(1902,
                new MetaTileEntityMEInputHatch(ApplyGrayAPI.id("me_import_fluid_hatch"), GTValues.EV));
        ITEM_IMPORT_BUS_ME = registerMetaTileEntity(1903,
                new MetaTileEntityMEInputBus(ApplyGrayAPI.id("me_import_item_bus"), GTValues.EV));
        STOCKING_BUS_ME = registerMetaTileEntity(1904,
                new MetaTileEntityMEStockingBus(ApplyGrayAPI.id("me_stocking_item_bus"), GTValues.IV));
        STOCKING_HATCH_ME = registerMetaTileEntity(1905,
                new MetaTileEntityMEStockingHatch(ApplyGrayAPI.id("me_stocking_fluid_hatch"), GTValues.IV));
    }

    private static void registerExtendedMEDevices() {
        // ME Devices, IDs 2700-2705
        ME_DUAL_IMPORT_HATCH = new MetaTileEntityMEDualInputHatch(ApplyGrayAPI.id("me_dual_hatch.import"));
        ME_DUAL_EXPORT_HATCH = new MetaTileEntityMEDualExportHatch(ApplyGrayAPI.id("me_dual_hatch.export"));
        ME_ORE_DICT_BUS = new MetaTileEntityMEOreDictBus(ApplyGrayAPI.id("me_ore_dict_bus"), GTValues.IV);

        ME_PATTERN_MANAGER = new MetaTileEntityMEPatternManager(ApplyGrayAPI.id("me_pattern_manager"), GTValues.UV, false);
        ME_ORE_PREFIX_PATTERN_PROVIDER = new MetaTileEntityMEOrePrefixPatternProvider(ApplyGrayAPI.id("me_ore_prefix_pattern_provider"), GTValues.UHV);
        ME_PATTERN_PROVIDER_PROXY = new MetaTileEntityMEPatternProviderProxy(ApplyGrayAPI.id("me_pattern_provider_proxy"), GTValues.UHV);
        ME_RECIPE_MAP_PATTERN_PROVIDER = new MetaTileEntityMERecipeMapPatternProvider(
                ApplyGrayAPI.id("me_recipe_map_pattern_provider"), GTValues.UHV);

        registerMetaTileEntity(2700, ME_DUAL_IMPORT_HATCH);
        registerMetaTileEntity(2701, ME_DUAL_EXPORT_HATCH);
        registerMetaTileEntity(2702, ME_ORE_DICT_BUS);
        registerMetaTileEntity(2703, ME_PATTERN_MANAGER);
        registerMetaTileEntity(2704, ME_PATTERN_PROVIDER_PROXY);
        registerMetaTileEntity(2705, ME_ORE_PREFIX_PATTERN_PROVIDER);
        registerMetaTileEntity(2706, ME_RECIPE_MAP_PATTERN_PROVIDER);

        // ME Pattern Providers, IDs 2710+
        for (int i = 0; i < ME_PATTERN_PROVIDER.length; i++) {
            int tier = GTValues.HV + i;
            String voltageName = VN[tier].toLowerCase();
            ME_PATTERN_PROVIDER[i] = new MetaTileEntityMEPatternProvider(
                    ApplyGrayAPI.id("me_pattern_provider." + voltageName), tier);
            registerMetaTileEntity(2710 + i, ME_PATTERN_PROVIDER[i]);
        }

        // Pattern Mapping Slaves, IDs 2730+
        for (int i = 0; i < PATTERN_MAPPING_SLAVE.length; i++) {
            int tier = GTValues.HV + i;
            String voltageName = VN[tier].toLowerCase();
            PATTERN_MAPPING_SLAVE[i] = new MetaTileEntityPatternProviderMappingSlave(
                    ApplyGrayAPI.id("pattern_mapping_slave." + voltageName), tier);
            registerMetaTileEntity(2730 + i, PATTERN_MAPPING_SLAVE[i]);
        }

        // ME Muffler Hatches, IDs 2875-2890
        for (int i = 0; i < ME_MUFFLER_HATCH.length; i++) {
            int tier = i + 1;
            String voltageName = VN[tier].toLowerCase();
            ME_MUFFLER_HATCH[i] = new MetaTileEntityMEMufflerHatch(ApplyGrayAPI.id("me_muffler_hatch." + voltageName), tier);
            registerMetaTileEntity(2875 + i, ME_MUFFLER_HATCH[i]);
        }

        // ME Gas Hatches, IDs 2890-2905
        for (int i = 0; i < ME_GAS_HATCH.length; i++) {
            int tier = i + 1;
            String voltageName = VN[tier].toLowerCase();
            ME_GAS_HATCH[i] = new MetaTileEntityMEGasHatch(ApplyGrayAPI.id("me_gas_hatch." + voltageName), tier);
            registerMetaTileEntity(2890 + i, ME_GAS_HATCH[i]);
        }
    }

    private static void registerMatterManipulatorUplink() {
        QUANTUM_UPLINK = registerMetaTileEntity(5000,
                new MetaTileEntityQuantumUplink(ApplyGrayAPI.id("matter_manipulator.quantum_uplink")));
        QUANTUM_UPLINK_ME_HATCH = registerMetaTileEntity(5001,
                new MetaTileEntityQuantumUplinkHatch(ApplyGrayAPI.id("matter_manipulator.quantum_uplink_hatch")));
    }
}
