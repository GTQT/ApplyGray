# ECO 实现路线图

> 分支：`feature_eco`
> 目标版本：Minecraft 1.12.2 / GTQT GregTech / Applied Energistics 2 Supergiant
> 参考仓库：GregTech（`E:\模组开发\GTQT\GregTech`）、AE2 Supergiant（`E:\模组开发\资料库\Applied-Energistics-2-Supergiant`）、PrismPlan（GPL-3.0，仅参考）、NeoECOAEExtension（GPL-3.0，协议允许时移植）
> 状态：阶段 0–4 完成；GT 规范重构完成，编译/测试/服务端冒烟通过；客户端交互仍需实机点验

## 1. 目标

把 ECO 补齐为四个独立的多方块组分：

| 组分 | 职责 | 对应 AE2 概念 |
|---|---|---|
| 多方块存储器 | 元件驱动、ME 存储挂载、优先级与统计 | ME Drive / Storage |
| 多方块 CPU | 合成任务执行、共享内存、线程与并行 | Crafting CPU |
| 多方块分子装配室 | 样板执行、批次合成、产物回网 | Molecular Assembler |
| AE 多方块电池 | 网络能量存储与缓冲 | Energy Cell |

硬性要求：

1. 四个多方块全部基于 GT 多方块架构：`MultiblockWithDisplayBase`、GT 声明式结构引擎（`DeclarativePatternBuilder` / `StructureDefinition`）、GT 结构通道。
2. 特殊端口全部通过 GT 仓室注册：`IMultiblockAbilityPart` + `MultiblockAbility`，控制器与仓室统一走 `MetaTileEntities.registerMetaTileEntity`。
3. 右键 UI 自实现，覆盖 GT 默认多方块面板；每个组分有自己的页面与状态展示。
4. 协议允许时，结构模型、材质、UI 设计可移植 NeoECOAEExtension；移植必须在源码与 README 中保留来源声明，并按 GPL-3.0 分发。

## 2. 现状盘点

### 2.1 已实现

