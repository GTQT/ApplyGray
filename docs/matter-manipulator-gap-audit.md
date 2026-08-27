# 物质操纵器移植遗漏与行为差异审计

## 1. 审计范围

本审计对照以下两个固定快照：

- 源项目：`D:\mc\modgit\MatterManipulator`，提交 `46cbd327d97fd78429f5e5e7d176206d01e7e02a`。
- 目标项目：`D:\mc\modgit\ApplyGray`，提交 `a85be63fe42174034ce3a98159318ce59406b1a7`。
- 目标运行环境：Minecraft 1.12.2、Cleanroom、目标 GregTech 与 Applied Energistics 2 Supergiant。
- 规格基线：[matter-manipulator-complete-port.md](matter-manipulator-complete-port.md)。

本文件回答“还有多少移植遗漏和不一样”，不是把所有目标代码重新描述一遍。结论按以下状态标记：

| 标记 | 含义 |
| --- | --- |
| **已确认缺失** | 源码中有源行为，目标没有等价实现。 |
| **行为不同** | 两边都有实现，但语义、边界或交互不同。 |
| **有意拒绝** | 目标明确拒绝该状态，这是当前安全架构边界，不是意外 bug；仍属于“不完全等价”。 |
| **未验证** | 目标代码存在，但尚未通过对应游戏内或跨重载验收。 |
| **已对齐** | 已完成目标实现；列出是为了避免重复追查。 |

### 1.1 总结计数

- **14 项源码已确认的核心差异/缺失**，其中目标代码已按目标 API 完成可表达行为或明确拒绝边界；仍需游戏内/快照验收的项目在下文单独列出，拒绝边界不宣称与源项目完整等价。
- **6 组需要游戏内验证的功能**：资源/配方与视觉、五种操作矩阵、GT/AE 状态、智能复制、量子上行链路、存档重载。
- **3 项本轮已固定行为边界**：A 确认后 B/C/粘贴点在客户端跟随准星实时预览；AE Interface-P2P/外部 Hub 选项在目标 API 不可表达时服务端拒绝；schema 11/有符号 Mark 边界由自动化测试固定。

“未验证”不等于“缺失”；“有意拒绝”也不等于“源项目等价”。两者在下文分别列出。

### 1.2 实现进度

勾选表示目标代码已完成对应行为改造，并通过至少一次编译/定向测试；“待游戏内验收”表示仍需在干净世界记录实际行为。

- [x] D01 范围/批处理：完整计划保留，批次按当前玩家范围筛选；待游戏内验证玩家移动后继续执行。
- [x] D02 AE 工具网络绑定与权限模型：接入 AE2 无线接入点链接槽，按绑定接入点解析网络并每次传输重检供电、范围和玩家动作源；待游戏内验证安全终端链接。
- [x] D03 物品/流体统一资源事务：已实现统一两阶段物品/流体预留、提交、补偿，以及 Forge/AE2/量子上行链路流体源；待游戏内验证实际流体容器与网络权限。
- [x] D04 详细预览提示、错误/警告状态和配置项：提示上限、覆盖地形、范围越界着色、无效计划临时错误框和过期时间已接入；待游戏内确认视觉效果。
- [x] D05 合并声音反馈：服务端每个已提交批次按操作类型在中心位置播放一次合并声音；待游戏内验证音效资源与音量。
- [x] D06 Mark、有符号阵列、Hub 和智能替换菜单：Mark/有符号阵列与 GT CRIB -> Proxy 已完成；目标 AE2 Supergiant 不提供带样板库存的 Interface-P2P 或源项目 Wireless Hub，启用对应选项时服务端明确拒绝，不再静默复制错误状态。
- [x] D07 Ctrl/滚轮输入状态与源配置一致：选择清理遵循配置，鼠标中键按下/释放边沿已消费，避免幽灵重复取样；待游戏内验证不同 GUI/双持组合。
- [x] D08 液体射线拾取：液体值已进入 `BlockSpec`，几何/交换通过 Forge `IFluidBlock` 放置并走流体资源事务；待游戏内确认不同流体和回滚。
- [x] D09 GregTech 状态捕获/恢复扩展：目标 API 可安全表达的机器/管线状态已捕获恢复；库存、流体和额外掉落仍拒绝，覆板与标准/自定义幽灵电路通过 GT 公开 API 恢复。
- [x] D10 AE2 状态捕获/恢复扩展：CableBus、Facade、Pattern Provider 已使用显式捕获结构；其他 AE 状态、Interface-P2P 和库存型掉落显式拒绝。
- [x] D11 移动模式的范围内渐进执行与更多适配器支持：按批次使用原子事务；普通 TileEntity、未支持 GT/AE 状态和重叠目标拒绝，不保留裸 NBT 兼容路径。
- [x] D12 Matter Manipulator 专用全局配置：配置文件加载、预览生命周期/深度、选择清理、MK3 批速和 ME 输出目标过滤已接入；待游戏内验证配置热启动行为。
- [x] D13 源兼容的稳定加权随机序列：Geometry/Exchange 改用源 XSTR 算法的 target-native 实现；待跨实现快照验证。
- [x] D14 持久化策略和迁移/发布边界：使用 schema 11 NBT；未知/缺失 schema 直接丢弃为新状态，不解释 1.7.10 JSON，发布边界已由自动化测试固定。

