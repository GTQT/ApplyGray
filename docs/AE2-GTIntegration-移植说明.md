# AE2-GTIntegration 移植说明

来源：`AE2-GTIntegration`（modid `ae2gtaddon`，依赖 AE2 1.1.0）。目标：本工程，AE2 Supergiant **1.1.0** + GTQT GregTech。
移植保留四项功能，代码按本工程分层重写，未保留原 addon 的包名与注册方式。

## 功能与落点

| 功能 | 代码 |
|---|---|
| 样板供应器「电路卡」 | `applygray/common/items/ApplyGrayAeItems`、`applygray/integration/ae2/circuit/GregTechCircuitPatternDetails`、`applygray/mixins/supergiant/MixinPatternProviderLogic`、`MixinPatternProviderP2PTunnelPart`、`MixinPseudoPatternDetails` |
| 扩展样板编码终端（32→8 / 8→32） | `applygray/integration/ae2/encoding/*`（`ExtendedEncodingMode/Draft/Logic/TermPart`、`ContainerExtendedEncodingTerm`、`ExtendedEncodingTermGuiHandler`）、`applygray/client/gui/ae2/*`、`MixinAEProcessingPattern` |
| HEI 导入时忽略集成电路 | `applygray/client/handler/HeiPatternInputFilterHandler`、`MixinProcessingEncodingPanel`、`MixinEncodingModePanel`、`MixinContainerPatternEncodingTerm`、`ExtendedPatternEncodingRecipeTransferHandler` |
| HEI 导入时重复物品自动改名 | `applygray/client/handler/HeiPatternAutoNamingHandler`、`applygray/integration/jei/HeiPatternItemAutoNamer` |

## 前置升级：AE2 1.0.16 → 1.1.0

- `gradle.properties`：`ae2_supergiant_jar = AppliedEnergistics2-Supergiant-1.1.0.jar`（jar 放 `libs/`）。
- `dependencies.gradle`：`mezz:jei` 由 `4.31.2` 升到 `4.35.1`。AE2 1.1.0 的 `ae2.mod.mixins.json` 含
  `hei.InvokerGhostIngredientDragManager`，需要 JEI 的 `GhostIngredientDragManager.handleMouseClicked(II)Z`；
  4.31.2 没有该方法，客户端会以 `InvalidAccessorException` 崩在 JEI 上。
- 源码参照：`E:\模组开发\GTQT\ae2-ref-1.1.0`（tag `v1.1.0`，commit `ad49f8113`）。

### 1.1.0 的 API 变动与对应改法

- **数量统一为 `long`**：`PatternDetailsHelper.MAX_PROCESSING_PATTERN_AMOUNT` 与 `ContainerCraftAmount.MAX_AUTO_CRAFT_AMOUNT`
  被删除。`GuiSetExtendedProcessingPatternAmount.getMaxAmount()` 改用 `Integer.MAX_VALUE`；
  `MixinContainerCraftAmountRecipeMapGeneration` 用本地 `APPLYGRAY_MAX_AUTO_CRAFT_AMOUNT = 1_000_000L` 保留原上限语义；
  `GuiRecipePatternCraftAmount`、`GuiRecipePatternGenerationTree`、`IRecipePatternGeneration`、
  `UplinkPlanToken` 的金额参数全部改为 `long`，`NumberEntryWidget.getIntValue()` 改 `getLongValue()`，
  客户端动作注册由 `Integer.class` 改 `Long.class`。
- **新增 `PatternRecipeTransferEvent`**：1.0.16 时期用 `@Inject("transferRecipe", RETURN)` 的
  `MixinPatternEncodingRecipeTransferHandlerHei` 已删除，改为在 `ae2.api.client.PatternRecipeTransferEvent` 上
  订阅 `HeiPatternAutoNamingHandler` / `HeiPatternInputFilterHandler`（在 `ApplyGrayClientProxy.init` 注册）。
  `DataGen` 之外没有其它注入点。
- **`ContainerCraftAmount` 换包**：`ae2.container.implementations.ContainerCraftAmount`，`initialAmount` 为 `long`。
- **`ContainerPatternEncodingTerm` 的 16 处差异**：仅同步了 `renameProcessingPatternItem` 的新语义
  （`itemKey.toStack()` + `new GenericStack(AEItemKey.of(renamed), stack.amount())`，旧写法会截断数量）。
  其余（`ContainerConstants.EMPTY_CONTAINER`、`@NotNull`、`resolveCraftingRecipe()` 改 `void`）不影响我们的扩展容器。

## 保留的兼容层及原因

