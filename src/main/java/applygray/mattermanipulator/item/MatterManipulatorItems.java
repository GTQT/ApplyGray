package applygray.mattermanipulator.item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import applygray.mattermanipulator.state.ManipulatorTier;
import applygray.mattermanipulator.state.ManipulatorUpgrade;
import applygray.mattermanipulator.integration.ae2.Ae2ManipulatorLinkHandler;

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

    private static final Map<ManipulatorComponent, ItemManipulatorComponent> COMPONENTS = createComponents();
    private static final List<Item> ALL_ITEMS = createAllItems();

    private MatterManipulatorItems() {}

    public static ItemManipulatorComponent component(ManipulatorComponent component) {
        return COMPONENTS.get(component);
    }

    public static List<Item> allItems() {
        return ALL_ITEMS;
    }

    public static void register(IForgeRegistry<Item> registry) {
        ALL_ITEMS.forEach(registry::register);
        Ae2ManipulatorLinkHandler.register(MK0);
        Ae2ManipulatorLinkHandler.register(MK1);
        Ae2ManipulatorLinkHandler.register(MK2);
        Ae2ManipulatorLinkHandler.register(MK3);
    }

    private static Map<ManipulatorComponent, ItemManipulatorComponent> createComponents() {
        EnumMap<ManipulatorComponent, ItemManipulatorComponent> components =
                new EnumMap<>(ManipulatorComponent.class);
        for (ManipulatorComponent component : ManipulatorComponent.values()) {
            components.put(component, new ItemManipulatorComponent(component));
        }
        return Collections.unmodifiableMap(components);
    }

    private static List<Item> createAllItems() {
        List<Item> items = new ArrayList<>();
        items.add(MK0);
        items.add(MK1);
        items.add(MK2);
        items.add(MK3);
        items.addAll(COMPONENTS.values());
        items.add(POWER_P2P_UPGRADE);
        items.add(MINING_UPGRADE);
        items.add(SPEED_UPGRADE);
        items.add(POWER_EFFICIENCY_UPGRADE);
        return Collections.unmodifiableList(items);
    }
}
