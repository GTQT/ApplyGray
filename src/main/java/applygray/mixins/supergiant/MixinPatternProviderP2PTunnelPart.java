package applygray.mixins.supergiant;

import ae2.api.AECapabilities;
import ae2.api.networking.security.IActionSource;
import ae2.api.implementations.blockentities.ICraftingMachine;
import ae2.api.implementations.blockentities.IPatternProviderBatchTarget;
import ae2.helpers.patternprovider.PatternProviderTarget;
import ae2.parts.p2p.PatternProviderP2PTunnelPart;
import applygray.integration.ae2.circuit.GregTechCircuitPatternDetails;
import gregtech.api.capability.IGhostSlotConfigurable;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.OptionalInt;

@Mixin(value = PatternProviderP2PTunnelPart.class, remap = false)
public class MixinPatternProviderP2PTunnelPart {

    @Unique
    private ICraftingMachine applygray$lastRemoteMachine;

    @Redirect(
        method = "findRemoteMachineTarget",
        at = @At(
            value = "INVOKE",
            target = "Lae2/api/implementations/blockentities/ICraftingMachine;of(Lnet/minecraft/tileentity/TileEntity;Lnet/minecraft/util/EnumFacing;)Lae2/api/implementations/blockentities/ICraftingMachine;"
        )
    )
    private ICraftingMachine applygray$wrapRemoteCraftingMachine(TileEntity blockEntity, EnumFacing side) {
        ICraftingMachine machine = ICraftingMachine.of(blockEntity, side);
        this.applygray$lastRemoteMachine = machine;

        OptionalInt circuitConfiguration = GregTechCircuitPatternDetails.getActiveCircuitConfiguration();
        if (machine == null || circuitConfiguration.isEmpty()) {
            return machine;
        }

        IGhostSlotConfigurable ghostTarget = GregTechCircuitPatternDetails.findGhostCircuitTarget(blockEntity, machine);
        if (ghostTarget == null) {
            return machine;
        }

        ICraftingMachine wrapped = GregTechCircuitPatternDetails.wrapCraftingMachine(
            machine,
            ghostTarget,
            circuitConfiguration.getAsInt());
        this.applygray$lastRemoteMachine = wrapped;
        return wrapped;
    }

    @Redirect(
        method = "findRemoteMachineTarget",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/tileentity/TileEntity;getCapability(Lnet/minecraftforge/common/capabilities/Capability;Lnet/minecraft/util/EnumFacing;)Ljava/lang/Object;"
        )
    )
    private Object applygray$wrapRemoteBatchTarget(
        TileEntity tileEntity,
        Capability<?> capability,
        EnumFacing facing
    ) {
        Object target = tileEntity.getCapability(capability, facing);
        if (capability != AECapabilities.PATTERN_PROVIDER_BATCH_TARGET
            || !(target instanceof IPatternProviderBatchTarget batchTarget)) {
            return target;
        }

        OptionalInt circuitConfiguration = GregTechCircuitPatternDetails.getActiveCircuitConfiguration();
        if (circuitConfiguration.isEmpty() || batchTarget == this.applygray$lastRemoteMachine) {
            return target;
        }

        IGhostSlotConfigurable ghostTarget = GregTechCircuitPatternDetails.findGhostCircuitTarget(
            tileEntity,
            this.applygray$lastRemoteMachine);
        if (ghostTarget == null) {
            return target;
        }

        return GregTechCircuitPatternDetails.wrapBatchTarget(
            batchTarget,
            ghostTarget,
            circuitConfiguration.getAsInt());
    }

    @Redirect(
        method = "findRemoteExternalTarget",
        at = @At(
            value = "INVOKE",
            target = "Lae2/helpers/patternprovider/PatternProviderTarget;get(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/EnumFacing;Lae2/api/networking/security/IActionSource;)Lae2/helpers/patternprovider/PatternProviderTarget;"
        )
    )
    private PatternProviderTarget applygray$wrapRemoteExternalTarget(
        World level,
        BlockPos pos,
        EnumFacing side,
        IActionSource actionSource
    ) {
        PatternProviderTarget target = PatternProviderTarget.get(level, pos, side, actionSource);
        if (target == null) {
            return null;
        }

        OptionalInt circuitConfiguration = GregTechCircuitPatternDetails.getActiveCircuitConfiguration();
        if (circuitConfiguration.isEmpty()) {
            return target;
        }

        TileEntity tileEntity = level.getTileEntity(pos);
        IGhostSlotConfigurable ghostTarget = GregTechCircuitPatternDetails.findGhostCircuitTarget(tileEntity, null);
        if (ghostTarget == null) {
            return target;
        }

        return GregTechCircuitPatternDetails.wrapExternalTarget(
            target,
            ghostTarget,
            circuitConfiguration.getAsInt());
    }
}