| 模块 | 现状 | 代码位置 |
|---|---|---|
| 控制器基类 | GT `MultiblockWithDisplayBase` + 多 piece 可重复结构 + 结构通道 + 统一持久化/暂停/UI 入口 | [`MetaTileEntityEcoController`](../src/main/java/applygray/common/metatileentities/multi/electric/eco/MetaTileEntityEcoController.java) |
| 四个控制器 | `eco.computer` / `eco.storage` / `eco.molecular_assembler` / `eco.battery`，一个机器一个类 | [`MetaTileEntityEcoComputer`](../src/main/java/applygray/common/metatileentities/multi/electric/eco/MetaTileEntityEcoComputer.java)、[`MetaTileEntityEcoStorage`](../src/main/java/applygray/common/metatileentities/multi/electric/eco/MetaTileEntityEcoStorage.java)、[`MetaTileEntityEcoMolecularAssembler`](../src/main/java/applygray/common/metatileentities/multi/electric/eco/MetaTileEntityEcoMolecularAssembler.java)、[`MetaTileEntityEcoEnergyStorage`](../src/main/java/applygray/common/metatileentities/multi/electric/eco/MetaTileEntityEcoEnergyStorage.java) |
| 仓室 | 抽象基类 + 8 个 role 类，统一走 GT `IMultiblockAbilityPart` + `MultiblockAbility` | [`MetaTileEntityEcoPart`](../src/main/java/applygray/common/metatileentities/multi/multiblockpart/eco/MetaTileEntityEcoPart.java)、[`EcoAbilities`](../src/main/java/applygray/common/metatileentities/multi/multiblockpart/eco/EcoAbilities.java) |
| ME 接口 | 每个控制器一个能力键，按类型只暴露 CPU/存储/样板/能量服务 | [`MetaTileEntityEcoNetworkPart`](../src/main/java/applygray/common/metatileentities/multi/multiblockpart/eco/MetaTileEntityEcoNetworkPart.java) |
| CPU | `CraftingCPUCluster` 子类 + 共享内存池 + 线程/超线程/并行核 + 任务预留/取消 + NBT | [`EcoCpu`](../src/main/java/applygray/common/metatileentities/multi/electric/eco/logic/EcoCpu.java)、[`EcoMemoryPool`](../src/main/java/applygray/common/metatileentities/multi/electric/eco/logic/EcoMemoryPool.java) |
| 存储 | 驱动仓插 ECO 元件，AE2 原生 `IStorageProvider` 挂载、待机耗电、元件热插拔 | [`MetaTileEntityEcoDrivePart`](../src/main/java/applygray/common/metatileentities/multi/multiblockpart/eco/MetaTileEntityEcoDrivePart.java) |
| 分子装配 | 3 宽 × 3 高 × 2 深（NeoECO crafting system 布局）+ 1–16 段；样板总线（63 槽）+ Worker 批次执行 + AE 扣电 + 产物暂存 + 失败回滚 | [`EcoStructures`](../src/main/java/applygray/common/metatileentities/multi/electric/eco/EcoStructures.java)、[`EcoAssemblerLane`](../src/main/java/applygray/common/metatileentities/multi/electric/eco/logic/EcoAssemblerLane.java) |
| UI | AE2 `AEBaseContainer` + 全自绘 `EcoGui`（总览/内容/硬件三页签、搜索、翻页、取消任务、暂停） | [`EcoGui`](../src/main/java/applygray/client/gui/EcoGui.java)、[`EcoContainer`](../src/main/java/applygray/common/metatileentities/multi/electric/eco/gui/EcoContainer.java) |
| 物品 | 5 级内存芯片 + 5 级物品元件 + 5 级流体元件 | [`ApplyGrayMetaItems`](../src/main/java/applygray/common/items/ApplyGrayMetaItems.java)、[`common/items/eco`](../src/main/java/applygray/common/items/eco) |
| 注册 | GT 风格：控制器/接口/仓室分别由 registration 类注册，字段集中在 `ApplyGrayMetaTileEntities` | [`EcoMultiblockRegistration`](../src/main/java/applygray/common/metatileentities/registration/EcoMultiblockRegistration.java)、[`EcoMultiblockPartRegistration`](../src/main/java/applygray/common/metatileentities/registration/EcoMultiblockPartRegistration.java) |
| 语言 | `en_us.lang` / `zh_cn.lang` 各 507 条总 key，`LocalizationResourceTest` 强制一致 | `src/main/resources/assets/applygray/lang/` |
| 数值 | 5 级硬件：内存、存储、加速器、线程、能量 | [`EcoTiers`](../src/main/java/applygray/common/metatileentities/multi/electric/eco/EcoTiers.java) |

### 2.2 缺口（阶段 0 基线；修复状态见 4.1）

| # | 缺口 | 证据 / 影响 |
|---|---|---|
| G1 | 没有第 4 个组分：AE 多方块电池 | 基线只有 `ENERGY` 仓，且被放在存储货架结构里 |
| G2 | ECO 没有任何配方 | [`ApplyGrayRecipes`](../src/main/java/applygray/common/ApplyGrayRecipes.java) 基线无 ECO 条目，机器/仓室/元件/内存只能创造模式获取 |
| G3 | MTE ID 由 enum ordinal 推导 | 已修复：改为显式 ID 注册，见 3.3 |
| G4 | 没有专属机壳/材质 | 四个结构复用 GT 电脑机壳 + 不锈钢框架 + 聚变玻璃；物品 model 引用 `ae2:` 贴图占位 |
| G5 | UI 只有一套通用仪表盘 | 电池没有能量页；能量仓、驱动仓、Worker 队列都没有专属界面 |
| G6 | 能量仓归属未拆分 | 电池独立后，存储货架的 `ENERGY` 位、ME 接口的 `IAEPowerStorage` 服务都要迁移 |
| G7 | 收尾项缺失 | 无 TOP、无 JEI 定制信息、无指南/成就、ECO 逻辑无单元测试 |