- `src/main/java/ae2/container/me/patternencode/ExtendedProviderSelectionBridge.java` 必须在 AE2 包内：
  1.1.0 的 `ProviderSelectionSession.Host` 仍是包私有接口，外部类无法实现，桥接类把
  `syncProviderSelectionOverlay(ProviderSelectionOverlayOpenRequest)` 适配到容器需要的 `(long, String, String)`。
- `ContainerExtendedEncodingTerm` 仍是「AE2 上游 `ContainerPatternEncodingTerm` + 本工程扩展槽位」的独立实现，
  因为扩展布局（32 输入 / 32 输出）改变了槽位数组与可见性判断。
- `GuiSetExtendedProcessingPatternAmount` 仍是 AE2 同名类的复制，差别是本工程版本调用 `setMaxValue` 并夹取上限
  （AE2 1.1.0 的实现不设上限）。

## 样板归属检查：StructureLib 提供的扩展点

`PatternProviderLogic.pushPattern` 与 `canMergePatternPushBasic` 用 `this.patterns.contains(details)` 判断样板是否属于自己。
装了电路卡后 `getAvailablePatterns()` 返回的是隐藏集成电路的 `GregTechCircuitPatternDetails` 包装，与列表里保存的原始
样板不相等，这两处检查会拒绝推送。`libs/structurelib-1.0.0.jar` 的 `MixinPatternProviderLogicSmartCopy` 已经用
`@Redirect` 接管了这两个 `ObjectList.contains` 调用（优先级 1000），本工程不能再注册同名 redirect。

所以扩展点放在 StructureLib 侧（源码树 `E:\模组开发\GTQT\StructureLib`）：

- 新增 `meowmel.structurelib.integration.ae2.PatternDetailsAcceptor`，方法
  `boolean applygray$acceptPattern(ObjectList<?> storedPatterns, Object candidate)`；
- `MixinPatternProviderLogicSmartCopy` 的两个 redirect 追加
  `|| applygray$acceptDerivedView(localPatterns, pattern)`，后者判断 `this instanceof PatternDetailsAcceptor` 并转发。

本工程 `MixinPatternProviderLogic` 实现该接口，把判断交给 `GregTechCircuitPatternDetails.containsPattern`；
`GregTechCircuitPatternDetails.equals` 保持对称语义（只比较两个包装，不再回退到 `delegate.equals(obj)`）。
`libs/structurelib-1.0.0.jar` 由 StructureLib 的 `./gradlew build --offline -x test` 重新构建后覆盖。

选按实例接口而不是全局 matcher 注册表：没有全局可变状态和加载顺序依赖，且只在装了该 mixin 的 provider 上生效。

## 样板供应器目标显示名：仓室跟随控制器

`PatternContainerGroup.fromMachine` 决定 AE2 终端里样板供应器目标显示什么名字。`MixinPatternContainerGroup` 在
`RETURN` 处改写：

- 目标方块是 GT 的 `MetaTileEntityHolder`，其机器为 `MetaTileEntityMultiblockNotifiablePart` 且已挂到控制器时，
  显示控制器名字，玩家不必再逐个给仓室改名；
- 未成型的仓室与其它 GT 机器保持自身名字；机器或控制器有自定义名时优先自定义名（与机器 GUI 一致）。

图标仍取目标方块自身，因此同一控制器下不同类型的仓室会各占一条同名条目。

## 移植过程中修掉的既有问题

1. `applygray/common/ApplyGrayMetaTileEntities.java`：Grid Energy Hatches 每个电压等级都用同一个 MTE ID 注册
   （300/315/…），启动即 `Tried to reassign id 300 …`。已改为 `基址 + tier`。
2. `applygray/client/Ae2ItemModels.java`：物品染色只能在 `init` 阶段注册，`ModelRegistryEvent` 时
   `Minecraft.getItemColors()` 仍为 null（AE2 自己也是在 `init` 里注册）；代理新增 `init(event)` 钩子调用。
3. 测试源目录：`PatternBufferFluidRestoreTest` / `PatternBufferItemSlotValidatorTest` 还留在 `98ea342`
   迁移前的 `gregtech...` 包下，前者调用 package-private 的 `PatternBuffer.restorePersistedFluid` 编译不过。
   已把两个测试移到 `src/test/java/applygray/common/metatileentities/multiblock/multiblockpart/` 并改包名。

## 验证

- `./gradlew compileJava --offline` 通过（AE2 1.1.0 + JEI 4.35.1）。
- `./gradlew runServer --offline`：加载 21 个模组，`mixins.applygray.mod.json (34)` 全部应用，无 skipped inject。
- `./gradlew runClient --offline`：加载 22 个模组，`Sound engine started`，无 applygray / JEI mixin 失败。
- `./gradlew test --offline`：131 个测试全部通过。
- 尚未验证：进入存档后打开扩展终端、插入电路卡后的实际推送行为（需要人工在游戏内确认）。
