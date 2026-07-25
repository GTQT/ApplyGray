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
import gregtech.api.util.FluidTooltipUtil;
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
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidTank;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.GenericStack;
import codechicken.lib.render.CCRenderState;
import codechicken.lib.render.pipeline.IVertexOperation;
import codechicken.lib.vec.Matrix4;
import com.cleanroommc.modularui.api.drawable.IRichTextBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class MetaTileEntityMEOutputHatch extends MetaTileEntityMEOutputBase
        implements IMultiblockAbilityPart<IFluidTank> {

    public final static String FLUID_BUFFER_TAG = "FluidBuffer";

    public MetaTileEntityMEOutputHatch(ResourceLocation metaTileEntityId) {
        super(metaTileEntityId, GTValues.EV);
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity iGregTechTileEntity) {
        return new MetaTileEntityMEOutputHatch(this.metaTileEntityId);
    }

    @SideOnly(Side.CLIENT)
    @Override
    protected void addStackLine(@NotNull IRichTextBuilder<?> text,
                                @NotNull GenericStack wrappedStack) {
        if (!(wrappedStack.what() instanceof AEFluidKey fluidKey)) {
            return;
        }
        FluidStack stack = fluidKey.toStack(1);
        text.add(new GTObjectDrawable(stack, 0)
                .asIcon()
                .asHoverable()
                .tooltip(tooltip -> {
                    tooltip.addLine(KeyUtil.fluid(stack));
                    FluidTooltipUtil.handleFluidTooltip(tooltip, stack);
                }));
        text.space();
        text.addLine(KeyUtil.number(TextFormatting.WHITE, wrappedStack.amount(), "L"));
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound data) {
        super.writeToNBT(data);

        NBTTagList nbtList = new NBTTagList();
        for (GenericStack stack : internalBuffer) {
            if (stack.what() instanceof AEFluidKey) {
                nbtList.appendTag(GenericStack.writeTag(stack));
            }
        }
        data.setTag(FLUID_BUFFER_TAG, nbtList);

        return data;
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        for (NBTBase tag : data.getTagList(FLUID_BUFFER_TAG, Constants.NBT.TAG_COMPOUND)) {
            GenericStack stack = GenericStack.readTag((NBTTagCompound) tag);
            if (stack != null && stack.what() instanceof AEFluidKey) {
                addToBuffer(stack);
            }
        }
    }

    @Override
    public void renderMetaTileEntity(CCRenderState renderState, Matrix4 translation, IVertexOperation[] pipeline) {
        super.renderMetaTileEntity(renderState, translation, pipeline);
        if (this.shouldRenderOverlay()) {
            if (isOnline()) {
                ApplyGrayTextures.ME_OUTPUT_HATCH_ACTIVE.renderSided(getFrontFacing(), renderState, translation, pipeline);
            } else {
                ApplyGrayTextures.ME_OUTPUT_HATCH.renderSided(getFrontFacing(), renderState, translation, pipeline);
            }
        }
    }

    @Override
    public void addInformation(ItemStack stack, @Nullable World player, @NotNull List<String> tooltip,
                               boolean advanced) {
        super.addInformation(stack, player, tooltip, advanced);
        tooltip.add(I18n.format("gregtech.machine.fluid_hatch.export.tooltip"));
        tooltip.add(I18n.format("gregtech.machine.me.fluid_export.tooltip"));
        tooltip.add(I18n.format("gregtech.machine.me.fluid_export.tooltip.2"));
        tooltip.add(I18n.format("gregtech.machine.me.extra_connections.tooltip"));
        tooltip.add(I18n.format("gregtech.universal.enabled"));
    }

    @Override
    public MultiblockAbility<IFluidTank> getAbility() {
        return MultiblockAbility.EXPORT_FLUIDS;
    }

    @Override
    public void registerAbilities(@NotNull AbilityInstances abilityInstances) {
        abilityInstances.add(new InaccessibleInfiniteTank(this, this.getController()));
    }

    @Override
    public void addToMultiBlock(MultiblockControllerBase controllerBase) {
        super.addToMultiBlock(controllerBase);
        if (controllerBase instanceof MultiblockWithDisplayBase multiblockWithDisplayBase) {
            multiblockWithDisplayBase.enableFluidInfSink();
        }
    }

    protected class InaccessibleInfiniteTank extends InaccessibleInfiniteHandler implements IFluidTank {

        public InaccessibleInfiniteTank(@NotNull MetaTileEntity holder,
                                        @NotNull MetaTileEntity mte) {
            super(holder, mte);
        }

        @Nullable
        @Override
        public FluidStack getFluid() {
            return null;
        }

        @Override
        public int getFluidAmount() {
            return 0;
        }

        @Override
        public int getCapacity() {
            return Integer.MAX_VALUE - 1;
        }

        @Override
        public FluidTankInfo getInfo() {
            return null;
        }

        @Override
        public int fill(@Nullable FluidStack stackToInsert, boolean doFill) {
            if (stackToInsert == null || stackToInsert.amount < 1) {
                return 0;
            }

            if (doFill) {
                GenericStack stack = GenericStack.fromFluidStack(stackToInsert);
                if (stack != null) {
                    addToBuffer(stack);
                }

                this.trigger();
            }

            return stackToInsert.amount;
        }

        @Nullable
        @Override
        public FluidStack drain(int maxDrain, boolean doDrain) {
            return null;
        }
    }
}