### 2.3 代码结构（按 GT 规范重构）

```
common/
  items/eco/
    EcoCellItem.java                 # GT MetaItem：AE2 存储元件
    EcoMemoryItem.java               # GT MetaItem：计算内存
  metatileentities/
    registration/
      EcoMultiblockRegistration.java     # 4 个控制器
      EcoMultiblockPartRegistration.java # 4 个 ME 接口 + 8 类仓室
    multi/
      electric/eco/
        EcoControllerType.java       # 组分键（同时用于 ME 接口能力）
        EcoStructures.java           # 四个 StructureDefinition
        EcoLayouts.java              # 结构行数据
        EcoTiers.java / EcoConfig.java
        EcoRecipes.java
        MetaTileEntityEcoController.java   # 抽象基类
        MetaTileEntityEcoComputer.java
        MetaTileEntityEcoStorage.java
        MetaTileEntityEcoMolecularAssembler.java
        MetaTileEntityEcoEnergyStorage.java
        logic/
          EcoCpu.java / EcoMemoryPool.java / EcoAssemblerLane.java
        gui/
          EcoContainer.java / EcoGuiHandler.java
      multiblockpart/eco/
        EcoPartRole.java / EcoAbilities.java
        MetaTileEntityEcoPart.java          # 抽象仓室
        MetaTileEntityEcoMemoryPart.java
        MetaTileEntityEcoThreadPart.java
        MetaTileEntityEcoHyperThreadPart.java
        MetaTileEntityEcoParallelPart.java
        MetaTileEntityEcoDrivePart.java
        MetaTileEntityEcoEnergyPart.java
        MetaTileEntityEcoWorkerPart.java
        MetaTileEntityEcoPatternBusPart.java
        MetaTileEntityEcoNetworkPart.java   # ME 接口
```

注册规则：

- 控制器字段放在 `ApplyGrayMetaTileEntities`（ApplyGray 的 `MetaTileEntities`），由 `EcoMultiblockRegistration` 赋值。
- 仓室用数组字段（`ECO_MEMORY[5]` 等）承载 tier 变体，由 `EcoMultiblockPartRegistration` 按显式基址 + tier 注册。
- `ApplyGrayMetaTileEntities.init()` 依次调用 `EcoMultiblockRegistration.init()` 与 `EcoMultiblockPartRegistration.init()`。
- 命名：控制器 `MetaTileEntityEco*`，仓室 `MetaTileEntityEco*Part`，逻辑 `Eco*`，注册 `Eco*Registration`。

### 2.4 当前工作区状态

重构已完成：旧 `common/metatileentities/multiblock/eco/*` 已删除，ECO 的控制器、仓室、逻辑、物品、注册分别落在上面的包中；`compileJava` 与服务端冒烟通过。

## 3. 总体设计

### 3.1 四组分职责

- **存储器**：只做 ME 存储挂载；驱动仓插 `EcoCellItem`，端口实现 `IStorageProvider`；不再承担供能。
- **CPU**：只做合成任务；端口实现 `ICraftingCpuProvider`；共享内存由内存芯片仓决定，线程/超线程/并行由专属仓决定。
- **分子装配室**：只做样板执行；端口实现 `ICraftingProvider`；样板总线 + Worker；默认只接受合成样板（`IAssemblerPattern`），与 AE 分子装配室语义一致。
- **电池**：只做能量；端口实现 `IAEPowerStorage`；能量仓只允许出现在电池结构里。

### 3.2 端口能力矩阵

