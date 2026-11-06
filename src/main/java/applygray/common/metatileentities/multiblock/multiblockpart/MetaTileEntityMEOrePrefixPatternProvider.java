package applygray.common.metatileentities.multiblock.multiblockpart;

import applygray.api.mui.ApplyGrayGuiTextures;
import applygray.client.renderer.texture.ApplyGrayTextures;
import applygray.common.metatileentities.multiblock.multiblockpart.utils.IMEPatternProviderPart;
import applygray.integration.ae2.ExactPatternInputRegistry;

import gregtech.api.GregTechAPI;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.api.metatileentity.multiblock.MultiblockControllerBase;
import gregtech.api.mui.GTGuiTextures;
import gregtech.api.mui.GTGuis;
import gregtech.api.mui.sync.PagedWidgetSyncHandler;
import gregtech.api.unification.OreDictUnifier;
import gregtech.api.unification.material.Material;
import gregtech.api.unification.material.info.MaterialFlag;
import gregtech.api.unification.ore.OrePrefix;
import gregtech.client.renderer.texture.cube.SimpleOverlayRenderer;
import gregtech.common.mui.widget.ScrollableTextWidget;

import net.minecraft.client.resources.I18n;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

import ae2.api.crafting.IPatternDetails;
import ae2.api.crafting.PatternDetailsHelper;
import codechicken.lib.render.CCRenderState;
import codechicken.lib.render.pipeline.IVertexOperation;
import codechicken.lib.vec.Matrix4;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.drawable.GuiTextures;
import com.cleanroommc.modularui.factory.PosGuiData;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.utils.Color;
import com.cleanroommc.modularui.value.BoolValue;
import com.cleanroommc.modularui.value.sync.BooleanSyncValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.value.sync.StringSyncValue;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.PageButton;
import com.cleanroommc.modularui.widgets.PagedWidget;
import com.cleanroommc.modularui.widgets.SlotGroupWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.ToggleButton;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.cleanroommc.modularui.widgets.layout.Grid;
import com.cleanroommc.modularui.widgets.slot.ItemSlot;
import com.cleanroommc.modularui.widgets.slot.ModularSlot;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static applygray.api.util.AE2PatternCompat.*;

/**
 * 按矿辞前缀生成虚拟配方的样板总成。
 *
 * <p>
 * 旧版只支持「单输入前缀 × 单输出前缀」的笛卡尔积，无法表达「锭+粉→板」这类多对多关系。
 * 现在一条规则就是一个 {@link OrePrefixMapping}：输入组与输出组同属一个样板，
 * 组内任一条目在该材料上不可用时整条规则跳过，避免生成残缺样板。
 */
