package applygray.mixins.supergiant;

import applygray.integration.ae2.RecipeMapPatternAccessDisplay;

import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityMERecipeMapPatternProvider;

import ae2.api.implementations.blockentities.PatternContainerGroup;
import ae2.client.gui.me.patternaccess.PatternContainerEntry;
import com.google.common.collect.HashMultimap;

import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Identifies the client entry belonging to one zero-slot RecipeMap pattern provider. */
@Mixin(targets = "ae2.client.gui.me.patternaccess.PatternAccessDisplaySupport", remap = false)
public abstract class MixinPatternAccessDisplaySupport implements RecipeMapPatternAccessDisplay {

    @Shadow @Final
    private HashMultimap<PatternContainerGroup, PatternContainerEntry> byGroup;

    @Override
    public long applygray$getRecipeMapPatternProviderId(PatternContainerGroup group) {
        if (!isRecipeMapPatternProviderGroup(group)) {
            return NO_PROVIDER;
        }

        long inventoryId = NO_PROVIDER;
        for (PatternContainerEntry entry : byGroup.get(group)) {
            if (entry.getInventory().size() != 0 || inventoryId != NO_PROVIDER) {
                return NO_PROVIDER;
            }
            inventoryId = entry.getServerId();
        }
        return inventoryId;
    }

    private static boolean isRecipeMapPatternProviderGroup(PatternContainerGroup group) {
        for (ITextComponent line : group.tooltip()) {
            if (line instanceof TextComponentTranslation translation
                    && MetaTileEntityMERecipeMapPatternProvider.TERMINAL_GROUP_TOOLTIP_KEY.equals(translation.getKey())) {
                return true;
            }
        }
        return false;
    }
}