| Kind | 控制器 | ME 接口服务 | 结构允许的仓室 |
|---|---|---|---|
| `COMPUTER` | `EcoController` | `ICraftingCpuProvider` | MEMORY、THREAD、HYPER_THREAD、PARALLEL |
| `STORAGE` | `EcoController` | `IStorageProvider` | DRIVE |
| `ASSEMBLER` | `EcoController` | `ICraftingProvider` | WORKER、PATTERN_BUS |
| `BATTERY` | 新增（建议 `Kind.BATTERY`） | `IAEPowerStorage` | ENERGY |

能力注册仍由 `MetaTileEntities.registerMetaTileEntity` 自动完成（`IMultiblockAbilityPart#getAbilities` → `MultiblockAbility.registerMultiblockAbility`）。

### 3.3 ID 规划（保持追加、不改旧号）

现有 ID 由 `200 + ordinal` / `203 + ordinal` / `210 + role.ordinal()*5 + tier - 1` 推导。改为显式常量：

| 段 | ID | 内容 |
|---|---|---|
| 控制器 | 200 | computer |
| | 201 | storage |
| | 202 | molecular_assembler |
| | 206 | **battery（新增）** |
| ME 接口 | 203 | computer |
| | 204 | storage |
| | 205 | molecular_assembler |
| | 207 | **battery（新增）** |
| 仓室 | 210–214 | memory T1–T5 |
| | 215–219 | thread T1–T5 |
| | 220–224 | hyper_thread T1–T5 |
| | 225–229 | parallel T1–T5 |
| | 230–234 | drive T1–T5 |
| | 235–239 | energy T1–T5 |
| | 240–244 | worker T1–T5 |
| | 245 | pattern_bus T1 |

规则：新内容只追加到所属段末尾；`206/207` 与 `246+` 预留给电池与后续仓室，不再用 ordinal 推导。

### 3.4 结构规划

已实现（`EcoLayouts` + `EcoStructures`）：

- **字符轴统一用 `BACK`**：`char 0` 是控制器所在平面（正面），`char 1` 在控制器背后。  
  用 `FRONT` 会把 2 格厚度伸到控制器前方，导致“必须背对结构才能成型”，而 JEI 默认按正面预览，朝向对不上。
- **JEI 预览正面固定为 `SOUTH`**：`MetaTileEntityEcoController#getPreviewFrontFacing()` 与 CEU 的 DataBank / AssemblyLine / DistillationTower 写法一致；否则预览会按未放置机器的默认朝北渲染，和实际成型方向差 180°。
- 货架类（CPU / 存储 / 电池）：2 宽 × 3 高截面，前固定段（控制器 + ME 接口）+ 1–16 段可重复段 + 后散热段。
- 大分子装配室：**照 NeoECO `createCraftingSystem` 的 3 宽 × 3 高 × 2 深截面**。
  - 固定段沿长度方向三格：一侧全外壳；中格正面是控制器；另一侧背面是 ME 接口。
  - 可重复工坊段接在外壳一侧，每段 1 格厚：正面中间 Worker，背面中间散热口，背面上下 Pattern Bus。
  - **所有 ECO 结构的可重复翼都沿同一 `+aisle` 方向展开（向左）**；装配室之前把可重复 piece 放在控制器前面，导致只有它向右，现已调整 piece 顺序。
  - 行数据：`WORKSHOP_SECTION = {"XB", "WH", "XB"}`、`WORKSHOP_CASING = {"XX","XX","XX"}`、`WORKSHOP_CONTROLLER = {"XX","SX","XX"}`、`WORKSHOP_INTERFACE = {"XX","XN","XX"}`。
  - 方块仍使用 GT 电脑机壳/散热口（D5），不引入 NeoECO 方块材质。

### 3.5 UI 规划

统一入口：`EcoGuiHandler`，右键只打开自绘 UI，不再落到 GT `createUITemplate`。

