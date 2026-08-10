package applygray.mattermanipulator.item;

import applygray.mattermanipulator.state.ManipulatorTier;
import applygray.mattermanipulator.state.ManipulatorUpgrade;

import net.minecraft.item.Item;
import net.minecraftforge.registries.IForgeRegistry;

/** Registers the standalone Matter Manipulator items through ApplyGray's normal Forge registry event. */
public final class MatterManipulatorItems {

    public static final ItemMatterManipulator MK0 = new ItemMatterManipulator(ManipulatorTier.MK0);
    public static final ItemMatterManipulator MK1 = new ItemMatterManipulator(ManipulatorTier.MK1);
    public static final ItemMatterManipulator MK2 = new ItemMatterManipulator(ManipulatorTier.MK2);
    public static final ItemMatterManipulator MK3 = new ItemMatterManipulator(ManipulatorTier.MK3);

    public static final ItemManipulatorUpgrade POWER_P2P_UPGRADE = new ItemManipulatorUpgrade(ManipulatorUpgrade.POWER_P2P);
    public static final ItemManipulatorUpgrade MINING_UPGRADE = new ItemManipulatorUpgrade(ManipulatorUpgrade.MINING);
    public static final ItemManipulatorUpgrade SPEED_UPGRADE = new ItemManipulatorUpgrade(ManipulatorUpgrade.SPEED);
    public static final ItemManipulatorUpgrade POWER_EFFICIENCY_UPGRADE =
            new ItemManipulatorUpgrade(ManipulatorUpgrade.POWER_EFFICIENCY);

    private MatterManipulatorItems() {}

    public static void register(IForgeRegistry<Item> registry) {
        registry.register(MK0);
        registry.register(MK1);
        registry.register(MK2);
        registry.register(MK3);
        registry.register(POWER_P2P_UPGRADE);
        registry.register(MINING_UPGRADE);
        registry.register(SPEED_UPGRADE);
        registry.register(POWER_EFFICIENCY_UPGRADE);
    }
}
