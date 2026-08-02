package gregtech.common.metatileentities.multi.multiblockpart.appeng;

import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternBufferItemSlotValidatorTest {

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        Bootstrap.register();
    }

    @Test
    void acceptsOnlyTheConfiguredItemType() {
        ItemStack expected = new ItemStack(Items.DIAMOND);

        assertTrue(PatternBufferItemSlotValidator.accepts(expected, new ItemStack(Items.DIAMOND)));
        assertFalse(PatternBufferItemSlotValidator.accepts(expected, new ItemStack(Items.IRON_INGOT)));
    }
}