| 页面 | 内容 |
|---|---|
| 总览 | 在线状态、结构状态、当前任务/进度、暂停/恢复、取消 |
| 内容 | 存储：元件列表与字节/类型；CPU：任务列表；装配：样板列表；电池：能量条与充放速率 |
| 硬件 | 仓室列表、等级、槽位占用、异常仓室 |
| 仓室界面 | 内存/驱动（元件槽）、样板总线（63 槽分页）、Worker（队列/批次）、能量仓（存量） |

若采用 NeoECO 风格（D4），只替换绘制层与贴图，容器/同步协议保持 `AEBaseContainer` + `@GuiSync`。

### 3.6 数据与持久化

- 控制器：`EcoId`、CPU 列表、任务预留、启用状态；拆解/卸载时保留在硬件 NBT。
- 仓室：库存、能量、Worker 队列；驱动拔出元件前必须先 `persist` 并 detach。
- 电池：能量存量、容量、累计充放统计（可选）。
- 迁移：ID 与 NBT key 一旦发布不得改；本次未发布，阶段 0 可一次性定稿。

## 4. 路线图

### 阶段 0 · 基线收敛（0.5–1 天）

任务：

1. 收敛未提交重构，保证 `compileJava` 通过。
2. 跑 `LocalizationResourceTest`（本机 Gradle test 有已知编码问题，必要时手动跑）。
3. 建立本文档 3.3 的显式 ID 表并替换 `EcoHardwareRegistry` 的 ordinal 推导。
4. 记录当前四类结构的成型/拆解基线行为。

产出：可编译的干净分支 + ID 表 + 基线记录。

验收：`compileJava` 通过；ECO 内容 ID 与现网一致（200–205、210–245）；无未提交的中间态文件。

### 阶段 1 · 注册与结构骨架（1–2 天）

任务：

1. 新增电池控制器与 ME 接口（ID 206/207），结构定义、成型/拆解、NBT。
2. 四套结构布局定稿（含 D3 决策）。
3. 端口能力矩阵落地：电池接口只注册 `IAEPowerStorage`；存储/CPU 接口移除供能。
4. （可选）新增 ECO 专属机壳方块 + blockstate/model（D5）。
5. 补 JEI 多方块预览实测与 AE 多方块构建器预置检查。

验收：四台机器可成型/拆解；缺块提示正确；端口可插拔；JEI 能预览；构建器能按结构放置。

### 阶段 2 · 功能实现（3–5 天）

任务：

1. 存储：元件挂载/卸载、优先级、模糊模式、字节与类型统计、元件拔出安全卸载。
2. CPU：共享内存池、线程/超线程/并行、任务预留与回收、取消后输入归还、卸载重载恢复。
3. 分子装配：样板总线编解码、Worker 批次、AE 扣电、产物 escrow、非法样板回滚。
4. 电池：AE 储能服务、容量/待机耗电、充放电、跨等级；按 D2 决定是否 EU↔AE。
5. 统一 `EcoConfig`：批处理、周期、AE/次、评估上限、电池容量与待机耗电。

验收：AE 网络内可提交任务、存取物品、给网络供能；拆机/卸载后数据不丢；非法输入有明确状态。

### 阶段 3 · UI（2–3 天）

任务：

1. 定基座与风格（D4）。
2. 控制器四类页面：总览、内容、硬件、电池能量页。
3. 仓室界面：内存、驱动、样板总线、Worker、能量仓。
4. 富表现：状态灯、进度条、能量条、tooltip、搜索/翻页/取消。
5. 确保右键不再出现 GT 多方块面板。

验收：四类机器 UI 可用；`AEBaseContainer` 同步无错位；UI 只读服务端状态。

### 阶段 4 · 配方与内容（2–3 天）

任务：

1. 机壳 / 控制器 / 仓室 / 内存 / 元件 / 电池的完整配方（D6 电压段）。
2. ECO 元件与 AE2 元件的升级/转换配方。
3. JEI 多方块预览、TOP 信息、双语 lang/tooltip。
4. （可选）指南/成就（D7）。

