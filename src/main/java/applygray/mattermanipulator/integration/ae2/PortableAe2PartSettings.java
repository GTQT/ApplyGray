package applygray.mattermanipulator.integration.ae2;

import net.minecraft.nbt.NBTTagCompound;

/** Mixin bridge for resource-free AE2 Memory Card settings. */
public interface PortableAe2PartSettings {

    NBTTagCompound applygray$exportPortableSettings();

    void applygray$importPortableSettings(NBTTagCompound settings);
}
