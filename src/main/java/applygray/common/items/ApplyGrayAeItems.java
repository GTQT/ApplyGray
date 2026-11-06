package applygray.common.items;

import applygray.api.ApplyGrayAPI;
import applygray.integration.ae2.encoding.ExtendedEncodingTermPart;
import ae2.api.parts.PartModels;
import ae2.api.upgrades.Upgrades;
import ae2.core.MainCreativeTab;
import ae2.core.definitions.AEBlocks;
import ae2.core.definitions.AEParts;
import ae2.core.localization.GuiText;
import ae2.items.parts.PartItem;
import ae2.items.parts.PartModelsHelper;

import net.minecraft.item.Item;
import net.minecraftforge.registries.IForgeRegistry;

/**
 * AE2 物品：升级卡与扩展样板编码终端部件。
 * <p>
 * 二者都必须是真实 {@link Item} 实例，不能走 {@code MetaItems}：AE2 通过
 * {@code instanceof UpgradeCardItem} 识别升级卡，通过 {@code IPartItem} 创建部件。
 */
public final class ApplyGrayAeItems {

    public static final Item CIRCUIT_CARD = createCircuitCard();

    public static final PartItem<ExtendedEncodingTermPart> EXTENDED_ENCODING_TERM = createExtendedEncodingTerm();

    private ApplyGrayAeItems() {}

    public static void register(IForgeRegistry<Item> registry) {
        registry.register(CIRCUIT_CARD);
        registry.register(EXTENDED_ENCODING_TERM);
    }

    /** 隶属关系必须在 AE2 冻结升级槽之前写入，因此放在 postInit。 */
    public static void registerUpgradeAssociations() {
        String group = GuiText.CraftingInterface.getTranslationKey();
        Upgrades.add(CIRCUIT_CARD, AEBlocks.PATTERN_PROVIDER.item(), 1, group);
        Upgrades.add(CIRCUIT_CARD, AEParts.PATTERN_PROVIDER.item(), 1, group);
    }

    private static Item createCircuitCard() {
        Item card = Upgrades.createUpgradeCardItem();
        card.setCreativeTab(MainCreativeTab.INSTANCE);
        card.setRegistryName(ApplyGrayAPI.id("circuit_card"));
        card.setTranslationKey(ApplyGrayAPI.MODID + ".circuit_card");
        return card;
    }

    private static PartItem<ExtendedEncodingTermPart> createExtendedEncodingTerm() {
        PartModels.registerModels(PartModelsHelper.createModels(ExtendedEncodingTermPart.class));
        PartItem<ExtendedEncodingTermPart> item =
                new PartItem<>(ExtendedEncodingTermPart.class, ExtendedEncodingTermPart::new);
        item.setCreativeTab(MainCreativeTab.INSTANCE);
        item.setRegistryName(ApplyGrayAPI.id("extended_encoding_terminal"));
        item.setTranslationKey(ApplyGrayAPI.MODID + ".extended_encoding_terminal");
        return item;
    }
}