验收：生存流程可从零造出四台机器；JEI 可查全部配方；双语 key 一致。

### 阶段 5 · 验证与收尾（1–2 天）

任务：

1. 单元测试：内存池预留/回收、批处理边界、NBT round-trip、lang 一致性。
2. 客户端手测清单（见第 6 节）。
3. README / CHANGELOG 更新。

验收：构建通过 + 手测清单全绿。

## 4.1 本次实现记录（2026-10-29）

| 项 | 内容 | 文件 |
|---|---|---|
| ID 表 | 去掉 ordinal 推导，控制器 `200–202/206`、接口 `203–205/207`、仓室 `210–245` 全部写死 | `EcoMultiblockRegistration`、`EcoMultiblockPartRegistration` |
| 电池组分 | 新增 `BATTERY` 类型、`BATTERY_SECTION`（每段 5 个蓄能单元位）、电池 ME 接口 | `MetaTileEntityEcoEnergyStorage`、`EcoLayouts`、`MetaTileEntityEcoNetworkPart` |
| 供能拆分 | `IAEPowerStorage` 服务从存储接口迁到电池接口；存储结构移除 `ENERGY` 位 | `MetaTileEntityEcoNetworkPart`、`EcoLayouts` |
| 配方 | 四台控制器（LuV 装配线）+ 8 类仓室（IV→UHV）+ 内存/物品/流体元件 | `EcoRecipes` |
| TOP | 控制器/仓室/接口摘要 | `EcoInfoProvider` |
| UI | 面板/卡片/按钮/槽位改 NeoECO 贴图九宫格绘制；电池能量页与净流量 | `EcoGui`、`EcoContainer` |
| 物品外观 | 15 张分等级贴图由 NeoECO 外壳 + 等级灯生成，模型改指新贴图；补回自定义 MetaItem 的客户端模型注册 | `ApplyGrayMetaItemModels`、`assets/applygray/textures/items/metaitems/` |
| 语言 | 新增电池机器/接口/工具提示/能量流量/TOP key，en/zh 同步 | `en_us.lang`、`zh_cn.lang` |
| GT 规范重构 | 控制器拆成 4 个 `MetaTileEntityEco*`；仓室拆成抽象基类 + 8 个 role 类；注册拆到 `registration`；物品移到 `common/items/eco` | 见 2.3 目录树 |
| 大分子装配结构 | 按 NeoECO `createCraftingSystem` 改为 3 宽 × 3 高 × 2 深 + 1–16 段 | `EcoLayouts`、`EcoStructures` |

运行期验证（专用服务端冒烟）：

- 服务器启动到 `Done`，ApplyGray preInit/init 无 ECO 异常。
- ECO 配方全部注册成功；`assembler` 的唯一冲突是既有的 MatterManipulator 配方，与 ECO 无关。
- 首次冒烟发现并修复：仓室配方只改数量、材料集合相同会被 GT 判为冲突；现已为每类仓室分配独占组件（sensor/emitter/field generator/robot arm/conveyor/pump）。
- 客户端启动到主菜单，自定义 MetaItem 模型注册补回后无模型/贴图加载错误。
- 测试：`gradlew test` 全绿（28 个测试类）。
- 资源生成脚本：`tools/eco/README.md`。

## 5. 已确认决策（2026-10-29）

