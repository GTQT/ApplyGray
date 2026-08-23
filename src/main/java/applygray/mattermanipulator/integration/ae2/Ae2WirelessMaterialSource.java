package applygray.mattermanipulator.integration.ae2;

import java.util.Objects;

import applygray.mattermanipulator.building.BlockSpec;
import applygray.mattermanipulator.inventory.MaterialSource;
import applygray.mattermanipulator.item.ItemMatterManipulator;
import applygray.mattermanipulator.state.ManipulatorLocation;
import applygray.mattermanipulator.state.ManipulatorState;

import ae2.api.config.Actionable;
import ae2.api.implementations.blockentities.IWirelessAccessPoint;
import ae2.api.networking.IGrid;
import ae2.api.networking.security.IActionSource;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEFluidKey;
import ae2.api.storage.StorageHelper;
import ae2.me.helpers.ActionHostEnergySource;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fluids.FluidStack;

/**
 * Exact material source backed by the AE2 network selected in the manipulator's security-terminal linking slot.
 *
 * <p>Every transfer rechecks the bound access point, active grid node, network power, wireless range, and player-backed
 * action source. The binding is a target-native dimension-qualified access-point location; no legacy AE encryption-key
 * compatibility field is retained.</p>
 */
public final class Ae2WirelessMaterialSource implements MaterialSource {

    private static final String ID = "ae2-bound-network";

    private final EntityPlayerMP player;
    private final ManipulatorLocation binding;

    public Ae2WirelessMaterialSource(EntityPlayerMP player, ItemStack manipulatorStack) {
        this.player = Objects.requireNonNull(player, "player");
        if (!(manipulatorStack.getItem() instanceof ItemMatterManipulator manipulator)) {
            throw new IllegalArgumentException("A Matter Manipulator stack is required");
        }
        ManipulatorState state = manipulator.state(manipulatorStack);
        this.binding = state.ae2NetworkLocation();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public long extract(BlockSpec specification, long amount, boolean simulate) {
        AEItemKey key = keyFor(specification, amount);
        BoundNetwork network = findBoundNetwork();
        if (key == null || network == null) return 0L;
        return StorageHelper.poweredExtraction(new ActionHostEnergySource(network.accessPoint()), network.inventory(), key,
                amount, IActionSource.ofPlayer(player, network.accessPoint()),
                simulate ? Actionable.SIMULATE : Actionable.MODULATE);
    }

    @Override
    public long insert(BlockSpec specification, long amount, boolean simulate) {
        AEItemKey key = keyFor(specification, amount);
        BoundNetwork network = findBoundNetwork();
        if (key == null || network == null) return 0L;
        return StorageHelper.poweredInsert(new ActionHostEnergySource(network.accessPoint()), network.inventory(), key,
                amount, IActionSource.ofPlayer(player, network.accessPoint()),
                simulate ? Actionable.SIMULATE : Actionable.MODULATE);
    }

    @Override
    public long extract(FluidStack specification, long amount, boolean simulate) {
        AEFluidKey key = specification == null ? null : AEFluidKey.of(specification);
        BoundNetwork network = findBoundNetwork();
        if (key == null || network == null) return 0L;
        return StorageHelper.poweredExtraction(new ActionHostEnergySource(network.accessPoint()), network.inventory(), key,
                amount, IActionSource.ofPlayer(player, network.accessPoint()), simulate ? Actionable.SIMULATE : Actionable.MODULATE);
    }

    @Override
    public long insert(FluidStack specification, long amount, boolean simulate) {
        AEFluidKey key = specification == null ? null : AEFluidKey.of(specification);
        BoundNetwork network = findBoundNetwork();
        if (key == null || network == null) return 0L;
        return StorageHelper.poweredInsert(new ActionHostEnergySource(network.accessPoint()), network.inventory(), key,
                amount, IActionSource.ofPlayer(player, network.accessPoint()), simulate ? Actionable.SIMULATE : Actionable.MODULATE);
    }

    private AEItemKey keyFor(BlockSpec specification, long amount) {
        if (amount <= 0L || specification.isAir()) return null;
        return AEItemKey.of(specification.toStack());
    }

    private BoundNetwork findBoundNetwork() {
        if (binding == null || binding.dimension() != player.dimension) return null;
        World world = DimensionManager.getWorld(binding.dimension());
        if (world == null || world.isRemote) return null;
        TileEntity tile = world.getTileEntity(binding.position());
        if (!(tile instanceof IWirelessAccessPoint accessPoint) || !accessPoint.isActive()) return null;
        IGrid grid = accessPoint.getGrid();
        if (grid == null || accessPoint.getActionableNode() == null || !accessPoint.getActionableNode().isActive()) {
            return null;
        }
        if (!grid.getEnergyService().isNetworkPowered()) return null;
        double distanceSquared = player.getDistanceSq(binding.position().getX() + 0.5D,
                binding.position().getY() + 0.5D, binding.position().getZ() + 0.5D);
        if (distanceSquared > accessPoint.getRange() * accessPoint.getRange()) return null;
        return new BoundNetwork(accessPoint, grid.getStorageService().getInventory());
    }

    private record BoundNetwork(IWirelessAccessPoint accessPoint, ae2.api.storage.MEStorage inventory) {}
}