本轮捕获/恢复适配进度：

- [x] GT 覆板完整拆除产物、标准/自定义幽灵电路和输出总线过滤器进入资源事务。
- [x] GT 工作允许、消音、单方块/缓冲仓 I/O 面、自动输出、输出面输入、同物品插入、总线/双仓自动压缩、流体仓锁定、批处理、输入分离、能量警告、多配方图和长距离端点模式使用公开 API 捕获、变换、恢复并重读校验。
- [x] AE2 CableBus 普通部件的 Memory Card 设置、优先级、升级、物品/流体配置槽，以及 Pattern Provider 设置进入显式捕获和资源事务。
- [x] AE2 P2P 输出可保留频率复制，输入可保留角色移动；已调谐输入复制明确拒绝，避免产生第二个输入端。
- [ ] AE2 可安全重建的单方块机器使用同等捕获/恢复合同。

## 2. 核心差异清单

| 编号 | 领域 | 状态 | 源项目行为 | ApplyGray 行为 | 影响 |
| --- | --- | --- | --- | --- | --- |
| D01 | 范围与批处理 | **已实现，待验证** | 选区整体保留，`MMRenderer`/建造队列只过滤当前玩家范围内的待处理方块；玩家移动后可继续执行剩余范围。 | 建造服务不再在创建计划时整体拒绝；`MatterManipulatorBuildManager` 保留完整计划，用 `BitSet` 记录已完成操作，每批只准备当前范围内的未完成操作。移动保持原子事务，只有源/目标全部进入范围才提交。 | 远距离几何/复制/交换/线缆现在可以边走边完成；移动仍需游戏内确认原子等待语义。 |
| D02 | AE 网络绑定 | **已实现，待验证** | `MMState.encKey` 绑定安全终端或无线接入点；工具拥有独立网络连接状态，并检查网络供电、无线范围和 `EXTRACT/INJECT` 权限。 | `Ae2ManipulatorLinkHandler` 注册目标 AE2 `GridLinkables`；工具 NBT 保存绑定接入点位置，`Ae2WirelessMaterialSource` 每次传输解析该接入点并通过 `IActionSource.ofPlayer` 走 AE 安全检查。旧的“扫描背包中任意无线终端”路径已移除。 | 工具现在具有独立、可持久化的 AE 网络绑定；仍需游戏内确认链接槽、失联和权限拒绝。 |
| D03 | 流体资源 | **已实现，待验证** | `MMInventory`、流体消费者和上行链路支持从机器/世界取出 `FluidStack`、注入玩家容器以及 AE 流体转移。 | `FluidRequirement` 与物品需求共同进入 `ResourceTransaction`/`OutputTransaction` 的两阶段事务；Forge `IFluidHandler`、绑定 AE2 网络和量子上行链路均实现流体提取/注入。现有不安全 TileEntity 适配器仍会拒绝无法安全捕获的非空流体状态。 | 通用流体资源边界已接通；需游戏内确认容器、AE 权限、Plasma 消耗和回滚。 |
| D04 | 预览提示 | **已实现，待验证** | 源 `MMRenderer` 使用 `RenderHints`/StructureLib，支持最多约 1,000,000 个方块提示、按实际可建造范围过滤、详细方块渲染、阻挡/错误/警告状态、过期时间和“始终覆盖地形”。 | `MatterManipulatorPreviewRenderer` 使用配置化详细上限，按工具实际范围将越界提示着红色，地形覆盖由 `hintsOnTop` 控制；无效计划以独立临时红色提示框显示，并按 `statusExpirationSeconds` 到期隐藏。 | 预览信息量、范围可见性和错误生命周期已恢复；结构级方块材质细节仍是目标渲染器的明确简化。 |
| D05 | 声音反馈 | **已实现，待验证** | 源 `AbstractBuildable` 按世界和声音资源合并事件，在建造/移除/移动完成时以中心位置播放，避免逐方块播放。 | `MatterManipulatorBuildManager` 每个成功批次只在批次中心播放一次声音，并按复制/建造/交换/移动选择不同声音；失败批次不播放逐方块音。 | 已恢复成功操作的合并听觉反馈；需游戏内确认资源和音量。 |
| D06 | 复制高级选项 | **已实现目标边界** | 复制菜单包含 `Edit Stack -> Mark`，按准星计算相对阵列跨度（可为负）；无线链接 Hub、`replaceCribsWithProxies`、`replaceInterfacesWithP2P`；规划全部/缺失材料、自动/手动规划、取消与清理手动规划。 | Mark 已贯通菜单、客户端实时预览、服务端状态和 `CopyPlanner` 的有符号跨度；GT `MetaTileEntityMEPatternProvider` 在开启选项时以显式主机坐标落地 `MetaTileEntityMEPatternProviderProxy`，并由事务预留 Proxy 材料；目标 AE2 不具备源项目 Interface-P2P/Hub 能力，服务端在计划/捕获边界明确拒绝。 | 反向/相对阵列与 GT CRIB 替换可执行；AE Interface-P2P/Hub 保留为目标 API 能力缺口，不会静默产生不等价结果。 |
| D07 | 输入与按键 | **已实现，待验证** | 源配置控制 Ctrl+C/X、Ctrl+Z 是否清理坐标、变换和阵列；有鼠标滚轮修复以清除幽灵按键；默认只检查主手。 | `beginCopy`/`beginMove`/模式切换现在遵循选择清理配置；中键同时消费按下与释放边沿并抑制重复事件；目标仍保留副手支持这一额外行为。 | 状态残留和幽灵中键事件已修复；需游戏内确认按键组合和副手差异。 |
| D08 | 中键拾取 | **已实现，待验证** | 源射线拾取可命中液体，并按当前动作处理。 | `PickManipulatorBlockMessage` 使用 `stopOnLiquid=true`；`BlockSpec.fromState` 保存 `FluidStack`，`FluidBuildingAdapter` 以 `IFluidBlock.place` 放置并通过 `FluidRequirement` 进入事务。 | 液体已能进入配置、预留和放置流程；需游戏内确认不同流体和失败回滚。 |
| D09 | GregTech 状态 | **已实现目标边界** | 源 GT 分析器/移动器覆盖正面、输出方向、颜色、自动输出、批处理、模式系统；hatch/bus 排序、堆叠限制、锁定、幽灵电路；Covers、红石输出、`IDataCopyable` 数据；多方块旋转与 TecTech 参数。 | `GregTechBuildingAdapter` 捕获每面覆板的物品、定义和配置 NBT，并通过 `CoverDefinition` 重建；通过 `IGhostSlotConfigurable`/`GhostCircuitItemStackHandler` 捕获标准及自定义幽灵槽。非空真实物品/流体、额外掉落或无法由公开 API 重建的状态仍拒绝。 | 覆板和幽灵电路已进入复制、移动、旋转和资源事务；更宽的 GT 工作模式及任意 `IDataCopyable` 数据仍是明确边界。 |
| D10 | Applied Energistics 2 状态 | **已实现目标边界** | 源 AE 分析器覆盖单方块机器颜色/方向/配置/升级/存储元件/样板，以及部件名称、词典过滤器、P2P 字段、优先级和可调谐 P2P。 | `Ae2BuildingAdapter` 以 CableBus、侧面部件、Facade 和 Pattern Provider 样板为显式支持集；其他 AE 单方块机器、存储元件和未显式支持配置拒绝，不回退到 TileEntity NBT。 | CableBus/Pattern Provider 有目标事务语义；完整 AE 状态复制仍是明确非兼容边界。 |
| D11 | 移动语义 | **已实现目标边界** | 源移动依赖完整 `BlockMover`、GT/AE/无线方块移动器，可处理更多 TileEntity 和机器状态。 | 目标移动使用 `CopyPlanner` + 目标适配器 + 原子 `BuildTransaction`；普通 Vanilla TileEntity、未支持 GT/AE 状态和重叠目标拒绝。 | 安全性更强、支持面更窄；行为差异已固定为目标合同。 |
| D12 | 全局配置 | **已实现，待验证** | `GlobalMMConfig` 提供自动清理粘贴区、清坐标时是否重置变换、最大提示数、状态提示持续时间、提示覆盖地形、MK3 批速、ME 输出清空和调试开关。 | `MatterManipulatorConfig` 在预初始化加载全部配置；选择清理、提示上限/生命周期/深度、MK3 批速和 ME 输出目标过滤均已消费。 | 管理员和玩家可调整源项目的主要 Matter Manipulator 行为参数；调试开关仍只作为低频诊断预留。 |
| D13 | 加权随机 | **已实现，待快照验证** | 源使用 StructureLib `XSTR`，并基于源配置哈希生成稳定候选序列。 | `SourceCompatibleRandom` 采用源 XSTR 的 xorshift 与 `nextInt(bound)` 语义，Geometry/Exchange 继续使用固定配置哈希种子。 | 同一权重表的序列算法已对齐；仍需跨实现计划快照确认种子哈希输入完全一致。 |
| D14 | 状态持久化 | **已实现目标边界** | 源用 Gson JSON，保存 `jv`/`dv` 并执行 JSON 版本迁移。 | 目标使用独立 NBT schema（当前 `SCHEMA_VERSION = 11`），未知/缺失 schema 直接创建新状态，不解释 1.7.10 JSON；往返和丢弃行为有自动化测试。 | 旧工具 NBT 不迁移；这是当前“清理旧接口”的明确发布边界。 |

