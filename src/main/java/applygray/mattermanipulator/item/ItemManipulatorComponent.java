package applygray.mattermanipulator.item;

import applygray.api.ApplyGrayAPI;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;

/** One target-native item for an original Matter Manipulator crafting component. */
public final class ItemManipulatorComponent extends Item {

    private final ManipulatorComponent component;

    ItemManipulatorComponent(ManipulatorComponent component) {
        this.component = component;
        setCreativeTab(CreativeTabs.TOOLS);
        setRegistryName(ApplyGrayAPI.id(component.registryPath()));
        setTranslationKey(ApplyGrayAPI.MODID + '.' + component.registryPath());
    }

    public ManipulatorComponent component() {
        return component;
    }
}
