package applygray.mixins.supergiant;

import ae2.container.me.patternaccess.PatternAccessSession;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes only the terminal's currently discovered provider ids for server-side validation. */
@Mixin(value = PatternAccessSession.class, remap = false)
public interface AccessorPatternAccessSupport {

    @Accessor("byId")
    Long2ObjectOpenHashMap<?> applygray$getProviderTrackers();
}
