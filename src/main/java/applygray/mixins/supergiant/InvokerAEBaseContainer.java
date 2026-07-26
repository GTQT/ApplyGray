package applygray.mixins.supergiant;

import ae2.container.AEBaseContainer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.function.Consumer;

/** Reuses AE2's validated GUI action transport for the RecipeMap-provider clear action. */
@Mixin(value = AEBaseContainer.class, remap = false)
public interface InvokerAEBaseContainer {

    @Invoker("registerClientAction")
    <T> void applygray$registerClientAction(String action, Class<T> argumentType, Consumer<T> handler);

    @Invoker("sendClientAction")
    <T> void applygray$sendClientAction(String action, T argument);
}
