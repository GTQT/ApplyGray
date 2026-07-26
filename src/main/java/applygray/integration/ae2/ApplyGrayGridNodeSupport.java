package applygray.integration.ae2;

import applygray.api.IAEManagedMetaTileEntity;

import gregtech.api.metatileentity.MetaTileEntity;

import ae2.api.networking.GridHelper;
import ae2.api.networking.IGridNode;
import ae2.api.networking.IGridNodeListener;
import ae2.api.networking.IManagedGridNode;

public final class ApplyGrayGridNodeSupport {

    private static final IGridNodeListener<MetaTileEntity> NODE_LISTENER = new IGridNodeListener<>() {

        @Override
        public void onSaveChanges(MetaTileEntity owner, IGridNode node) {
            owner.markDirty();
        }

        @Override
        public void onGridChanged(MetaTileEntity owner, IGridNode node) {
            if (owner instanceof IAEManagedMetaTileEntity managed) {
                managed.gridChanged();
            }
        }

        @Override
        public void onStateChanged(MetaTileEntity owner, IGridNode node, State state) {
            if (owner instanceof IAEManagedMetaTileEntity managed) {
                managed.onMainNodeStateChanged(state);
            }
        }
    };

    private ApplyGrayGridNodeSupport() {}

    public static IManagedGridNode createMainNode(MetaTileEntity owner) {
        return GridHelper.createManagedNode(owner, NODE_LISTENER).setInWorldNode(true);
    }
}
