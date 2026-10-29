package applygray.common;

import applygray.api.ApplyGrayAPI;
import applygray.common.blocks.BlockQuantumStorageUnit;

import gregtech.api.block.VariantItemBlock;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraftforge.registries.IForgeRegistry;

import java.util.Objects;
import java.util.function.Function;

/**
 * Blocks registered by ApplyGray.
 * <p>
 * Instances are created and named in {@link #init()} during pre-init; Forge only ever receives finished instances
 * through {@link #registerBlocks} / {@link #registerItemBlocks}. Item models stay on the client side, in
 * {@code applygray.client.QuantumStorageUnitItemModels}.
 */
public final class ApplyGrayBlocks {

    public static BlockQuantumStorageUnit QUANTUM_STORAGE_UNIT;

    private ApplyGrayBlocks() {}

    public static void init() {
        QUANTUM_STORAGE_UNIT = new BlockQuantumStorageUnit();
        QUANTUM_STORAGE_UNIT.setRegistryName(ApplyGrayAPI.id("quantum_storage_unit"));
    }

    /** 方块注册入口：方块实例集中在这里注册，Proxy 只转发注册事件。 */
    public static void registerBlocks(IForgeRegistry<Block> registry) {
        registry.register(QUANTUM_STORAGE_UNIT);
    }

    /** 方块物品注册入口：ItemBlock 与方块本体集中在这里成对注册。 */
    public static void registerItemBlocks(IForgeRegistry<Item> registry) {
        registry.register(createItemBlock(QUANTUM_STORAGE_UNIT, VariantItemBlock::new));
    }

    private static <T extends Block> ItemBlock createItemBlock(T block, Function<T, ItemBlock> producer) {
        ItemBlock itemBlock = producer.apply(block);
        itemBlock.setRegistryName(Objects.requireNonNull(block.getRegistryName()));
        return itemBlock;
    }
}
