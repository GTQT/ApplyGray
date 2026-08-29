package applygray.mixins.supergiant;

import applygray.mattermanipulator.integration.ae2.PortableCanerRuntimeStateAccess;

import ae2.api.stacks.AEKey;
import ae2.tile.misc.TileCaner;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Exposes a low-frequency capture guard without making Caner execution state portable. */
@Mixin(value = TileCaner.class, remap = false)
public abstract class MixinTileCanerRuntimeState implements PortableCanerRuntimeStateAccess {

    @Shadow
    private ItemStack target;

    @Shadow
    @Nullable
    private AEKey emptyKey;

    @Override
    public boolean applygray$hasInFlightCanerState() {
        return !target.isEmpty() || emptyKey != null;
    }
}
