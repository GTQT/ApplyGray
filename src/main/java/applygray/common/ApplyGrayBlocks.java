package applygray.common;

import applygray.common.blocks.BlockQuantumStorageUnit;

/**
 * Blocks registered by ApplyGray.
 * <p>
 * Instances are created in {@link #init()} during pre-init and handed to Forge
 * through {@link net.minecraftforge.event.RegistryEvent.Register} handlers in
 * {@link ApplyGrayEventHandler}; item models are wired on the client by
 * {@code applygray.client.QuantumStorageUnitItemModels}.
 */
public final class ApplyGrayBlocks {

    public static BlockQuantumStorageUnit QUANTUM_STORAGE_UNIT;

    private ApplyGrayBlocks() {}

    public static void init() {
        QUANTUM_STORAGE_UNIT = new BlockQuantumStorageUnit();
        QUANTUM_STORAGE_UNIT.setRegistryName("quantum_storage_unit");
    }
}