### 2.1 证据索引

下表给出每项结论的最小源码证据。行号以审计快照为准，后续改动后应重新定位。

| 编号 | 源项目证据 | ApplyGray 证据 |
| --- | --- | --- |
| D01 | `MatterManipulator/src/main/java/com/recursive_pineapple/matter_manipulator/common/items/manipulator/ItemMatterManipulator.java:1106`，只移除超出玩家范围的待处理块。 | `ApplyGray/src/main/java/applygray/mattermanipulator/server/MatterManipulatorBuildManager.java` 的 `BitSet completed`、`geometryBatch/copyBatch/exchangeBatch` 和 `inRange`；各服务创建计划时不再整体范围拒绝。 |
| D02 | `MatterManipulator/.../MMState.java:104,207-273` 的 `encKey`、`connectToMESystem`、网络供电/权限/无线距离检查。 | `ApplyGray/.../integration/ae2/Ae2ManipulatorLinkHandler.java`、`.../Ae2WirelessMaterialSource.java` 和 `ManipulatorState.java` 的 `Ae2Network` 位置字段。 |
| D03 | `MatterManipulator/.../common/building/MMInventory.java`、`IItemConsumer`、`MMItemConsumer.java`，以及 `BlockAnalyzer` 的流体/库存分析。 | `ApplyGray/.../inventory/FluidRequirement.java`、`FluidMaterialSource.java`、`ResourceTransaction.java`/`OutputTransaction.java`；AE 与 Uplink 源均提供 `FluidStack` 提取/注入。 |
| D04 | `MatterManipulator/.../common/items/manipulator/MMRenderer.java:513-604` 使用 `RenderHints`、错误/警告提示和可配置上限。 | `ApplyGray/.../client/mattermanipulator/MatterManipulatorPreviewRenderer.java` 使用配置化提示上限、覆盖地形深度状态、实际范围越界着色和无效计划红色范围提示；详细生命周期仍待补齐。 |
| D05 | `MatterManipulator/.../common/building/AbstractBuildable.java:401-432` 合并并播放声音。 | `MatterManipulatorBuildManager.playBatchSound` 每个成功批次按操作类型播放一次中心声音；失败批次不播放逐方块音。 |
| D06 | `MatterManipulator/.../MMConfig.java:47,55,60` 和 `ItemMatterManipulator.java:998-1013,1384-1512` 的 `arraySpan`、Mark、智能替换和规划菜单。 | `CopyArraySpan.java`、`ManipulatorPendingAction.MARK_ARRAY`、`ItemMatterManipulator` Mark 分支、`CopyPlanner` 有符号跨度；`GregTechBuildingAdapter` 已按开关将 CRIB 捕获为带主机坐标的 Proxy；目标 AE2 的 Interface/P2P 与 Hub 选项在计划/捕获边界明确拒绝。 |
| D07 | `MatterManipulator/.../ItemMatterManipulator.java:2218-2250` 的 Ctrl/Shift/Ctrl 步进与清理逻辑；输入处理默认主手。 | `ApplyGray/.../client/mattermanipulator/MatterManipulatorClientInput.java` 与 `MatterManipulatorTransformScreen.java:318-387` 固定重置、`1..64` 重复次数并支持副手。 |
| D08 | 源 `ItemMatterManipulator` 的射线拾取路径允许液体命中。 | `PickManipulatorBlockMessage` 使用 `rayTraceBlocks(..., true, ...)`；`BlockSpec`/`FluidBuildingAdapter` 分别负责流体值持久化和 Forge 流体放置。 |
| D09 | `MatterManipulator/.../common/building/GTAnalysisResult.java`、`BlockAnalyzer.java` 和 `movers/GTBlockMover.java` 的 GT 属性分析/恢复。 | `ApplyGray/.../integration/gregtech/GregTechBuildingAdapter.java` 对覆板/幽灵电路做显式捕获恢复；对非空库存、流体、额外掉落及未知状态抛出 `BuildingException`。 |
| D10 | `MatterManipulator/.../common/building/AEAnalysisResult.java`、`AEPartData.java` 和 `movers/WirelessBlockMover.java` 的 AE 状态捕获。 | `ApplyGray/.../integration/ae2/Ae2BuildingAdapter.java:178-258,306-410` 仅显式处理 CableBus/Facade/Pattern Provider。 |
| D11 | `MatterManipulator/.../common/building/movers/BlockMovers.java`、`GTBlockMover.java`、`WirelessBlockMover.java`。 | `ApplyGray/.../building/MoveBuildService.java:36-62,86-110` 使用目标适配器和原子事务，重叠/未知状态拒绝。 |
| D12 | `MatterManipulator/src/main/java/com/recursive_pineapple/matter_manipulator/GlobalMMConfig.java:8-66`。 | `MatterManipulatorConfig` 已加载 `maxHints`、覆盖地形、选择清理、MK3 批速、ME 输出清空和调试开关；提示生命周期与部分服务端消费策略仍待接入。 |
| D13 | `MatterManipulator/.../common/utils/MMUtils.java:427-448` 和 `MMState.java:385-415` 使用 `XSTR`/配置哈希稳定序列。 | `ApplyGray/.../util/SourceCompatibleRandom.java` 复制 XSTR xorshift/取模语义，由 Geometry/Exchange 使用。 |
| D14 | `MatterManipulator/.../MMState.java:104-199` 的 Gson `jv/dv` 与 `migrateJson`。 | `ApplyGray/.../state/ManipulatorState.java:21,247-314` 只接受 `SCHEMA_VERSION = 11`，未知 schema 返回新状态。 |

