package gregtech.common.metatileentities.multi.multiblockpart.appeng;

import applygray.client.renderer.texture.ApplyGrayTextures;

import gregtech.api.GTValues;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.api.metatileentity.multiblock.AbilityInstances;
import gregtech.api.metatileentity.multiblock.IMultiblockAbilityPart;
import gregtech.api.metatileentity.multiblock.MultiblockAbility;
import gregtech.api.metatileentity.multiblock.MultiblockControllerBase;
import gregtech.api.metatileentity.multiblock.MultiblockWithDisplayBase;
import gregtech.api.mui.drawable.GTObjectDrawable;
import gregtech.api.util.KeyUtil;

import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.items.IItemHandlerModifiable;

import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import codechicken.lib.render.CCRenderState;
import codechicken.lib.render.pipeline.IVertexOperation;
import codechicken.lib.vec.Matrix4;
import com.cleanroommc.modularui.api.drawable.IRichTextBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class MetaTileEntityMEOutputBus extends MetaTileEntityMEOutputBase
        implements IMultiblockAbilityPart<IItemHandlerModifiable> {

    public final static String ITEM_BUFFER_TAG = "ItemBuffer";

    public MetaTileEntityMEOutputBus(ResourceLocation metaTileEntityId) {
        super(metaTileEntityId, GTValues.EV);
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity iGregTechTileEntity) {
        return new MetaTileEntityMEOutputBus(this.metaTileEntityId);
    }

    @SideOnly(Side.CLIENT)
    @Override
    protected void addStackLine(@NotNull IRichTextBuilder<?> text,
                                @NotNull GenericStack wrappedStack) {
        if (!(wrappedStack.what() instanceof AEItemKey itemKey)) {
            return;
        }
        ItemStack stack = itemKey.toStack(1);
        text.add(new GTObjectDrawable(stack, 0)
                .asIcon()
                .asHoverable()
                // Auto update has to be true for "Press CTRL for Advanced Info" to work
                .tooltipAutoUpdate(true)
                .tooltipBuilder(tooltip -> tooltip.addFromItem(stack)));
        text.space();
        text.addLine(KeyUtil.number(TextFormatting.WHITE, wrappedStack.amount(), "x"));
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound data) {
        super.writeToNBT(data);

        NBTTagList nbtList = new NBTTagList();
        for (GenericStack stack : internalBuffer) {
            if (stack.what() instanceof AEItemKey) {
                nbtList.appendTag(GenericStack.writeTag(stack));
            }
        }
        data.setTag(ITEM_BUFFER_TAG, nbtList);

        return data;
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        for (NBTBase tag : data.getTagList(ITEM_BUFFER_TAG, Constants.NBT.TAG_COMPOUND)) {
            GenericStack stack = GenericStack.readTag((NBTTagCompound) tag);
            if (stack != null && stack.what() instanceof AEItemKey) {
                addToBuffer(stack);
            }
        }
    }

    @Override
    public void renderMetaTileEntity(CCRenderState renderState, Matrix4 translation, IVertexOperation[] pipeline) {
        super.renderMetaTileEntity(renderState, translation, pipeline);
        if (this.shouldRenderOverlay()) {
            if (isOnline()) {
                ApplyGrayTextures.ME_OUTPUT_BUS_ACTIVE.renderSided(getFrontFacing(), renderState, translation, pipeline);
            } else {
                ApplyGrayTextures.ME_OUTPUT_BUS.renderSided(getFrontFacing(), renderState, translation, pipeline);
            }
        }
    }

    @Override
    public void addInformation(ItemStack stack, @Nullable World player, @NotNull List<String> tooltip,
                               boolean advanced) {
        super.addInformation(stack, player, tooltip, advanced);
        tooltip.add(I18n.format("gregtech.machine.item_bus.export.tooltip"));
        tooltip.add(I18n.format("gregtech.machine.me.item_export.tooltip"));
        tooltip.add(I18n.format("gregtech.machine.me.item_export.tooltip.2"));
        tooltip.add(I18n.format("gregtech.machine.me.extra_connections.tooltip"));
        tooltip.add(I18n.format("gregtech.universal.enabled"));
    }

    @Override
    public MultiblockAbility<IItemHandlerModifiable> getAbility() {
        return MultiblockAbility.EXPORT_ITEMS;
    }

    @Override
    public void registerAbilities(@NotNull AbilityInstances abilityInstances) {
        abilityInstances.add(new InaccessibleInfiniteSlot(this, this.getController()));
    }

    @Override
    public void addToMultiBlock(MultiblockControllerBase controllerBase) {
        super.addToMultiBlock(controllerBase);
        if (controllerBase instanceof MultiblockWithDisplayBase multiblockWithDisplayBase) {
            multiblockWithDisplayBase.enableItemInfSink();
        }
    }

    private class InaccessibleInfiniteSlot extends InaccessibleInfiniteHandler implements IItemHandlerModifiable {

        public InaccessibleInfiniteSlot(@NotNull MetaTileEntity holder,
                                        @NotNull MetaTileEntity mte) {
            super(holder, mte);
        }

        @Override
        public void setStackInSlot(int slot, @NotNull ItemStack stack) {
            insertItem(slot, stack, false);
            this.trigger();
        }

        @Override
        public int getSlots() {
            return 1;
        }

        @NotNull
        @Override
        public ItemStack getStackInSlot(int slot) {
            return ItemStack.EMPTY;
        }

        @NotNull
        @Override
        public ItemStack insertItem(int slot, @NotNull ItemStack stackToInsert, boolean simulate) {
            if (stackToInsert.isEmpty() || simulate) {
                return ItemStack.EMPTY;
            }

            GenericStack stack = GenericStack.fromItemStack(stackToInsert);
            if (stack != null) {
                addToBuffer(stack);
            }

            trigger();
            return ItemStack.EMPTY;
        }

        @NotNull
        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return Integer.MAX_VALUE - 1;
        }
    }
}
