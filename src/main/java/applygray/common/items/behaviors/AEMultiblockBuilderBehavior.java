package applygray.common.items.behaviors;

import applygray.integration.AE2StructureItemSource;

import gregtech.api.items.metaitem.stats.IItemBehaviour;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.api.metatileentity.multiblock.MultiblockControllerBase;
import gregtech.api.pattern.StructureFailureReport;
import gregtech.api.pattern.StructureItemSourceRegistry;
import gregtech.api.pattern.casing.StructureChannel;
import gregtech.client.gui.ClientToolScreens;
import gregtech.common.items.behaviors.multiblock.ChannelSettings;

import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;

import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AE multiblock builder: opens the structure preview/build dialog and lets the build pull missing materials
 * from a linked AE network.
 *
 * <p>The dialog itself is the shared GT builder UI; this behaviour only stores the channel configuration in
 * the item's NBT and hands it to the preview. Materials are resolved by {@link AE2StructureItemSource}, which
 * is registered globally as a {@code StructureItemSource}.
 *
 * <ul>
 *     <li>Right-click a controller: open the structure preview and build dialog</li>
 *     <li>Shift-right-click a controller: diagnose the structure and report the AE link state</li>
 * </ul>
 */
public final class AEMultiblockBuilderBehavior implements IItemBehaviour {

    private static final String BUILD_ADVICE = "gregtech.multiblock.pattern.failure.advice.build";

    @Override
    public EnumActionResult onItemUseFirst(EntityPlayer player, World world, BlockPos pos, EnumFacing side,
                                           float hitX, float hitY, float hitZ, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        TileEntity tile = world.getTileEntity(pos);
        if (!(tile instanceof IGregTechTileEntity)) {
            return EnumActionResult.PASS;
        }

        MetaTileEntity mte = ((IGregTechTileEntity) tile).getMetaTileEntity();
        if (!(mte instanceof MultiblockControllerBase controller)) {
            return EnumActionResult.PASS;
        }
        if (!player.canPlayerEdit(pos, side, stack)) {
            return EnumActionResult.FAIL;
        }

        ChannelSettings settings = ChannelSettings.load(stack);

        if (player.isSneaking()) {
            if (world.isRemote) {
                return EnumActionResult.SUCCESS;
            }
            saveSupportedChannels(settings, controller);
            diagnose(player, controller, world);
            return EnumActionResult.SUCCESS;
        }

        if (world.isRemote) {
            ClientToolScreens.openControllerPreview(controller, settings.values());
            return EnumActionResult.SUCCESS;
        }

        // The preview is client-only; the server side only refreshes the cached channel ranges.
        saveSupportedChannels(settings, controller);
        return EnumActionResult.SUCCESS;
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        // The dialog only opens from a controller; empty-space use keeps the vanilla behaviour.
        return new ActionResult<>(EnumActionResult.PASS, player.getHeldItem(hand));
    }

    /** Cache the controller's channel names and ranges in the item NBT for the preview dialog. */
    private static void saveSupportedChannels(@NotNull ChannelSettings settings,
                                              @NotNull MultiblockControllerBase controller) {
        Map<String, int[]> ranges = new HashMap<>();
        for (StructureChannel channel : controller.getSupportedChannels()) {
            ranges.put(channel.getName(), controller.getChannelRange(channel));
        }
        settings.setSupportedRanges(ranges);
        settings.store();
    }

    private static void diagnose(@NotNull EntityPlayer player, @NotNull MultiblockControllerBase controller,
                                 @NotNull World world) {
        if (controller.isStructureFormed()) {
            player.sendMessage(translation(TextFormatting.GREEN, "gregtech.multiblock.pattern.no_errors"));
        } else {
            // Reuse GT's failure report: missing blocks, expected states, ability gaps and advice in one pass.
            StructureFailureReport.sendTo(player, controller, world, BUILD_ADVICE);
        }
        player.sendMessage(linkStatus(player));
    }

    @NotNull
    private static ITextComponent linkStatus(@NotNull EntityPlayer player) {
        boolean linked = StructureItemSourceRegistry.hasLinkedSource(player);
        return translation(linked ? TextFormatting.GREEN : TextFormatting.YELLOW,
                linked ? "applygray.ae_builder.ae_linked" : "applygray.ae_builder.ae_unlinked");
    }

    @NotNull
    private static ITextComponent translation(@NotNull TextFormatting color, @NotNull String key, Object... args) {
        return new TextComponentTranslation(key, args).setStyle(new Style().setColor(color));
    }

    @Override
    public void addInformation(ItemStack stack, List<String> lines) {
        ChannelSettings settings = ChannelSettings.load(stack);
        lines.add(I18n.format("applygray.ae_builder.tooltip.preview"));
        lines.add(I18n.format("applygray.ae_builder.tooltip.diagnose"));
        if (settings.isNoHatch()) {
            lines.add(TextFormatting.RED + I18n.format("gregtech.tool.projector.no_hatch_on"));
        }
        if (!settings.values().isEmpty()) {
            lines.add(TextFormatting.YELLOW + I18n.format("applygray.ae_builder.tooltip.channels",
                    settings.values().size()));
        }
    }
}