## 3. 已对齐但曾经遗漏的行为

### 3.1 A/B/C 实时跟随预览

本轮已修复目标预览的关键语义：确认 A 后，B、C（粘贴点）以及移动模式所需的下一点由客户端准星实时投影，直到下一次点击确认；投影通过客户端状态副本完成，不写回服务端权威 NBT。实现见 [MatterManipulatorPreviewRenderer.java](../src/main/java/applygray/client/mattermanipulator/MatterManipulatorPreviewRenderer.java:126)。

这与源 `MMRenderer` 的“选择点跟随玩家准星、点击后固化”语义一致。它只解决预览跟随，不改变 D01 的服务端范围拒绝，也不补齐 D04 的详细提示系统。

### 3.2 当前已完成的结构性移植

以下不是遗漏，但后续审计不应重复列为缺失：

- 四级工具、组件/下行链路/空白升级和四种升级的注册入口、模型、双语名称已存在；资源和 JEI/创造栏仍需游戏内逐项确认。
- 几何、交换、线缆、复制、移动五类服务均由服务端计划和事务驱动，材料/EU/产物具有回滚路径。
- 二维旋转/镜像兼容接口已移除，统一使用三维正交 `ManipulatorTransform`。
- 中键、Ctrl+X/C/V/Z、空气右键配置和潜行蓄力执行入口已接通，旧 `M`/`B` 入口未保留。
- AE Smart Copy 的源链接接口与量子上行链路的目标原生接口已接通，但见第 5 节的运行时验证缺口。