| 编号 | 决策 | 结论 |
|---|---|---|
| D1 | 协议口径 | **结构、UI、物品材质** 可从 NeoECOAEExtension 移植；方块材质与仓室外观不移植。移植内容按 GPL-3.0 声明来源，见第 9 节。 |
| D2 | 电池语义 | AE 多方块电池 = **使用蓄能单元（`EcoPartRole.ENERGY`）的纯 AE 储能多方块**；旧多方块中的蓄能单元移除。不做 EU⇄AE 转换。 |
| D3 | 等级与结构 | **保留 5 级硬件 + 1–16 段可扩展结构**。 |
| D4 | UI | **AE2 `AEBaseGui` 基座 + NeoECO 视觉风格**（移植 GUI 贴图，容器与同步协议不变）。 |
| D5 | 机壳与仓室 | **使用 GT 现有方块与现有仓室 overlay，参考 PrismPlan**；不新增方块材质、不做 NeoECO 机壳。 |
| D6 | 配方电压 | **参考 PrismPlan**：机器/仓室 5 档 `IV → UHV`（第 i 档 EUt = `VA[IV + i]`）；控制器用装配线、LuV、带扫描研究。 |
| D7 | 收尾范围 | **TOP 信息**；指南、成就、单元测试不在本次范围。 |

### 5.1 决策带来的调整

- 结构：`STORAGE_SECTION` 去掉 `ENERGY` 位；新增 `BATTERY_*` 结构，只允许 `ENERGY` 仓。
- 端口：`IAEPowerStorage` 从存储接口迁到电池接口；存储接口只保留 `IStorageProvider`。
- 配方：新增 `EcoRecipes`，按 PrismPlan 的 IV→UHV 五档与 LuV 控制器配方组织。
- UI：`EcoGui` 保留 AE2 容器/同步，仅替换绘制层与贴图；新增电池能量页。
- 结构几何：继续沿用 PrismPlan 的货架/工坊布局（D5 的“参考 PrismPlan”），不移植 NeoECO 的方块模型与方块材质；NeoECO 只用于 UI 与物品材质（D1/D4）。

### 5.2 已知限制

- 电池为纯 AE 储能，不做 EU ⇄ AE 转换。
- 电池接口待机耗电为 0（存储接口仍按元件待机耗电计算）。
- 旧存档里插在存储机架中的蓄能单元不再计入网络能量；拆下后改装到电池结构即可恢复使用。

## 6. 验证清单

### 6.1 已通过（2026-10-29）

| 项 | 方式 | 结果 |
|---|---|---|
| 编译 | `gradlew compileJava` | BUILD SUCCESSFUL |
| 单元测试 | `gradlew test` | 28 个测试类全部通过，0 failures / 0 errors |
| 语言一致性 | `LocalizationResourceTest` + `tools/eco/check_eco_lang.py` | en_us / zh_cn 各 507 条 key，集合一致、无重复、无单边 |
| 资源完整性 | `tools/eco/check_eco_assets.py` | 15 个物品模型引用的贴图齐全；6 张 GUI 贴图齐全 |
| 服务端启动 | `gradlew runServer`（Flat 世界） | 启动到 `Done`，ECO 控制器/仓室/配方注册无异常 |
| GT 规范重构 | 目录/命名/注册方式按 GT 多方块规范 | 编译、测试与服务端冒烟通过 |
| 配方冲突 | 服务端日志 | ECO 无冲突；唯一冲突是既有 MatterManipulator 配方，与 ECO 无关 |
| 客户端启动 | `gradlew runClient` | 启动到主菜单，无模型/贴图加载错误 |

### 6.2 待实机点验

- [ ] 四台机器：成型、拆解、缺块提示、结构乱序。
- [ ] AE 网络：频道接入、任务提交、任务取消、物品存取、电池给网络供能。
- [ ] 存储：驱动仓热插拔元件；拔出后字节/类型不丢。
- [ ] CPU：任务跨区块卸载/重载保留；取消后输入归还。
- [ ] 分子装配：样板总线 63 槽、Worker 批次、产物回网、非法样板回滚。
- [ ] 电池：充放、上限、每段 5 个蓄能单元、拆机保留。
- [ ] UI：四类页面、翻页/搜索/取消、电池能量条与净流量、GT 默认面板不再出现。
- [ ] 配方：生存可造、JEI 多方块预览与配方可查。
- [ ] 结构构建器：AE 多方块构建器自动放置四类结构。
- [ ] 旧存档：存储机架中的旧蓄能单元拆下后能装到电池结构并恢复供能。

