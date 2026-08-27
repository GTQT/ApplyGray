package applygray.mixins.supergiant;

import applygray.mattermanipulator.integration.ae2.PortableAe2PartSettings;

import ae2.api.parts.IPart;
import ae2.parts.AEBasePart;
import ae2.util.SettingsFrom;
import net.minecraft.nbt.NBTTagCompound;
import org.spongepowered.asm.mixin.Mixin;

/** Exposes player-independent Memory Card settings while leaving resource inventories to ApplyGray transactions. */
@Mixin(value = AEBasePart.class, remap = false)
public abstract class MixinAEBasePartPortableSettings implements PortableAe2PartSettings {

    private static final String EXPORTED_UPGRADES = "exported_upgrades";

    @Override
    public NBTTagCompound applygray$exportPortableSettings() {
        NBTTagCompound settings = new NBTTagCompound();
        ((IPart) (Object) this).exportSettings(SettingsFrom.MEMORY_CARD, settings);
        settings.removeTag(EXPORTED_UPGRADES);
        return settings;
    }

    @Override
    public void applygray$importPortableSettings(NBTTagCompound settings) {
        ((IPart) (Object) this).importSettings(SettingsFrom.MEMORY_CARD, settings.copy(), null);
    }
}
