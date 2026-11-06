package applygray.mixins.supergiant;

import ae2.api.AECapabilities;
import ae2.api.crafting.IPatternDetails;
import ae2.api.implementations.blockentities.ICraftingMachine;
import ae2.api.implementations.blockentities.IPatternProviderBatchTarget;
import ae2.api.networking.crafting.ICraftingProvider;
import ae2.api.stacks.KeyCounter;
import ae2.api.upgrades.IUpgradeInventory;
import ae2.helpers.patternprovider.PatternProviderLogic;
import ae2.helpers.patternprovider.PatternProviderLogicHost;
import ae2.helpers.patternprovider.PatternProviderTarget;
import ae2.parts.p2p.PatternProviderP2PTunnelPart;
import ae2.util.inv.InternalInventoryHost;
import applygray.common.items.ApplyGrayAeItems;
import applygray.integration.ae2.circuit.GregTechCircuitPatternDetails;
import it.unimi.dsi.fastutil.objects.ObjectList;
import meowmel.structurelib.integration.ae2.PatternDetailsAcceptor;
import gregtech.api.capability.IGhostSlotConfigurable;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

@Mixin(value = PatternProviderLogic.class, remap = false)
public abstract class MixinPatternProviderLogic
    implements InternalInventoryHost, ICraftingProvider, PatternDetailsAcceptor {

    @Shadow
    @Final
    private IUpgradeInventory upgrades;

    @Shadow
    @Final
    private PatternProviderLogicHost host;

    @Shadow
    private PatternProviderTarget findAdapter(EnumFacing side) {
        throw new AssertionError();
    }

    @Unique
    private int applygray$currentCircuitConfig = -1;

    /**
     * StructureLib's ownership check redirect asks this provider whether a rewritten pattern view (the circuit
     * filtered patterns returned by {@link #applygray$filterCircuitPatternsForCraftingPlan}) still designates a
     * stored pattern. Bending {@code equals} of the wrapper instead would make equality asymmetric for every
     * other caller.
     */
    @Override
    public boolean applygray$acceptPattern(ObjectList<?> storedPatterns, Object candidate) {
        return GregTechCircuitPatternDetails.containsPattern(storedPatterns, candidate);
    }

    @Inject(
        method = "getAvailablePatterns",
        at = @At("RETURN"),
        cancellable = true
    )
    private void applygray$filterCircuitPatternsForCraftingPlan(CallbackInfoReturnable<List<IPatternDetails>> cir) {
        if (!this.applygray$hasCircuitCard()) {
            return;
        }

        List<IPatternDetails> availablePatterns = cir.getReturnValue();
        if (availablePatterns.isEmpty()) {
            return;
        }

        boolean changed = false;
        List<IPatternDetails> filteredPatterns = new ArrayList<>(availablePatterns.size());
        for (IPatternDetails patternDetails : availablePatterns) {
            IPatternDetails filteredPattern = this.applygray$filterCircuitPattern(patternDetails);
            filteredPatterns.add(filteredPattern);
            changed |= filteredPattern != patternDetails;
        }
        if (changed) {
            cir.setReturnValue(filteredPatterns);
        }
    }

    @Inject(
        method = "pushPattern",
        at = @At("HEAD")
    )
    private void applygray$captureCircuitConfiguration(
        IPatternDetails patternDetails,
        KeyCounter[] inputHolder,
        int multiplier,
        CallbackInfoReturnable<Boolean> cir
    ) {
        this.applygray$captureCircuitConfiguration(patternDetails);
    }

    @Inject(
        method = "getMaxPatternPushMultiplier",
        at = @At("HEAD")
    )
    private void applygray$captureCircuitConfigurationForMergePush(
        IPatternDetails patternDetails,
        int maxMultiplier,
        CallbackInfoReturnable<Integer> cir
    ) {
        this.applygray$captureCircuitConfiguration(patternDetails);
    }

    @Inject(
        method = "getMaxPatternPushMultiplierThroughP2P",
        at = @At("HEAD")
    )
    private void applygray$captureCircuitConfigurationForP2PMergePush(
        PatternProviderP2PTunnelPart inputTunnel,
        IPatternDetails patternDetails,
        KeyCounter[] inputs,
        int maxMultiplier,
        CallbackInfoReturnable<Integer> cir
    ) {
        this.applygray$captureCircuitConfiguration(patternDetails);
    }

    @Inject(
        method = "pushPatternThroughP2P",
        at = @At("HEAD")
    )
    private void applygray$captureCircuitConfigurationForP2PPush(
        PatternProviderP2PTunnelPart inputTunnel,
        IPatternDetails patternDetails,
        KeyCounter[] inputHolder,
        int multiplier,
        CallbackInfoReturnable<Boolean> cir
    ) {
        this.applygray$captureCircuitConfiguration(patternDetails);
    }

    @Unique
    private void applygray$captureCircuitConfiguration(IPatternDetails patternDetails) {
        this.applygray$currentCircuitConfig = -1;
        boolean hasCircuitCard = this.applygray$hasCircuitCard();
        OptionalInt configuration;
        if (hasCircuitCard) {
            configuration = GregTechCircuitPatternDetails.findCircuitConfiguration(patternDetails);
            if (configuration.isPresent()) {
                this.applygray$currentCircuitConfig = configuration.getAsInt();
                GregTechCircuitPatternDetails.setActiveCircuitConfiguration(this.applygray$currentCircuitConfig);
                return;
            }
        }
        GregTechCircuitPatternDetails.clearActiveCircuitConfiguration();
    }

    @Inject(
        method = "pushPattern",
        at = @At("RETURN")
    )
    private void applygray$clearCircuitConfiguration(
        IPatternDetails patternDetails,
        KeyCounter[] inputHolder,
        int multiplier,
        CallbackInfoReturnable<Boolean> cir
    ) {
        this.applygray$currentCircuitConfig = -1;
        GregTechCircuitPatternDetails.clearActiveCircuitConfiguration();
    }

    @Inject(
        method = "getMaxPatternPushMultiplier",
        at = @At("RETURN")
    )
    private void applygray$clearCircuitConfigurationForMergePush(
        IPatternDetails patternDetails,
        int maxMultiplier,
        CallbackInfoReturnable<Integer> cir
    ) {
        this.applygray$currentCircuitConfig = -1;
        GregTechCircuitPatternDetails.clearActiveCircuitConfiguration();
    }

    @Inject(
        method = "getMaxPatternPushMultiplierThroughP2P",
        at = @At("RETURN")
    )
    private void applygray$clearCircuitConfigurationForP2PMergePush(
        PatternProviderP2PTunnelPart inputTunnel,
        IPatternDetails patternDetails,
        KeyCounter[] inputs,
        int maxMultiplier,
        CallbackInfoReturnable<Integer> cir
    ) {
        this.applygray$currentCircuitConfig = -1;
        GregTechCircuitPatternDetails.clearActiveCircuitConfiguration();
    }

    @Inject(
        method = "pushPatternThroughP2P",
        at = @At("RETURN")
    )
    private void applygray$clearCircuitConfigurationForP2PPush(
        PatternProviderP2PTunnelPart inputTunnel,
        IPatternDetails patternDetails,
        KeyCounter[] inputHolder,
        int multiplier,
        CallbackInfoReturnable<Boolean> cir
    ) {
        this.applygray$currentCircuitConfig = -1;
        GregTechCircuitPatternDetails.clearActiveCircuitConfiguration();
    }

    @Redirect(
        method = {
            "pushPattern",
            "canMergePatternPush",
            "getMaxPatternPushMultiplier",
            "getMaxPatternPushMultiplierThroughP2P",
            "pushPatternThroughP2P"
        },
        at = @At(
            value = "INVOKE",
            target = "Lae2/helpers/patternprovider/PseudoPatternDetails;unwrap(Lae2/api/crafting/IPatternDetails;)Lae2/api/crafting/IPatternDetails;"
        )
    )
    private IPatternDetails applygray$unwrapCircuitPattern(IPatternDetails details) {
        return GregTechCircuitPatternDetails.unwrapPseudo(details);
    }

    @Redirect(
        method = "collectPushTargets",
        at = @At(
            value = "INVOKE",
            target = "Lae2/api/implementations/blockentities/ICraftingMachine;of(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/EnumFacing;)Lae2/api/implementations/blockentities/ICraftingMachine;"
        )
    )
    private ICraftingMachine applygray$collectCraftingMachine(World level, BlockPos pos, EnumFacing side) {
        TileEntity tileEntity = level.getTileEntity(pos);
        ICraftingMachine machine = ICraftingMachine.of(tileEntity, side);
        if (machine == null || this.applygray$currentCircuitConfig < 0) {
            return machine;
        }

        IGhostSlotConfigurable ghostTarget = GregTechCircuitPatternDetails.findGhostCircuitTarget(tileEntity, machine);
        if (ghostTarget == null) {
            return machine;
        }

        return GregTechCircuitPatternDetails.wrapCraftingMachine(
            machine,
            ghostTarget,
            this.applygray$currentCircuitConfig);
    }

    @Redirect(
        method = "collectPushTargets",
        at = @At(
            value = "INVOKE",
            target = "Lae2/helpers/patternprovider/PatternProviderLogic;getBatchTarget(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/EnumFacing;Lae2/api/implementations/blockentities/IPatternProviderBatchTarget;)Lae2/api/implementations/blockentities/IPatternProviderBatchTarget;"
        )
    )
    private IPatternProviderBatchTarget applygray$collectBatchTarget(
        World level,
        BlockPos pos,
        EnumFacing side,
        IPatternProviderBatchTarget fallback
    ) {
        TileEntity tileEntity = level.getTileEntity(pos);
        IPatternProviderBatchTarget batchTarget = fallback;
        if (tileEntity != null && tileEntity.hasCapability(AECapabilities.PATTERN_PROVIDER_BATCH_TARGET, side)) {
            IPatternProviderBatchTarget capabilityTarget = tileEntity.getCapability(
                AECapabilities.PATTERN_PROVIDER_BATCH_TARGET, side);
            if (capabilityTarget != null) {
                batchTarget = capabilityTarget;
            }
        }
        if (this.applygray$currentCircuitConfig < 0) {
            return batchTarget;
        }

        IGhostSlotConfigurable ghostTarget = GregTechCircuitPatternDetails.findGhostCircuitTarget(tileEntity, fallback);
        if (ghostTarget == null || batchTarget == fallback) {
            return batchTarget;
        }

        return GregTechCircuitPatternDetails.wrapBatchTarget(
            batchTarget,
            ghostTarget,
            this.applygray$currentCircuitConfig);
    }

    @Redirect(
        method = "collectPushTargets",
        at = @At(
            value = "INVOKE",
            target = "Lae2/helpers/patternprovider/PatternProviderLogic;findAdapter(Lnet/minecraft/util/EnumFacing;)Lae2/helpers/patternprovider/PatternProviderTarget;"
        )
    )
    private PatternProviderTarget applygray$collectExternalTarget(PatternProviderLogic logic, EnumFacing side) {
        PatternProviderTarget target = this.findAdapter(side);
        return this.applygray$wrapExternalTarget(side, target);
    }

    @Unique
    private PatternProviderTarget applygray$wrapExternalTarget(EnumFacing direction, PatternProviderTarget target) {
        if (target == null || this.applygray$currentCircuitConfig < 0) {
            return target;
        }

        TileEntity hostTileEntity = this.host.getTileEntity();
        World level = hostTileEntity.getWorld();

        BlockPos targetPos = hostTileEntity.getPos().offset(direction);
        TileEntity targetTileEntity = level.getTileEntity(targetPos);
        IGhostSlotConfigurable ghostTarget = GregTechCircuitPatternDetails.findGhostCircuitTarget(targetTileEntity, null);
        if (ghostTarget == null) {
            return target;
        }

        return GregTechCircuitPatternDetails.wrapExternalTarget(
            target,
            ghostTarget,
            this.applygray$currentCircuitConfig);
    }

    @Unique
    private boolean applygray$hasCircuitCard() {
        return this.upgrades.getInstalledUpgrades(ApplyGrayAeItems.CIRCUIT_CARD) > 0;
    }

    @Unique
    private IPatternDetails applygray$filterCircuitPattern(IPatternDetails patternDetails) {
        if (GregTechCircuitPatternDetails.findCircuitConfiguration(patternDetails).isEmpty()) {
            return patternDetails;
        }
        return GregTechCircuitPatternDetails.filterIntegratedCircuits(patternDetails);
    }

}
