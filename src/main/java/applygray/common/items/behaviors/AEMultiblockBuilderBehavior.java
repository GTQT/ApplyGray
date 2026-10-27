package applygray.common.items.behaviors;

import applygray.integration.AE2StructureItemSource;

import gregtech.api.items.gui.ItemUIFactory;
import gregtech.api.items.metaitem.stats.IItemBehaviour;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.api.metatileentity.multiblock.MultiblockControllerBase;
import gregtech.api.mui.GTGuiTextures;
import gregtech.api.mui.GTGuis;
import gregtech.api.mui.factory.MetaItemGuiFactory;
import gregtech.api.pattern.MultiPiecePattern;
import gregtech.api.pattern.StructureBuildResult;
import gregtech.api.pattern.StructureFailureReport;
import gregtech.api.pattern.StructureOperationRequest;
import gregtech.api.pattern.StructureOrientation;
import gregtech.api.pattern.casing.GTStructureChannels;
import gregtech.api.pattern.casing.StructureChannel;
import gregtech.api.util.GTLog;
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
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.factory.HandGuiData;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.utils.Color;
import com.cleanroommc.modularui.value.sync.BooleanSyncValue;
import com.cleanroommc.modularui.value.sync.IntSyncValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.value.sync.StringSyncValue;
import com.cleanroommc.modularui.widget.scroll.VerticalScrollData;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.ToggleButton;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AE 多方块构建器：把 GT 的结构自动建造接到 AE 网络上。
 *
 * <p>建造时先消耗背包材料，不足的部分由 {@link AE2StructureItemSource} 通过玩家身上已连接的
 * 无线终端从 AE 网络提取（走 GT 的 {@code StructureItemSource} 扩展点，因此不需要复制一套
 * 结构匹配逻辑）。本行为只负责：解析目标控制器、发起生存建造、把建造结果翻译成玩家可读的反馈，
 * 以及维护物品上的通道配置。
 *
 * <p>交互约定与 GT 的多方块工具保持一致：
 * <ul>
 *     <li>潜行 + 右键控制器：按当前通道配置建造（{@code structure_piece} 通道 &gt; 0 时只补该段）</li>
 *     <li>右键控制器：诊断结构（缺块报告）并显示 AE 链路状态</li>
 *     <li>右键空气 / 非 GT 方块：打开通道配置面板</li>
 * </ul>
 *
 * <p>所有配置都存于 ItemStack NBT（见 {@link ChannelSettings}），行为本身无状态。
 */
public final class AEMultiblockBuilderBehavior implements IItemBehaviour, ItemUIFactory {

    private static final int MAX_CHANNEL_ROWS = 12;
    private static final int MAX_VISIBLE_ROWS = 6;
    private static final int ROW_HEIGHT = 18;

    /** 建造后最多逐条列出的缺失材料种类数。 */
    private static final int MAX_MISSING_ITEM_ROWS = 6;

    /** 指向本工具自动建造模式的建议行，复用 GT 的通用文本。 */
    private static final String BUILD_ADVICE = "gregtech.multiblock.pattern.failure.advice.build";

    // --- Item use ---

    @Override
    public EnumActionResult onItemUseFirst(EntityPlayer player, World world, BlockPos pos, EnumFacing side,
                                           float hitX, float hitY, float hitZ, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);

        TileEntity tile = world.getTileEntity(pos);
        if (!(tile instanceof IGregTechTileEntity)) {
            if (!world.isRemote) MetaItemGuiFactory.open(player, hand);
            return EnumActionResult.SUCCESS;
        }
        MetaTileEntity mte = ((IGregTechTileEntity) tile).getMetaTileEntity();
        if (!(mte instanceof MultiblockControllerBase controller)) return EnumActionResult.PASS;
        if (!player.canPlayerEdit(pos, side, stack)) return EnumActionResult.FAIL;
        if (world.isRemote) return EnumActionResult.SUCCESS;