## 4. 目标设计中的明确拒绝边界

这些项不是“忘记实现”，而是为了避免把任意 TileEntity NBT 当作可移植状态：

1. `VanillaBuildingAdapter` 拒绝带有不安全 TileEntity 状态的普通方块，不做裸 NBT 克隆。
2. `GregTechBuildingAdapter` 对非空真实库存/流体、额外掉落和未知工作模式拒绝；覆板与幽灵电路仅通过公开 GT 定义/配置 API 捕获恢复，无法解析的状态仍拒绝。
3. `Ae2BuildingAdapter` 对未显式支持的 AE 机器、存储元件、部件配置拒绝；Pattern Provider 槽位变化会失败并回滚。
4. `MoveBuildService` 拒绝重叠源/目标，使用原子事务保证失败时不留下半个移动结果。
5. `ManipulatorState.readFromNbt` 对缺少 schema 或未知 schema 重置，不保留旧 1.7.10 兼容读取器。

这些边界应保留；若要扩大支持面，应增加目标 API 的显式捕获/恢复结构和测试，而不是恢复旧接口。

## 5. 仅代码存在、尚未完成游戏内验证的项目

### V01 物品、模型、配方和 JEI

静态代码和资源显示 4 把工具、20 个分级核心/框架/镜组、下行链路、空白升级和 4 个升级均有注册与语言条目；仍未在干净客户端逐项确认创造栏、JEI、动画贴图、模型和生存合成链。需要检查：