public class MetaTileEntityMEOrePrefixPatternProvider extends MetaTileEntityAEPatternRegistrar
        implements IMEPatternProviderPart {

    /** 魔法前缀：代表材料自身的流体，数量单位为 mB。 */
    public static final String FLUID_PREFIX = "fluid";
    /** AE 处理样板单侧的容量，组大小以此为上限。 */
    public static final int MAX_GROUP_SIZE = 9;
    public static final int MAX_OUTPUT_GROUP_SIZE = 3;

    List<OrePrefixMapping> mappings = new ArrayList<>();
    ArrayList<String> blackList = new ArrayList<>();
    ArrayList<String> whiteTagList = new ArrayList<>();
    ArrayList<String> blackTagList = new ArrayList<>();
    ItemStackHandler extraInput = new ItemStackHandler(8);
    ItemStackHandler extraOutput = new ItemStackHandler(2);

    public MetaTileEntityMEOrePrefixPatternProvider(ResourceLocation metaTileEntityId, int tier) {
        super(metaTileEntityId, tier);
    }

    // ## 序列化

    public static String listToString(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        return String.join(",", list);
    }

    public static List<String> stringToList(String str) {
        List<String> result = new ArrayList<>();
        if (str != null && !str.trim().isEmpty()) {
            for (String part : str.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }

    public static String groupToText(List<PrefixEntry> entries) {
        if (entries == null || entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (PrefixEntry entry : entries) {
            if (sb.length() > 0) sb.append(',');
            sb.append(entry.prefix).append(':').append(entry.amount);
        }
        return sb.toString();
    }

    /** 兼容旧调用名；数量可省略，解析失败不再抛异常。 */
    public static String prefixEntriesToString(List<PrefixEntry> entries) {
        return groupToText(entries);
    }

    public static List<PrefixEntry> parseGroup(String str) {
        List<PrefixEntry> result = new ArrayList<>();
        if (str == null || str.trim().isEmpty()) return result;
        for (String part : str.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            String prefix = trimmed;
            int amount = 1;
            int colon = trimmed.indexOf(':');
            if (colon >= 0) {
                prefix = trimmed.substring(0, colon).trim();
                String amountText = trimmed.substring(colon + 1).trim();
                if (!amountText.isEmpty()) {
                    try {
                        amount = Math.max(1, Integer.parseInt(amountText));
                    } catch (NumberFormatException e) {
                        // 数量手输错误时按 1 处理，不要因此丢掉整条规则
                        amount = 1;
                    }
                }
            }
            if (prefix.isEmpty()) continue;
            result.add(new PrefixEntry(prefix, amount));
        }
        return result;
    }

    public static List<PrefixEntry> stringToPrefixEntries(String str) {
        return parseGroup(str);
    }

    public static String mappingsToString(List<OrePrefixMapping> mappings) {
        if (mappings == null || mappings.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (OrePrefixMapping mapping : mappings) {
            if (mapping == null || mapping.isEmpty()) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(mapping.toText());
        }
        return sb.toString();
    }

    public static List<OrePrefixMapping> stringToMappings(String str) {
        List<OrePrefixMapping> result = new ArrayList<>();
        if (str == null || str.trim().isEmpty()) return result;
        for (String part : str.split(";")) {
            OrePrefixMapping mapping = OrePrefixMapping.parse(part);
            if (mapping != null) {
                result.add(mapping);
            }
        }
        return result;
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity tileEntity) {
        return new MetaTileEntityMEOrePrefixPatternProvider(metaTileEntityId, getTier());
    }

    @Override
    public void renderMetaTileEntity(CCRenderState renderState, Matrix4 translation, IVertexOperation[] pipeline) {
        super.renderMetaTileEntity(renderState, translation, pipeline);
        if (shouldRenderOverlay()) {
            SimpleOverlayRenderer overlay = ApplyGrayTextures.ME_BUFFER_HATCH_OVERLAY;
            overlay.renderSided(getFrontFacing(), renderState, translation, pipeline);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound data) {
        super.writeToNBT(data);
        data.setTag("ExtraInput", this.extraInput.serializeNBT());
        data.setTag("ExtraOutput", this.extraOutput.serializeNBT());
        data.setString("Mappings", mappingsToString(mappings));

        data.setInteger("blackListSize", blackList.size());
        for (int i = 0; i < blackList.size(); i++) {
            data.setString("blackList" + i, blackList.get(i));
        }
        data.setInteger("whiteTagListSize", whiteTagList.size());
        for (int i = 0; i < whiteTagList.size(); i++) {
            data.setString("whiteTagList" + i, whiteTagList.get(i));
        }
        data.setInteger("blackTagListSize", blackTagList.size());
        for (int i = 0; i < blackTagList.size(); i++) {
            data.setString("blackTagList" + i, blackTagList.get(i));
        }

        return data;
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        this.extraInput.deserializeNBT(data.getCompoundTag("ExtraInput"));
        this.extraOutput.deserializeNBT(data.getCompoundTag("ExtraOutput"));

        mappings.clear();
        String mappingText = data.getString("Mappings");
        if (!mappingText.trim().isEmpty()) {
            mappings.addAll(stringToMappings(mappingText));
        } else {
            // 旧存档只有两个前缀列表，按旧版笛卡尔积语义迁移成规则
            List<PrefixEntry> legacyInputs = parseGroup(data.getString("inputPrefixes"));
            List<PrefixEntry> legacyOutputs = parseGroup(data.getString("outputPrefixes"));
            for (PrefixEntry input : legacyInputs) {
                for (PrefixEntry output : legacyOutputs) {
                    mappings.add(new OrePrefixMapping(List.of(input), List.of(output)));
                }
            }
        }

        blackList.clear();
        int size = data.getInteger("blackListSize");
        for (int i = 0; i < size; i++) {
            blackList.add(data.getString("blackList" + i));
        }
        whiteTagList.clear();
        size = data.getInteger("whiteTagListSize");
        for (int i = 0; i < size; i++) {
            whiteTagList.add(data.getString("whiteTagList" + i));
        }
        blackTagList.clear();
        size = data.getInteger("blackTagListSize");
        for (int i = 0; i < size; i++) {
            blackTagList.add(data.getString("blackTagList" + i));
        }
    }

    // ## 样板生成

    @Override
    public void setPatternDetails() {
        patternDetails = new ArrayList<>();
        List<ItemStack> patterns = createPatterns();
        for (int i = 0; i < patterns.size(); i++) {
            ItemStack pattern = patterns.get(i);
            if (pattern.isEmpty()) {
                patternDetails.add(i, null);
                continue;
            }

            IPatternDetails detail = PatternDetailsHelper.decodePattern(pattern, getWorld());
            patternDetails.add(i, detail);
            if (detail != null) {
                ExactPatternInputRegistry.registerPattern(detail);
            }
        }
    }

    @Override
    public List<ItemStack> createPatterns() {
        ArrayList<ItemStack> patterns = new ArrayList<>();
        if (mappings.isEmpty()) {
            return patterns;
        }

        List<MaterialFlag> whiteTag = MaterialFlag.getFlagListByName(whiteTagList);
        List<MaterialFlag> blackTag = MaterialFlag.getFlagListByName(blackTagList);

        for (Material material : GregTechAPI.materialManager.getRegisteredMaterials()) {
            if (blackList.contains(material.toString())) continue;
            if (!MaterialFlag.checkMaterialHasFlag(material, whiteTag, blackTag)) continue;

            for (OrePrefixMapping mapping : mappings) {
                List<ItemStack> inputs = resolveGroup(mapping.inputs, material);
                if (inputs == null) continue;
                List<ItemStack> outputs = resolveGroup(mapping.outputs, material);
                if (outputs == null) continue;

                ItemStack[] inputArray = inputs.toArray(new ItemStack[0]);
                ItemStack pattern = createProcessingPattern(
                        inputArray,
                        outputs.toArray(new ItemStack[0]),
                        false,
                        containsFluid(inputArray));
                if (!pattern.isEmpty()) {
                    patterns.add(pattern);
                }
            }
        }
        return patterns;
    }

    /**
     * 把一组前缀条目解析成实际堆栈；任一条目在该材料上不存在就返回 null，整条规则跳过。
     */
    @Nullable
    private static List<ItemStack> resolveGroup(List<PrefixEntry> entries, Material material) {
        if (entries.isEmpty()) return null;
        List<ItemStack> result = new ArrayList<>(entries.size());
        for (PrefixEntry entry : entries) {
            ItemStack stack;
            if (FLUID_PREFIX.equals(entry.prefix)) {
                if (!material.hasFluid()) return null;
                FluidStack fluid = material.getFluid(entry.amount);
                if (fluid == null || fluid.amount <= 0) return null;
                stack = toFluidDrop(fluid);
            } else {
                OrePrefix prefix = OrePrefix.getPrefix(entry.prefix);
                if (prefix == null) return null;
                stack = OreDictUnifier.get(prefix, material, entry.amount);
            }
            if (stack == null || stack.isEmpty()) return null;
            result.add(stack);
        }
        return result;
    }

    // ## UI

    @Override
    public boolean usesMui2() {
        return true;
    }

    @Override
    public ModularPanel buildUI(PosGuiData guiData, PanelSyncManager guiSyncManager, UISettings settings) {
        int backgroundWidth = 9 * 18 + 18 + 14 + 5 + 18;
        int backgroundHeight = 18 + 18 * 8 + 94;
        guiSyncManager.registerSlotGroup("item_inv", extraInput.getSlots());
        EntityPlayer player = guiSyncManager.getPlayer();

        List<List<IWidget>> extraSlots = new ArrayList<>();
        extraSlots.add(new ArrayList<>());
        for (int i = 0; i < extraInput.getSlots(); i++) {
            extraSlots.get(0)
                    .add(new ItemSlot()
                            .slot(new ModularSlot(extraInput, i)
                                    .slotGroup("item_inv")
                                    .accessibility(true, true))
                            .background(GTGuiTextures.SLOT));
        }
        extraSlots.add(new ArrayList<>());
        for (int i = 0; i < extraOutput.getSlots(); i++) {
            extraSlots.get(1)
                    .add(new ItemSlot()
                            .slot(new ModularSlot(extraOutput, i)
                                    .slotGroup("item_inv")
                                    .accessibility(true, true))
                            .background(GTGuiTextures.SLOT));
        }

        StringSyncValue mappingsValue = new StringSyncValue(
                () -> mappingsToString(mappings),
                str -> {
                    if (str != null) {
                        List<OrePrefixMapping> parsed = stringToMappings(str);
                        mappings.clear();
                        mappings.addAll(parsed);
                        // 规则变了就重推，否则 AE 端要等到下次网络刷新
                        setNeedPatternSync(true);
                    }
                });
        guiSyncManager.syncValue("ore_prefix_mappings", mappingsValue);

        StringSyncValue blackListValue = new StringSyncValue(
                () -> listToString(blackList),
                this::applyBlackList);
        StringSyncValue whiteTagListValue = new StringSyncValue(
                () -> listToString(whiteTagList),
                this::applyWhiteTags);
        StringSyncValue blackTagListValue = new StringSyncValue(
                () -> listToString(blackTagList),
                this::applyBlackTags);
        guiSyncManager.syncValue("blackList", blackListValue);
        guiSyncManager.syncValue("whiteTagList", whiteTagListValue);
        guiSyncManager.syncValue("blackTagList", blackTagListValue);

        // ### 映射编辑页
        TextFieldWidget inputGroupField = new TextFieldWidget()
                .width(168)
                .height(18)
                .setTextColor(Color.WHITE.darker(1))
                .background(GTGuiTextures.DISPLAY)
                .tooltip(t -> t.addLine(IKey.lang(key("ui.hint.format"))));

        TextFieldWidget outputGroupField = new TextFieldWidget()
                .width(168)
                .height(18)
                .setTextColor(Color.WHITE.darker(1))
                .background(GTGuiTextures.DISPLAY)
                .tooltip(t -> t.addLine(IKey.lang(key("ui.hint.format"))));

        Flow mappingPage = Flow.column()
                .top(0)
                .widthRel(1f)
                .leftRel(0.5f)
                .child(new Grid()
                        .height(36)
                        .leftRel(0.5f)
                        .widthRel(1.0f)
                        .grid(extraSlots))
                .child(new TextWidget<>(IKey.lang(key("ui.group.input"))).pos(10, 38))
                .child(inputGroupField.pos(10, 50))
                .child(new ButtonWidget<>()
                        .size(18, 18)
                        .overlay(GTGuiTextures.PLUS)
                        .tooltip(t -> t.addLine(IKey.lang(key("ui.button.add"))))
                        .onMousePressed(b -> addMapping(player, inputGroupField, outputGroupField, mappingsValue))
                        .pos(182, 50))
                .child(new TextWidget<>(IKey.lang(key("ui.group.output"))).pos(10, 70))
                .child(outputGroupField.pos(10, 82))
                .child(new ButtonWidget<>()
                        .size(18, 18)
                        .overlay(GTGuiTextures.MINUS)
                        .tooltip(t -> t.addLine(IKey.lang(key("ui.button.remove"))))
                        .onMousePressed(b -> removeMapping(player, inputGroupField, outputGroupField, mappingsValue))
                        .pos(182, 82))
                .child(new ScrollableTextWidget()
                        .top(104)
                        .height(40)
                        .autoUpdate(true)
                        .leftRel(0.5f)
                        .widthRel(0.94f)
                        .alignment(Alignment.TopLeft)
                        .textBuilder(text -> {
                            text.addLine(IKey.lang(key("ui.list.header")));
                            if (mappings.isEmpty()) {
                                text.addLine(IKey.lang(key("ui.list.empty")));
                                return;
                            }
                            for (int i = 0; i < mappings.size(); i++) {
                                text.addLine((i + 1) + ". " + mappings.get(i).toText());
                            }
                        }));

        // ### 过滤页
        List<String> filterLabelKeys = List.of("ui.filter.blacklist", "ui.filter.white", "ui.filter.black");
        List<List<String>> filterTargets = List.of(blackList, whiteTagList, blackTagList);
        List<StringSyncValue> filterValues = List.of(blackListValue, whiteTagListValue, blackTagListValue);

        Flow filterPage = Flow.column()
                .top(0)
                .widthRel(1f)
                .leftRel(0.5f);
        for (int i = 0; i < filterLabelKeys.size(); i++) {
            int y = 4 + i * 36;
            String labelKey = filterLabelKeys.get(i);
            List<String> target = filterTargets.get(i);
            StringSyncValue value = filterValues.get(i);

            TextFieldWidget field = new TextFieldWidget()
                    .width(150)
                    .height(18)
                    .setTextColor(Color.WHITE.darker(1))
                    .background(GTGuiTextures.DISPLAY);
            filterPage.child(new TextWidget<>(IKey.lang(key(labelKey))).pos(10, y));
            filterPage.child(field.pos(10, y + 12));
            filterPage.child(new ButtonWidget<>()
                    .size(18, 18)
                    .overlay(GTGuiTextures.PLUS)
                    .tooltip(t -> t.addLine(IKey.lang(key("ui.button.add"))))
                    .onMousePressed(b -> {
                        String entry = field.getText().trim();
                        if (!entry.isEmpty() && !target.contains(entry)) {
                            target.add(entry);
                            value.setValue(listToString(target));
                            message(player, "ui.msg.added", entry);
                            field.setText("");
                        }
                        return true;
                    })
                    .pos(166, y + 12));
            filterPage.child(new ButtonWidget<>()
                    .size(18, 18)
                    .overlay(GTGuiTextures.MINUS)
                    .tooltip(t -> t.addLine(IKey.lang(key("ui.button.remove"))))
                    .onMousePressed(b -> {
                        String entry = field.getText().trim();
                        if (!entry.isEmpty() && target.remove(entry)) {
                            value.setValue(listToString(target));
                            message(player, "ui.msg.removed", entry);
                            field.setText("");
                        }
                        return true;
                    })
                    .pos(184, y + 12));
        }
        filterPage.child(new ScrollableTextWidget()
                .top(112)
                .height(36)
                .autoUpdate(true)
                .leftRel(0.5f)
                .widthRel(0.94f)
                .alignment(Alignment.TopLeft)
                .textBuilder(text -> {
                    text.addLine(I18n.format(key("ui.filter.white")) + ": " + listToString(whiteTagList));
                    text.addLine(I18n.format(key("ui.filter.black")) + ": " + listToString(blackTagList));
                    text.addLine(I18n.format(key("ui.filter.blacklist")) + ": " + listToString(blackList));
                }));

        // ### 网络代理页
        StringSyncValue displayXValue = new StringSyncValue(
                () -> "X:" + AEProxy_pos.getX(),
                str -> applyProxyCoordinate(str, 'X'));
        StringSyncValue displayYValue = new StringSyncValue(
                () -> "Y:" + AEProxy_pos.getY(),
                str -> applyProxyCoordinate(str, 'Y'));
        StringSyncValue displayZValue = new StringSyncValue(
                () -> "Z:" + AEProxy_pos.getZ(),
                str -> applyProxyCoordinate(str, 'Z'));

        BooleanSyncValue useProxyStateValue = new BooleanSyncValue(() -> useProxy, val -> useProxy = val);
        guiSyncManager.syncValue("useProxyStateValue", useProxyStateValue);

        List<List<IWidget>> proxyRows = new ArrayList<>();
        List<IWidget> proxyRow = new ArrayList<>();
        proxyRow.add(new ToggleButton()
                .width(20)
                .height(20)
                .value(new BoolValue.Dynamic(useProxyStateValue::getBoolValue, useProxyStateValue::setBoolValue))
                .overlay(ApplyGrayGuiTextures.PROXY_OVERLAY)
                .tooltip(t -> t.addLine(IKey.lang(key("ui.proxy.mode")))));
        proxyRow.add(coordinateField(displayXValue, 'X', 0.25f));
        proxyRow.add(coordinateField(displayYValue, 'Y', 0.25f));
        proxyRow.add(coordinateField(displayZValue, 'Z', 0.25f));
        proxyRows.add(proxyRow);

        BooleanSyncValue blockStateValue = new BooleanSyncValue(this::isBlockedMode, this::setBlockedMode);
        guiSyncManager.syncValue("block_state", blockStateValue);
        BooleanSyncValue collapseStateValue = new BooleanSyncValue(this::isAutoCollapse, this::setAutoCollapse);
        guiSyncManager.syncValue("collapse_state", collapseStateValue);
        BooleanSyncValue exportStateValue = new BooleanSyncValue(this::isExport, this::setExport);
        guiSyncManager.syncValue("export_state", exportStateValue);

        var controller = new PagedWidget.Controller();
        guiSyncManager.syncValue("page_controller", new PagedWidgetSyncHandler(controller));

        return GTGuis.createPanel(this, backgroundWidth, backgroundHeight)
                .child(Flow.row()
                        .name("tab row")
                        .widthRel(1f)
                        .leftRel(0.5f)
                        .margin(3, 0)
                        .coverChildrenHeight()
                        .topRel(0f, 3, 1f)
                        .child(new PageButton(0, controller)
                                .tab(GuiTextures.TAB_TOP, 0)
                                .addTooltipLine(IKey.lang(key("ui.tab.mapping")))
                                .overlay(HATCH))
                        .child(new PageButton(1, controller)
                                .tab(GuiTextures.TAB_TOP, 0)
                                .addTooltipLine(IKey.lang(key("ui.tab.filter")))
                                .overlay(FILTER))
                        .child(new PageButton(2, controller)
                                .tab(GuiTextures.TAB_TOP, 0)
                                .addTooltipLine(IKey.lang(key("ui.tab.proxy")))
                                .overlay(PROXY))
                        .child(new PageButton(3, controller)
                                .tab(GuiTextures.TAB_TOP, 0)
                                .addTooltipLine(IKey.lang(key("ui.tab.link")))
                                .overlay(LINK)))
                .child(IKey.lang(getMetaFullName()).asWidget().pos(5, 5))
                .child(SlotGroupWidget.playerInventory(false).left(7).bottom(7))
                .child(new PagedWidget<>()
                        .top(18)
                        .margin(0)
                        .widthRel(1f)
                        .controller(controller)
                        .addPage(mappingPage)
                        .addPage(filterPage)
                        .addPage(Flow.column()
                                .top(0)
                                .widthRel(1f)
                                .leftRel(0.5f)
                                .child(new Grid()
                                        .height(25)
                                        .minElementMargin(0, 0)
                                        .minColWidth((int) (0.24f * backgroundWidth))
                                        .minRowHeight(18)
                                        .grid(proxyRows))
                                .childIf(useProxy, () -> Flow.column()
                                        .widthRel(1f)
                                        .top(30)
                                        .margin(5, 0)
                                        .child(new TextWidget<>(IKey.lang(key("ui.proxy.wireless"))))
                                        .childIf(useProxy, () -> {
                                            TileEntity tileEntity = this.getWorld().getTileEntity(AEProxy_pos);
                                            if (tileEntity != null) {
                                                return Flow.column()
                                                        .widthRel(1f)
                                                        .child(new TextWidget<>(IKey.lang(key("ui.proxy.connected"))))
                                                        .child(new TextWidget<>(IKey.dynamic(() ->
                                                                "位置:" + AEProxy_pos.getX() + ", " +
                                                                        AEProxy_pos.getY() + ", " +
                                                                        AEProxy_pos.getZ())))
                                                        .child(new TextWidget<>(IKey.dynamic(() ->
                                                                "名称:" + tileEntity.getBlockType().getLocalizedName())));
                                            }
                                            return Flow.column()
                                                    .widthRel(1f)
                                                    .child(new TextWidget<>(IKey.lang(key("ui.proxy.missing"))))
                                                    .child(new TextWidget<>(IKey.dynamic(() ->
                                                            "坐标:" + AEProxy_pos.getX() + ", " +
                                                                    AEProxy_pos.getY() + ", " +
                                                                    AEProxy_pos.getZ())));
                                        }))
                                .childIf(!useProxy, () -> Flow.column()
                                        .widthRel(1f)
                                        .top(30)
                                        .margin(5, 0)
                                        .child(new TextWidget<>(IKey.lang(key("ui.proxy.wired"))))))
                        .addPage(Flow.column()
                                .top(0)
                                .widthRel(1f)
                                .leftRel(0.5f)
                                .margin(5, 0)
                                .child(new TextWidget<>(IKey.lang(key("ui.link.title"))))
                                .child(new TextWidget<>(IKey.dynamic(this::getLinkStatusText)))
                                .child(new TextWidget<>(IKey.dynamic(this::getLinkTargetText)))
                                .child(new TextWidget<>(IKey.dynamic(this::getLinkBufferText)))
                                .child(new TextWidget<>(IKey.lang(key("ui.link.hint"))))))
                .child(Flow.column()
                        .pos(backgroundWidth - 7 - 36, backgroundHeight - 18 * 4 - 7 - 5)
                        .width(36).height(18 * 4 + 5)
                        .child(GTGuiTextures.getLogo(getUITheme()).asWidget()
                                .top(18 * 3 + 5)
                                .size(17))
                        .child(new ToggleButton()
                                .top(18 * 2)
                                .value(new BoolValue.Dynamic(blockStateValue::getBoolValue,
                                        blockStateValue::setBoolValue))
                                .overlay(ApplyGrayGuiTextures.BUTTON_DUAL_OUTPUT)
                                .tooltip(t -> t.addLine(IKey.lang(key("ui.mode.blocked")))))
                        .child(new ToggleButton()
                                .top(18 * 2)
                                .left(18)
                                .value(new BoolValue.Dynamic(exportStateValue::getBoolValue,
                                        exportStateValue::setBoolValue))
                                .overlay(ApplyGrayGuiTextures.EXPORT_OVERLAY)
                                .tooltip(t -> t.addLine(IKey.lang(key("ui.mode.export")))))
                        .child(new ToggleButton()
                                .top(18)
                                .value(new BoolValue.Dynamic(collapseStateValue::getBoolValue,
                                        collapseStateValue::setBoolValue))
                                .overlay(ApplyGrayGuiTextures.BUTTON_DUAL_COLLAPSE)
                                .tooltip(t -> t.addLine(IKey.lang(key("ui.mode.collapse")))))
                        .child(new ButtonWidget<>()
                                .top(0)
                                .onMousePressed(b -> {
                                    handlePatternGenerate(player);
                                    return true;
                                })
                                .overlay(ApplyGrayGuiTextures.PATTERN_OVERLAY)
                                .tooltip(t -> t.addLine(IKey.lang(key("ui.generate.button"))))));
    }

    private static String key(String suffix) {
        return "gregtech.machine.me_ore_prefix_pattern_provider." + suffix;
    }

    private void message(EntityPlayer player, String suffix, String arg) {
        player.sendMessage(new TextComponentTranslation(key(suffix), arg));
    }

    // ## 映射编辑

    private boolean addMapping(EntityPlayer player, TextFieldWidget inputField, TextFieldWidget outputField,
                               StringSyncValue mappingsValue) {
        OrePrefixMapping mapping = OrePrefixMapping.parse(inputField.getText() + ">" + outputField.getText());
        if (mapping == null) {
            message(player, "ui.msg.invalid", inputField.getText().trim() + " → " + outputField.getText().trim());
            return true;
        }
        String unknown = firstUnknownPrefix(mapping);
        if (unknown != null) {
            message(player, "ui.msg.unknown_prefix", unknown);
            return true;
        }
        if (mapping.inputs.size() > MAX_GROUP_SIZE) {
            message(player, "ui.msg.too_many_inputs", String.valueOf(MAX_GROUP_SIZE));
            return true;
        }
        if (mapping.outputs.size() > MAX_OUTPUT_GROUP_SIZE) {
            message(player, "ui.msg.too_many_outputs", String.valueOf(MAX_OUTPUT_GROUP_SIZE));
            return true;
        }

        boolean updated = mappings.removeIf(existing -> existing.equals(mapping));
        mappings.add(mapping);
        mappingsValue.setValue(mappingsToString(mappings));
        message(player, updated ? "ui.msg.updated" : "ui.msg.added", mapping.toText());
        inputField.setText("");
        outputField.setText("");
        return true;
    }

    private boolean removeMapping(EntityPlayer player, TextFieldWidget inputField, TextFieldWidget outputField,
                                  StringSyncValue mappingsValue) {
        OrePrefixMapping mapping = OrePrefixMapping.parse(inputField.getText() + ">" + outputField.getText());
        if (mapping == null) {
            message(player, "ui.msg.invalid", inputField.getText().trim() + " → " + outputField.getText().trim());
            return true;
        }
        if (!mappings.removeIf(existing -> existing.equals(mapping))) {
            message(player, "ui.msg.not_found", mapping.toText());
            return true;
        }
        mappingsValue.setValue(mappingsToString(mappings));
        message(player, "ui.msg.removed", mapping.toText());
        inputField.setText("");
        outputField.setText("");
        return true;
    }

    @Nullable
    private static String firstUnknownPrefix(OrePrefixMapping mapping) {
        for (PrefixEntry entry : mapping.inputs) {
            if (!FLUID_PREFIX.equals(entry.prefix) && OrePrefix.getPrefix(entry.prefix) == null) {
                return entry.prefix;
            }
        }
        for (PrefixEntry entry : mapping.outputs) {
            if (!FLUID_PREFIX.equals(entry.prefix) && OrePrefix.getPrefix(entry.prefix) == null) {
                return entry.prefix;
            }
        }
        return null;
    }

    // ## 同步值回写

    private void applyBlackList(String str) {
        if (str == null) return;
        blackList.clear();
        blackList.addAll(stringToList(str));
        setNeedPatternSync(true);
    }

    private void applyWhiteTags(String str) {
        if (str == null) return;
        whiteTagList.clear();
        whiteTagList.addAll(stringToList(str));
        setNeedPatternSync(true);
    }

    private void applyBlackTags(String str) {
        if (str == null) return;
        blackTagList.clear();
        blackTagList.addAll(stringToList(str));
        setNeedPatternSync(true);
    }

    private void applyProxyCoordinate(String str, char axis) {
        String body = str;
        if (body.length() >= 2 && body.charAt(1) == ':' && Character.toUpperCase(body.charAt(0)) == axis) {
            body = body.substring(2);
        }
        try {
            int value = Integer.parseInt(body.trim());
            AEProxy_pos = switch (axis) {
                case 'X' -> new BlockPos(value, AEProxy_pos.getY(), AEProxy_pos.getZ());
                case 'Y' -> new BlockPos(AEProxy_pos.getX(), value, AEProxy_pos.getZ());
                default -> new BlockPos(AEProxy_pos.getX(), AEProxy_pos.getY(), value);
            };
        } catch (NumberFormatException e) {
            // 输入过程中的半成品坐标不写回
        }
    }

    private static TextFieldWidget coordinateField(StringSyncValue value, char axis, float widthRel) {
        String prefix = axis + ":";
        return new TextFieldWidget()
                .widthRel(widthRel)
                .height(20)
                .setTextColor(Color.WHITE.darker(1))
                .setValidator(str -> {
                    if (!str.startsWith(prefix)) {
                        if (str.isEmpty()) {
                            return prefix;
                        }
                        return prefix + str;
                    }
                    String number = str.substring(2);
                    if (number.isEmpty()) {
                        return str;
                    }
                    try {
                        Long.parseLong(number.trim());
                        return str;
                    } catch (NumberFormatException e) {
                        return value.getValue();
                    }
                })
                .value(value)
                .background(GTGuiTextures.DISPLAY);
    }

    // ## 样板推送与链接页

    private void handlePatternGenerate(EntityPlayer player) {
        int patternCount = refreshGeneratedPatternDetails();
        if (patternCount <= 0) {
            setNeedPatternSync(true);
            player.sendStatusMessage(new TextComponentTranslation(key("ui.generate.none")), true);
            return;
        }

        setNeedPatternSync(true);
        boolean waitingForSync = requestPatternUpdate();
        setNeedPatternSync(waitingForSync);

        String messageKey = waitingForSync
                ? key("ui.generate.queued")
                : hasMaster()
                        ? key("ui.generate.success")
                        : key("ui.generate.unlinked");
        player.sendStatusMessage(new TextComponentTranslation(messageKey, patternCount), true);
    }

    private int refreshGeneratedPatternDetails() {
        setPatternDetails();
        if (patternDetails == null) {
            return 0;
        }
        int patternCount = 0;
        for (Object detail : patternDetails) {
            if (detail != null) {
                patternCount++;
            }
        }
        return patternCount;
    }

    private String getLinkStatusText() {
        if (hasMaster() && masterPos != null) {
            return I18n.format(key("ui.link.status.linked"),
                    masterPos.getX(), masterPos.getY(), masterPos.getZ());
        }
        if (masterSet && masterPos != null) {
            return I18n.format(key("ui.link.status.waiting"),
                    masterPos.getX(), masterPos.getY(), masterPos.getZ());
        }
        return I18n.format(key("ui.link.status.none"));
    }

    private String getLinkTargetText() {
        if (!hasMaster()) {
            return "";
        }
        return I18n.format(key("ui.link.status.target"), I18n.format(master.getMetaFullName()));
    }

    private String getLinkBufferText() {
        if (!hasMaster()) {
            return "";
        }
        int usedBuffers = 0;
        List<MetaTileEntityMEPatternProvider.PatternBuffer> bufferPool = master.getBufferPool();
        if (bufferPool == null) {
            return "";
        }
        for (MetaTileEntityMEPatternProvider.PatternBuffer buffer : bufferPool) {
            if (!buffer.isEmpty()) usedBuffers++;
        }
        return I18n.format(key("ui.link.status.buffers"), usedBuffers, master.getBufferCount());
    }

    @Override
    public MultiblockControllerBase getController() {
        return super.getController();
    }

    @Override
    public String getShowName() {
        return getMetaFullName();
    }

    @Nullable
    @Override
    public IItemHandler getPatternSlot() {
        // 样板不落在槽位里，全部由规则在运行时生成
        return null;
    }

    @Override
    public void getSubItems(CreativeTabs creativeTab, NonNullList<ItemStack> subItems) {
        super.getSubItems(creativeTab, subItems);
    }

    @Override
    public void addInformation(ItemStack stack, @Nullable World player, @NotNull List<String> tooltip,
                               boolean advanced) {
        for (int i = 1; i <= 6; i++) {
            tooltip.add(I18n.format(key("tooltip." + i)));
        }
        tooltip.add(I18n.format("gregtech.machine.me_pattern.tooltip.2"));
        tooltip.add(I18n.format("gregtech.machine.me.data_stick_proxy"));
        tooltip.add(I18n.format("gregtech.universal.enabled"));
    }

    // ## 数据模型

    public static class PrefixEntry {

        public String prefix;
        public int amount;

        public PrefixEntry(String prefix, int amount) {
            this.prefix = prefix;
            this.amount = amount;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof PrefixEntry other)) return false;
            return amount == other.amount && prefix.equals(other.prefix);
        }

        @Override
        public int hashCode() {
            return Objects.hash(prefix, amount);
        }

        @Override
        public String toString() {
            return prefix + ":" + amount;
        }
    }

    /**
     * 一条「矿辞组 → 矿辞组」规则。
     *
     * <p>
     * 组内同名前缀在构造时合并，因此 `ingot:1,ingot:2` 等价于 `ingot:3`；
     * 也让删除按钮可以直接按内容匹配已有规则。
     */
    public static class OrePrefixMapping {

        public final List<PrefixEntry> inputs;
        public final List<PrefixEntry> outputs;

        public OrePrefixMapping(List<PrefixEntry> inputs, List<PrefixEntry> outputs) {
            this.inputs = normalize(inputs);
            this.outputs = normalize(outputs);
        }

        @Nullable
        public static OrePrefixMapping parse(String text) {
            if (text == null) return null;
            String trimmed = text.trim();
            int arrow = trimmed.indexOf('>');
            if (arrow <= 0 || arrow == trimmed.length() - 1) return null;
            List<PrefixEntry> inputs = parseGroup(trimmed.substring(0, arrow));
            List<PrefixEntry> outputs = parseGroup(trimmed.substring(arrow + 1));
            if (inputs.isEmpty() || outputs.isEmpty()) return null;
            return new OrePrefixMapping(inputs, outputs);
        }

        private static List<PrefixEntry> normalize(List<PrefixEntry> entries) {
            List<PrefixEntry> result = new ArrayList<>();
            for (PrefixEntry entry : entries) {
                if (entry == null || entry.prefix == null || entry.prefix.trim().isEmpty()) continue;
                String prefix = entry.prefix.trim();
                int amount = Math.max(1, entry.amount);
                PrefixEntry existing = null;
                for (PrefixEntry candidate : result) {
                    if (candidate.prefix.equals(prefix)) {
                        existing = candidate;
                        break;
                    }
                }
                if (existing != null) {
                    existing.amount += amount;
                } else {
                    result.add(new PrefixEntry(prefix, amount));
                }
            }
            return result;
        }

        public boolean isEmpty() {
            return inputs.isEmpty() || outputs.isEmpty();
        }

        public String toText() {
            return groupToText(inputs) + " > " + groupToText(outputs);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof OrePrefixMapping other)) return false;
            return inputs.equals(other.inputs) && outputs.equals(other.outputs);
        }

        @Override
        public int hashCode() {
            return Objects.hash(inputs, outputs);
        }

        @Override
        public String toString() {
            return toText();
        }
    }
}