        ChannelSettings settings = ChannelSettings.load(stack);
        saveSupportedChannels(settings, controller);
        if (!player.isSneaking()) {
            diagnose(player, controller, world);
            return EnumActionResult.SUCCESS;
        }
        build(player, controller, stack, settings);
        return EnumActionResult.SUCCESS;
    }

    /**
     * 记住控制器支持的通道与取值范围，供配置面板列出通道并限制输入。
     *
     * <p>与 GT 多方块工具一致：通道本身属于结构，工具只缓存一份用于界面。
     */
    private static void saveSupportedChannels(@NotNull ChannelSettings settings,
                                              @NotNull MultiblockControllerBase controller) {
        Map<String, int[]> ranges = new HashMap<>();
        for (StructureChannel channel : controller.getSupportedChannels()) {
            ranges.put(channel.getName(), controller.getChannelRange(channel));
        }
        settings.setSupportedRanges(ranges);
        settings.store();
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        if (!world.isRemote) MetaItemGuiFactory.open(player, hand);
        return new ActionResult<>(EnumActionResult.SUCCESS, player.getHeldItem(hand));
    }

    // --- Build ---

    private static void build(@NotNull EntityPlayer player, @NotNull MultiblockControllerBase controller,
                              @NotNull ItemStack stack, @NotNull ChannelSettings settings) {
        Map<String, Integer> channels = settings.asRequestChannels();
        StructureOrientation orientation = StructureOrientation.fromController(controller);
        var runtime = controller.getOrCreateStructureRuntime();

        int pieceIndex = settings.get(GTStructureChannels.STRUCTURE_PIECE.getName());
        if (pieceIndex > 0) {
            MultiPiecePattern pattern = runtime.getMultiPiecePattern();
            int compiledPiece = pattern == null ? pieceIndex : pattern.resolveToolingPieceIndex(pieceIndex);
            if (compiledPiece < 1) {
                player.sendMessage(translation(TextFormatting.RED, "applygray.ae_builder.invalid_piece", pieceIndex));
                return;
            }
            StructureBuildResult pieceResult = runtime.buildPiece(StructureOperationRequest.buildPiece(
                    compiledPiece, player, controller, orientation, channels, stack));
            reportBuildResult(player, pieceResult, compiledPiece);
            return;
        }

        // 与 GT 多方块工具一致：先交给控制器自己的自动建造钩子，未处理时再遍历全部结构片。
        StructureOperationRequest request = StructureOperationRequest.build(
                player, controller, orientation, channels, stack);
        if (controller.autoBuildStructure(request)) {
            player.sendMessage(translation(TextFormatting.GREEN, "applygray.ae_builder.handled_by_controller"));
            return;
        }
        reportBuildResult(player, runtime.buildAllPieces(request), 0);
    }

    /**
     * 把建造结果翻译成聊天反馈：放置数量、消耗数量、以及材料缺口（AE 也没有的材料）。
     */
    private static void reportBuildResult(@NotNull EntityPlayer player,
                                          @NotNull StructureBuildResult result,
                                          int pieceIndex) {
        if (!result.isAttempted()) {
            player.sendMessage(translation(TextFormatting.YELLOW, "applygray.ae_builder.not_attempted"));
            return;
        }

        player.sendMessage(translation(TextFormatting.GREEN, "applygray.ae_builder.result",
                Integer.toString(result.getPlacedCells()),
                Integer.toString(sumCount(result.getConsumedItems())),
                Integer.toString(result.getMissingCandidateCells())));

        List<MissingItem> missing = mergeMissing(result.getMissingItems());
        if (missing.isEmpty()) {
            return;
        }

        player.sendMessage(translation(TextFormatting.RED, "applygray.ae_builder.missing_header",
                Integer.toString(missing.size())));
        int listed = Math.min(missing.size(), MAX_MISSING_ITEM_ROWS);
        for (int i = 0; i < listed; i++) {
            MissingItem amount = missing.get(i);
            player.sendMessage(new TextComponentString(TextFormatting.GRAY + "  " +
                    amount.stack.getDisplayName() + " x" + amount.count));
        }
        if (missing.size() > listed) {
            player.sendMessage(translation(TextFormatting.GRAY, "applygray.ae_builder.missing_more",
                    Integer.toString(missing.size() - listed)));
        }
        if (!AE2StructureItemSource.isLinked(player)) {
            player.sendMessage(translation(TextFormatting.YELLOW, "applygray.ae_builder.ae_unlinked_hint"));
        }
        GTLog.logger.debug("[AE builder] piece={} placed={} missing={}", pieceIndex,
                result.getPlacedCells(), missing.size());
    }

    /** 合并同一种材料的多次缺失记录（结构遍历会按格子逐条记录）。 */
    @NotNull
    private static List<MissingItem> mergeMissing(@NotNull List<StructureBuildResult.ItemAmount> amounts) {
        List<MissingItem> merged = new ArrayList<>();
        for (StructureBuildResult.ItemAmount amount : amounts) {
            if (amount == null) continue;
            ItemStack stack = amount.getStack();
            if (stack.isEmpty()) continue;
            MissingItem matched = null;
            for (MissingItem existing : merged) {
                if (sameItem(existing.stack, stack)) {
                    matched = existing;
                    break;
                }
            }
            if (matched == null) {
                merged.add(new MissingItem(stack, amount.getCount()));
            } else {
                matched.count += amount.getCount();
            }
        }
        return merged;
    }

    private static boolean sameItem(@NotNull ItemStack first, @NotNull ItemStack second) {
        return ItemStack.areItemsEqual(first, second) && ItemStack.areItemStackTagsEqual(first, second);
    }

    private static int sumCount(@NotNull List<StructureBuildResult.ItemAmount> amounts) {
        int total = 0;
        for (StructureBuildResult.ItemAmount amount : amounts) {
            total += amount.getCount();
        }
        return total;
    }

    // --- Diagnosis ---

    private static void diagnose(@NotNull EntityPlayer player, @NotNull MultiblockControllerBase controller,
                                 @NotNull World world) {
        if (controller.isStructureFormed()) {
            player.sendMessage(translation(TextFormatting.GREEN, "gregtech.multiblock.pattern.no_errors"));
        } else {
            // 复用 GT 的结构失败报告：缺块位置、期望方块、缺失能力与建议一次列全。
            StructureFailureReport.sendTo(player, controller, world, BUILD_ADVICE);
        }
        player.sendMessage(linkStatus(player));
    }

    @NotNull
    private static ITextComponent linkStatus(@NotNull EntityPlayer player) {
        boolean linked = AE2StructureItemSource.isLinked(player);
        return translation(linked ? TextFormatting.GREEN : TextFormatting.YELLOW,
                linked ? "applygray.ae_builder.ae_linked" : "applygray.ae_builder.ae_unlinked");
    }

    @NotNull
    private static ITextComponent translation(@NotNull TextFormatting color, @NotNull String key, Object... args) {
        return new TextComponentTranslation(key, args).setStyle(new Style().setColor(color));
    }

    // --- Item info ---

    @Override
    public void addInformation(ItemStack stack, List<String> lines) {
        ChannelSettings settings = ChannelSettings.load(stack);
        lines.add(I18n.format("applygray.ae_builder.tooltip.build"));
        lines.add(I18n.format("applygray.ae_builder.tooltip.diagnose"));
        lines.add(I18n.format("applygray.ae_builder.tooltip.configure"));
        if (settings.isNoHatch()) {
            lines.add(TextFormatting.RED + I18n.format("gregtech.tool.projector.no_hatch_on"));
        }
        if (!settings.values().isEmpty()) {
            lines.add(TextFormatting.YELLOW + I18n.format("applygray.ae_builder.tooltip.channels",
                    settings.values().size()));
        }
    }

    // --- GUI ---

    @Override
    @SuppressWarnings("deprecation") // ItemUIFactory 仍要求 HandGuiData 契约。
    public ModularPanel buildUI(HandGuiData guiData, PanelSyncManager guiSyncManager, UISettings settings) {
        ItemStack stack = guiData.getUsedItemStack();
        ChannelSettings channels = ChannelSettings.load(stack);
        List<ChannelEntry> entries = buildChannelEntries(channels);

        ModularPanel panel = GTGuis.createPanel(stack, 176, 240);

        BooleanSyncValue noHatchValue = new BooleanSyncValue(
                channels::isNoHatch,
                v -> {
                    channels.setNoHatch(v);
                    channels.store();
                });
        guiSyncManager.syncValue("no_hatch", noHatchValue);

        int[] widthRange = channels.supportedRanges().getOrDefault(
                GTStructureChannels.STRUCTURE_WIDTH.getName(), new int[] { 0, 100 });
        int[] heightRange = channels.supportedRanges().getOrDefault(
                GTStructureChannels.STRUCTURE_HEIGHT.getName(), new int[] { 0, 100 });
        int[] lengthRange = channels.supportedRanges().getOrDefault(
                GTStructureChannels.STRUCTURE_LENGTH.getName(), new int[] { 0, 100 });

        IntSyncValue widthValue = new IntSyncValue(
                () -> channels.get(GTStructureChannels.STRUCTURE_WIDTH.getName()),
                v -> {
                    channels.put(GTStructureChannels.STRUCTURE_WIDTH.getName(), v);
                    channels.store();
                });
        guiSyncManager.syncValue("structure_width", widthValue);

        IntSyncValue heightValue = new IntSyncValue(
                () -> channels.get(GTStructureChannels.STRUCTURE_HEIGHT.getName()),
                v -> {
                    channels.put(GTStructureChannels.STRUCTURE_HEIGHT.getName(), v);
                    channels.store();
                });
        guiSyncManager.syncValue("structure_height", heightValue);

        IntSyncValue lengthValue = new IntSyncValue(
                () -> channels.get(GTStructureChannels.STRUCTURE_LENGTH.getName()),
                v -> {
                    channels.put(GTStructureChannels.STRUCTURE_LENGTH.getName(), v);
                    channels.store();
                });
        guiSyncManager.syncValue("structure_length", lengthValue);

        StringSyncValue[] nameSyncs = new StringSyncValue[MAX_CHANNEL_ROWS];
        IntSyncValue[] valueSyncs = new IntSyncValue[MAX_CHANNEL_ROWS];
        for (int i = 0; i < MAX_CHANNEL_ROWS; i++) {
            final int idx = i;
            nameSyncs[i] = new StringSyncValue(
                    () -> idx < entries.size() ? entries.get(idx).name : "",
                    n -> updateEntryName(channels, entries, idx, n != null ? n : ""));
            valueSyncs[i] = new IntSyncValue(
                    () -> idx < entries.size() ? entries.get(idx).value : 0,
                    v -> updateEntryValue(channels, entries, idx, v));
            guiSyncManager.syncValue("ch_name_" + i, nameSyncs[i]);
            guiSyncManager.syncValue("ch_val_" + i, valueSyncs[i]);
        }

        int visibleRows = Math.max(entries.size(), 1);
        int listHeight = Math.min(visibleRows, MAX_VISIBLE_ROWS) * ROW_HEIGHT;

        var channelList = new ListWidget<>()
                .children(visibleRows, i -> {
                    int[] range = getEntryRange(channels, entries, i);
                    return Flow.row()
                            .widthRel(1f)
                            .height(ROW_HEIGHT - 2)
                            .child(new TextFieldWidget()
                                    .width(100)
                                    .height(12)
                                    .setTextColor(Color.WHITE.darker(1))
                                    .setMaxLength(32)
                                    .value(nameSyncs[i])
                                    .background(GTGuiTextures.DISPLAY))
                            .child(new TextFieldWidget()
                                    .width(40)
                                    .height(12)
                                    .setTextColor(Color.WHITE.darker(1))
                                    .setNumbers(range[0], range[1])
                                    .value(valueSyncs[i])
                                    .background(GTGuiTextures.DISPLAY));
                })
                .scrollDirection(new VerticalScrollData())
                .size(162, listHeight)
                .pos(7, 105);

        var clearButton = new ButtonWidget<>()
                .pos(7, 215)
                .width(60).height(16)
                .overlay(IKey.lang("gregtech.tool.projector.clear"))
                .onMousePressed(m -> {
                    channels.clear();
                    entries.clear();
                    widthValue.setValue(0, true, true);
                    heightValue.setValue(0, true, true);
                    lengthValue.setValue(0, true, true);
                    for (int i = 0; i < MAX_CHANNEL_ROWS; i++) {
                        nameSyncs[i].setValue("", true, true);
                        valueSyncs[i].setValue(0, true, true);
                    }
                    return true;
                });

        return panel
                .child(IKey.lang("applygray.ae_builder.gui.title").asWidget().pos(5, 5))
                .child(new ToggleButton()
                        .pos(15, 20)
                        .width(90).height(18)
                        .value(noHatchValue)
                        .tooltip(tooltip -> tooltip.addLine(
                                IKey.lang("gregtech.tool.projector.no_hatch_tooltip")))
                        .onUpdateListener(w -> w.overlay(IKey.str(
                                noHatchValue.getValue() ? "NO HATCH" : "HATCH"))))
                .child(IKey.lang("applygray.ae_builder.gui.source_hint").asWidget().pos(7, 40))
                .child(IKey.lang("gregtech.tool.projector.structure_height").asWidget().pos(7, 54))
                .child(new TextFieldWidget()
                        .pos(80, 54)
                        .width(40).height(12)
                        .setTextColor(Color.WHITE.darker(1))
                        .setNumbers(heightRange[0], heightRange[1])
                        .value(heightValue)
                        .background(GTGuiTextures.DISPLAY))
                .child(IKey.lang("gregtech.tool.projector.structure_width").asWidget().pos(7, 70))
                .child(new TextFieldWidget()
                        .pos(80, 70)
                        .width(40).height(12)
                        .setTextColor(Color.WHITE.darker(1))
                        .setNumbers(widthRange[0], widthRange[1])
                        .value(widthValue)
                        .background(GTGuiTextures.DISPLAY))
                .child(IKey.lang("gregtech.tool.projector.structure_length").asWidget().pos(7, 86))
                .child(new TextFieldWidget()
                        .pos(80, 86)
                        .width(40).height(12)
                        .setTextColor(Color.WHITE.darker(1))
                        .setNumbers(lengthRange[0], lengthRange[1])
                        .value(lengthValue)
                        .background(GTGuiTextures.DISPLAY))
                .child(channelList)
                .child(clearButton);
    }

    @NotNull
    private static List<ChannelEntry> buildChannelEntries(@NotNull ChannelSettings channels) {
        List<ChannelEntry> entries = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : channels.values().entrySet()) {
            if (isDedicatedDimensionChannel(entry.getKey())) continue;
            entries.add(new ChannelEntry(entry.getKey(), entry.getValue()));
        }
        for (String name : channels.supportedRanges().keySet()) {
            if (isDedicatedDimensionChannel(name) || containsEntry(entries, name)) continue;
            entries.add(new ChannelEntry(name, channels.get(name)));
        }
        return entries;
    }

    private static boolean isDedicatedDimensionChannel(@NotNull String name) {
        return name.equals(GTStructureChannels.STRUCTURE_WIDTH.getName()) ||
                name.equals(GTStructureChannels.STRUCTURE_HEIGHT.getName()) ||
                name.equals(GTStructureChannels.STRUCTURE_LENGTH.getName());
    }

    private static boolean containsEntry(@NotNull List<ChannelEntry> entries, @NotNull String name) {
        for (ChannelEntry entry : entries) {
            if (name.equals(entry.name)) return true;
        }
        return false;
    }

    private static int[] getEntryRange(@NotNull ChannelSettings channels, @NotNull List<ChannelEntry> entries,
                                       int idx) {
        if (idx < entries.size()) {
            String name = entries.get(idx).name;
            if (name != null && !name.isEmpty() && channels.supportedRanges().containsKey(name)) {
                return channels.supportedRanges().get(name);
            }
        }
        return new int[] { 0, 100 };
    }

    private static void updateEntryName(@NotNull ChannelSettings channels, @NotNull List<ChannelEntry> entries,
                                        int idx, @NotNull String name) {
        while (entries.size() <= idx) {
            entries.add(new ChannelEntry("", 0));
        }
        String previous = entries.get(idx).name;
        entries.get(idx).name = name;
        if (previous != null && !previous.isEmpty()) {
            channels.put(previous, 0);
        }
        if (!name.isEmpty()) {
            channels.put(name, entries.get(idx).value);
        }
        channels.store();
    }

    private static void updateEntryValue(@NotNull ChannelSettings channels, @NotNull List<ChannelEntry> entries,
                                         int idx, int value) {
        while (entries.size() <= idx) {
            entries.add(new ChannelEntry("", 0));
        }
        entries.get(idx).value = value;
        String name = entries.get(idx).name;
        if (name != null && !name.isEmpty()) {
            channels.put(name, value);
        }
        channels.store();
    }

    /** 面板中的一行通道：名称 + 数值，只是 {@link ChannelSettings} 的视图模型。 */
    private static final class ChannelEntry {

        private String name;
        private int value;

        private ChannelEntry(@NotNull String name, int value) {
            this.name = name;
            this.value = value;
        }
    }

    /** 合并后的缺失材料条目（GT 的 {@code ItemAmount} 构造器不可见，这里自建视图）。 */
    private static final class MissingItem {

        private final ItemStack stack;
        private int count;

        private MissingItem(@NotNull ItemStack stack, int count) {
            this.stack = stack;
            this.count = count;
        }
    }
}