- MK0 -> MK I -> MK II -> MK III 升阶时 NBT、电量和升级是否保留。
- 重复升级、等级不允许升级和配方冲突是否在启动期明确报错。
- remap jar 是否包含每个模型、PNG 和 `.mcmeta`。

### V02 五种模式的实际世界操作

自动化已覆盖计划和事务的部分边界，但尚未在干净世界完成：

- 四种几何形状、角/边/面/体加权材料、空材料槽和重复材料。
- 线缆模式的 AE CableBus 与 GT 管线/线缆放置。
- 交换白名单、替换材料、资源不足和保护方块。
- 复制的四种水平旋转、三轴镜像、三轴重复。
- 移动的普通方块、GT 空机器/管线、AE CableBus/Pattern Provider，以及失败回滚。

### V03 GT/AE 状态矩阵

目标拒绝边界已由适配器代码实现，但需要在游戏内确认拒绝不会丢物品、不会遗留半个 CableBus、不会破坏连接：

- GT 空单方块的正面、染色、自定义名称。
- GT 空管线各面连接、阻断和框架材料。
- AE CableBus 中心线缆、六侧部件、Facade。
- Pattern Provider 样板/升级真实消耗、目标槽位变化回滚。
- Smart Copy 源链接的发布、推送和目标独立性。

