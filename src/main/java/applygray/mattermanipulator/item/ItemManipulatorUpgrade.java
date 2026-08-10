package applygray.mattermanipulator.item;

import applygray.api.ApplyGrayAPI;
import applygray.mattermanipulator.state.ManipulatorUpgrade;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;

/** A concrete target item for a Matter Manipulator upgrade recipe or UI action. */
public final class ItemManipulatorUpgrade extends Item {

    private final ManipulatorUpgrade upgrade;

    ItemManipulatorUpgrade(ManipulatorUpgrade upgrade) {
        this.upgrade = upgrade;
        String path = "matter_manipulator_upgrade_" + upgrade.name().toLowerCase();
        setCreativeTab(CreativeTabs.TOOLS);
        setRegistryName(ApplyGrayAPI.id(path));
        setTranslationKey(ApplyGrayAPI.MODID + '.' + path);
    }

    public ManipulatorUpgrade upgrade() {
        return upgrade;
    }
}