### 6.3 复现命令

```powershell
.\gradlew.bat compileJava test
python tools/eco/check_eco_lang.py
python tools/eco/check_eco_assets.py
.\gradlew.bat runServer    # 看 build/runserver*.log 或 run/server/logs/latest.log
.\gradlew.bat runClient
```

## 7. 风险

1. **协议**：已确认可移植结构与 UI/物品材质；包含移植内容的发行物必须按 GPL-3.0 提供，见第 9 节。
2. **ID 稳定性**：已改为显式 ID（控制器 `200–202/206`、接口 `203–205/207`、仓室 `210–245`）；后续新增只能追加到所属段末尾，禁止再引入 ordinal 推导。
3. **GT fork 差异**：本项目依赖 GTQT 的 GT（声明式结构引擎、`FormedStructureView` 等），不能照搬上游 GTCEu 写法。
4. **1.21 → 1.12 移植**：NeoECO 的方块模型、屏幕渲染、菜单无法直接复制，只能移植几何与贴图，代码需按 GT/AE2 Supergiant 重写。
5. **性能**：样板总线 63 槽 × 多段、Worker 批次与内存池预留需要限制每 tick 评估次数（已有 `evaluationsPerTick`）。
6. **AE2 Supergiant API**：`CraftingCPUCluster`、`IStorageProvider`、`IAEPowerStorage` 的语义与上游有差异，需以本地源码为准。

## 8. 参考与协议

| 仓库 | 用途 | 协议 |
|---|---|---|
| `E:\模组开发\GTQT\GregTech` | GT 多方块、仓室、能力、结构引擎写法 | GT 自身协议 |
| `E:\模组开发\资料库\Applied-Energistics-2-Supergiant` | CPU/存储/样板底层 API | AE2 协议 |
| `E:\模组开发\GTQT\PrismPlan` | 旧版 ECO 思路（仅参考，API 已不可用） | GPL-3.0 |
| `E:\模组开发\资料库\NeoECOAEExtension` | 结构、材质、UI 参考/移植来源 | GPL-3.0 |

移植 NeoECO 内容时，源码文件头与 README 需注明来源与协议；不得把 GPL-3.0 代码以 LGPL-3.0-only 分发。

## 9. 移植清单与声明

按 D1/D4/D5，本次只移植以下内容，其余用 GT/AE2 现有资源：

| 内容 | 来源 | 落地位置 |
|---|---|---|
| 多方块结构布局（尺寸/段数/仓室位置） | PrismPlan（GTQT，GPL-3.0）；NeoECO `NEMultiBlocks` 仅作几何参考，本项目为 5 级可扩展版 | [`EcoLayouts`](../src/main/java/applygray/common/metatileentities/multi/electric/eco/EcoLayouts.java)、[`EcoStructures`](../src/main/java/applygray/common/metatileentities/multi/electric/eco/EcoStructures.java) |
| GUI 贴图（背景、按钮、卡片、槽位、侧边栏） | NeoECO `assets/neoecoae/textures/gui/common` | `assets/applygray/textures/gui/eco/` |
| 物品材质（存储元件外壳/等级灯/内存颗粒） | NeoECO `assets/neoecoae/textures/item/eco_cells` 与 `eco_cell_component_*` | `assets/applygray/textures/items/metaitems/` |

声明要求：

- 上述移植文件的目录与 `docs/` 需注明来源：`NeoECOAEExtension (GPL-3.0)`。
- 包含移植内容的发行物按 GPL-3.0 提供；项目自身原创代码仍可按 LGPL-3.0-or-later 授权。
- 不移植 NeoECO 的方块材质与仓室外观（按 D5 使用 GT 现有方块/overlay）。