### V04 智能复制生命周期

仍未验证源区块卸载、源更新、客户端/服务端重载、失联和恢复。特别要确认链接只借用源样板，不复制源升级和库存；失联时建造必须拒绝而不是静默使用旧缓存。

### V05 量子上行链路

目标已有结构、地址、AE 材料接口、Plasma 消耗、Power P2P 和合成请求接口，但以下仍需日志与游戏内检查：

- 结构方向、控制器/连接器数量和形成/失效状态。
- 跨维度材料提取/退还、Plasma 不足和 AE 事务回滚。
- 合成请求的排队、取消、完成、服务器重启后的恢复。
- 多玩家共用同一上行链路、地址冲突重生成和区块卸载/加载。

### V06 存档和旧世界干扰

目标 schema 重载需要用干净世界验证。已知最新测试世界包含一个既有 GregTech ME Pattern Provider 存档恢复越界异常；它不属于物质操纵器注册，但会干扰旧世界的重载验收。最终矩阵应使用新建世界，并单独记录该外部异常。

## 6. 交互层的细项差异

### 6.1 交换模式取样时机

源菜单提供“一次性待处理右键取样”动作，进入世界后按该动作完成白名单/替换目标采样。目标菜单选择白名单覆盖/追加或替换目标后，回到世界主要依靠中键取样；源动作状态没有完整一一对应。这是 D07 之外的独立交互差距。

### 6.2 阵列 Mark 的语义不能只补按钮

源 `ItemMatterManipulator.onMarkArray` 根据准星和选区跨度写入可为负的 `Vector3i arraySpan`，负值表示反向阵列。目标 `ManipulatorState` 只持久化三个 `1..64` 的正重复次数。不能只在 GUI 添加 Mark 文案；必须先统一状态、`CopyPlanner`、预览和服务端目标坐标的有符号跨度模型。

### 6.3 主手/副手

目标网络包携带 `EnumHand` 并支持副手。源默认只处理主手。副手支持是目标新增行为，应在控制设置、双持和重复触发测试中明确记录，而不是当作源移植完成项。

## 7. 资源、掉落和事务差异

源 `MMInventory` 可以在玩家背包、AE 网络、上行链路和机器内部库存之间选择物品/流体消费者，并在建造过程中处理取出、退还和掉落。目标的 `ResourceTransaction`、`MaterialSource` 和 `OutputTransaction` 已建立物品级事务，但以下边界仍不同或未覆盖：

