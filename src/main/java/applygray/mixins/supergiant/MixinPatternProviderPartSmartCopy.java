package applygray.mixins.supergiant;

import java.util.Optional;

import applygray.mattermanipulator.integration.ae2.SmartCopyPatternProviderLink;
import applygray.mattermanipulator.integration.ae2.SmartCopyPatternProviderLinkable;
import applygray.mattermanipulator.integration.ae2.SmartCopyPatternProviderRegistry;

import ae2.api.parts.IPartHost;
import ae2.helpers.patternprovider.PatternProviderLogic;
import ae2.parts.crafting.PatternProviderPart;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Persists a Smart Copy source address on an AE2 Pattern Provider without changing AE2 itself. */
@Mixin(value = PatternProviderPart.class, remap = false)
public abstract class MixinPatternProviderPartSmartCopy implements SmartCopyPatternProviderLinkable {

    @Unique
    private static final String APPLYGRAY_SMART_COPY_KEY = "ApplyGraySmartCopy";

    @Unique
    @Nullable
    private SmartCopyPatternProviderLink applygray$smartCopyLink;

    @Unique
    @Nullable
    private PatternProviderLogic applygray$smartCopySource;

    @Override
    public Optional<SmartCopyPatternProviderLink> applygray$getSmartCopyLink() {
        return Optional.ofNullable(applygray$smartCopyLink);
    }

    @Override
    public boolean applygray$setSmartCopyLink(SmartCopyPatternProviderLink source) {
        if (source == null || applygray$isSourceEndpoint(source)) return false;
        if (source.equals(applygray$smartCopyLink)) return true;

        PatternProviderPart self = applygray$self();
        if (applygray$isServerPart(self)) {
            SmartCopyPatternProviderRegistry.unregister(this);
        }
        applygray$smartCopyLink = source;
        applygray$smartCopySource = applygray$resolveSource(source);
        if (applygray$isServerPart(self)) {
            SmartCopyPatternProviderRegistry.register(source, this);
        }
        applygray$markLinkChanged(self);
        return true;
    }

    @Override
    public void applygray$clearSmartCopyLink() {
        if (applygray$smartCopyLink == null) return;

        PatternProviderPart self = applygray$self();
        if (applygray$isServerPart(self)) {
            SmartCopyPatternProviderRegistry.unregister(this);
        }
        applygray$smartCopyLink = null;
        applygray$smartCopySource = null;
        applygray$markLinkChanged(self);
    }

    @Override
    public @Nullable PatternProviderLogic applygray$getSmartCopySourceLogic() {
        return applygray$smartCopySource;
    }

    @Override
    public void applygray$refreshSmartCopySource() {
        applygray$smartCopySource = applygray$smartCopyLink == null ? null :
                applygray$resolveSource(applygray$smartCopyLink);
        applygray$self().getLogic().updatePatterns();
    }

    @Override
    public void applygray$invalidateSmartCopySource() {
        applygray$smartCopySource = null;
        applygray$self().getLogic().updatePatterns();
    }

    @Inject(method = "readFromNBT", at = @At("TAIL"))
    private void applygray$readSmartCopyLink(NBTTagCompound data, CallbackInfo ci) {
        applygray$smartCopyLink = data.hasKey(APPLYGRAY_SMART_COPY_KEY, Constants.NBT.TAG_COMPOUND)
                ? SmartCopyPatternProviderLink.readFromNbt(data.getCompoundTag(APPLYGRAY_SMART_COPY_KEY))
                : null;
        applygray$smartCopySource = null;
    }

    @Inject(method = "writeToNBT", at = @At("TAIL"))
    private void applygray$writeSmartCopyLink(NBTTagCompound data, CallbackInfo ci) {
        if (applygray$smartCopyLink == null) {
            data.removeTag(APPLYGRAY_SMART_COPY_KEY);
            return;
        }
        NBTTagCompound linkData = new NBTTagCompound();
        applygray$smartCopyLink.writeToNbt(linkData);
        data.setTag(APPLYGRAY_SMART_COPY_KEY, linkData);
    }

    @Inject(method = "addToWorld", at = @At("TAIL"))
    private void applygray$registerSmartCopyLink(CallbackInfo ci) {
        PatternProviderPart self = applygray$self();
        if (!applygray$isServerPart(self)) return;

        if (applygray$smartCopyLink != null) {
            SmartCopyPatternProviderRegistry.register(applygray$smartCopyLink, this);
            applygray$refreshSmartCopySource();
        }
        SmartCopyPatternProviderRegistry.sourceAvailable(self);
    }

    @Unique
    private PatternProviderPart applygray$self() {
        return (PatternProviderPart) (Object) this;
    }

    @Unique
    private @Nullable PatternProviderLogic applygray$resolveSource(SmartCopyPatternProviderLink source) {
        PatternProviderPart candidate = source.resolve();
        if (candidate == null || candidate == applygray$self()) return null;
        if (candidate instanceof SmartCopyPatternProviderLinkable linked &&
                linked.applygray$getSmartCopyLink().isPresent()) {
            return null;
        }
        return candidate.getLogic();
    }

    @Unique
    private boolean applygray$isSourceEndpoint(SmartCopyPatternProviderLink source) {
        PatternProviderPart self = applygray$self();
        World world = self.getLevel();
        EnumFacing side = self.getSide();
        if (world == null || side == null || self.getTileEntity() == null) return false;
        return source.equals(SmartCopyPatternProviderLink.forSource(world, self.getTileEntity().getPos(), side));
    }

    @Unique
    private static boolean applygray$isServerPart(PatternProviderPart part) {
        World world = part.getLevel();
        return world != null && !world.isRemote;
    }

    @Unique
    private static void applygray$markLinkChanged(PatternProviderPart part) {
        IPartHost host = part.getHost();
        if (host != null) {
            host.markForSave();
            host.markForUpdate();
        }
        part.getLogic().updatePatterns();
    }
}