- 目标 `BlockSpec` 是主要资源键，无法表达源项目的通用 `FluidStack` 消费者。
- 目标普通/GT/AE 适配器拒绝无法安全回收的库存型掉落；源项目对更多容器和机器有回收策略。
- 目标批处理在计划创建时固定材料选择，批次失败会回滚；源项目部分分析/资源消费是按待处理队列推进，需用日志确认玩家移动、区块卸载和资源变化时的差异。
- 目标 MK3 批速固定为 `ManipulatorTier.MK3.blocksPerBatch() == 256`，源由 `GlobalMMConfig.BuildingConfig.mk3BlocksPerPlace` 配置。

## 8. 算法与一致性风险

### 8.1 随机序列

目标 `GeometryPlanBinder` 和 `ExchangeBuildService` 使用 Java `Random`，即使随机种子固定，也不等同于源 `XSTR`。应增加跨实现计划快照测试：同一选区、权重表、配置哈希和操作顺序，比较角色分类、材料序列和总资源需求；若不要求字节级一致，也应在规格中明确“只保证权重分布，不保证排列”。

### 8.2 计划固定与世界变化

目标建造服务先创建不可变计划，再按批次执行；源渲染/队列会按当前世界和玩家范围过滤待处理块。区块在两批之间变化、目标被其他玩家修改、玩家离开范围时，目标和源可能出现不同的跳过/拒绝顺序。该差异需要低频的开始、首次失败、取消和结束日志来确认，不应添加逐方块或逐 tick 日志。

## 9. 优先级修复清单

### P0：必须先决定是否要“完全等价”

1. 决定 D01 是否要改为“保留计划、按批次过滤当前范围”，或把整体范围拒绝写入正式规格。两者会改变服务端队列模型。
2. 决定 D02 是否保留目标“玩家无线终端即材料源”的架构；若要等价，需引入工具 `encKey`、绑定动作、连接状态和权限检查，而不是兼容两套入口。
3. 决定 D03 是否要支持通用流体资源；若支持，应扩展 `MaterialSource`/`ResourceRequirement` 为物品与流体的值对象，并为 GT/AE/上行链路分别实现事务。

### P1：玩家可见的能力缺口

1. 补齐 D04 预览提示状态、上限、范围过滤和覆盖地形配置。
2. 补齐 D05 合并声音事件。
3. D06 的 Mark/有符号阵列和 GT CRIB -> Proxy 已完成；目标 AE2 Interface -> P2P 与 Hub 链接无源等价 API，已固定为显式拒绝边界。
4. D07 已完成；D08 液体值对象和 Forge 放置事务已接入，保留游戏内流体回滚验收。

### P2：适配器支持面

1. 以显式 POD/捕获结构扩大 GT 状态，而不是复制任意 NBT。
2. 按优先级扩大 AE CableBus、Pattern Provider 之外的机器/部件配置，并为每项增加资源计费和回滚测试。
3. 对 D11 的移动支持分别建立普通方块、GT、AE 的能力矩阵，保持重叠拒绝和原子事务。

### P3：验证与发布门槛

1. 在干净世界完成 V01-V06，保存日志和截图证据。
2. 增加随机计划快照测试、跨批次玩家移动测试和 schema 重载测试。
3. 发布文档中明确 D09-D11、D14 是有意非兼容边界；不要恢复旧接口或旧 JSON 兼容层。

## 10. 本轮验证记录

- 已完成 `./gradlew compileJava --no-daemon --console=plain`（包含 GT CRIB Proxy 和 D08 流体值对象/放置事务修改）。
- 已完成 `./gradlew test --no-daemon --console=plain`（D06/D08 修改后）。
- 已完成 A 确认后 B/C/粘贴点实时跟随准星的客户端预览代码修复；预览使用客户端副本，不污染服务端状态。
- 本轮 Java 修改后重新执行编译、定向 Matter Manipulator 边界测试和 `git diff --check`；D06 的 AE Interface-P2P/外部 Hub 选项现在在服务端显式拒绝。

截至本文档生成时，不能把物质操纵器标记为“完整移植”。准确结论是：核心服务端架构和基础五模式已接通，存在 14 项已确认的源行为差异/缺失，另有 6 组必须通过干净世界游戏内验收的验证缺口。
