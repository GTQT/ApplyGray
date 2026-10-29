# 项目规范

本文件是文件摆放与代码写法约定。新增代码、重构、迁移都按这里执行；与既有代码冲突时，以本文件为准，不要继续复制旧写法。

## 1. 顶层分层

```
src/main/java/meowmel/xxxxx/
  api/          对外稳定契约、数据模型、配方规格
  common/       服务端/通用实现：物品、方块、机器、NBT、网络、事件
  client/       仅客户端：GUI、渲染、纹理、模型、音效
  integration/  模组集成：JEI、TOP、AE2、Baubles 等
  loaders/      注册入口：配方加载
  mixins/       Mixin
```

规则：

- `api/` 不放 Minecraft 世界逻辑、不放 `Item`/`Block`/`MetaTileEntity` 实现。
- `client/` 只放客户端类，服务端类不得 import `client`。
- `common/` 放实现；跨包引用保持单向，避免 `common -> client`。
- 不新建 `microbiology` 这类子领域目录；生物线统一使用 `biology`。

## 2. api 包摆放

| 包 | 用途 |
|---|---|
| `api/capability` | Capability 接口、跨机器能力契约 |
| `api/recipes` | `RecipeMap` 字段、配方规格、配方 Builder |
| `api/metatileentity` | 机器/结构配置：布局、硬件等级、容量、设备定义 |
| `api/item` | 物品相关公共接口/类型 |
| `api/unification` | 材料、矿辞、统一化 |
| `api/utils` | 真正通用的工具与值类型 |
| `api/<domain>` | 领域公共模型（如确有需要） |

注意：

- `api/utils` 不是垃圾桶。基因组、培养状态、任务定义这类领域模型不要全塞进 `api/utils`。
- 同一领域的定义、状态、引擎尽量放在同一领域包内，用嵌套类型收敛数量。
- 公共接口放 `api`，实现类放 `common`。

## 3. common 包摆放

### 3.1 物品

```
common/items/
  MetaItems.java       所有 MetaValueItem 字段
  MetaItem1.java       逐项 addItem 注册
  behaviors/               IItemBehaviour 实现
  <真实 Item 类>.java      只有确实需要独立 Item 时才放这里
common/<domain>/           虚拟物品/假物品的数据与堆栈
  particle/                Particle、ParticleStack、ParticleList
  nanochip/                CircuitComponent、NanochipPartType、ItemId
```

规则：

- 普通自定义物品（前提是普通的，假物品除外）必须走 `MetaItems` + `MetaValueItem`。
- 一个物品一个字段、一行 `addItem`，写在它自己的注册位置。
- 禁止用数组、`List`、`Map`、循环批量注册物品。
- 禁止 `Item` + `setHasSubtypes(true)` + `public static INSTANCE` 的假物品写法。
- 如果确实是“虚拟物品/假物品”，按 3.1.1 处理。
- `IItemBehaviour`、物品 inventory/session 等辅助类放 `common/items/behaviors`，不要单独开 `common/items/<feature>`。

#### 3.1.1 假物品 / 虚拟物品

定义：只作为配方、JEI、GUI 或网络数据存在，玩家不能在物品栏正常获得的“物品”。参考 `common/particle`（`ParticleStack` + `ingredient/` + JEI 注册）。

- 不注册 `Item`：不写 `new ItemX()`、不占用 `MetaItems` 的 MetaValueItem ID、不写 `setHasSubtypes(true)` / `public static INSTANCE`。
- 数据与堆栈放 `common/<domain>`（如 `common/particle/ParticleStack`）：提供 `EMPTY`、`copy()`、NBT 读写、`equals`/`hashCode` 和稳定的注册名。
- 展示走自定义 ingredient：`common/<domain>/ingredient/` 放 `IIngredientRenderer` / `IIngredientHelper`，JEI 插件在 `integration/jei` 中注册；渲染器直接画贴图，不经过物品模型、材质或 `ItemColors`。
- 配方与存档：使用该虚拟 `XxxStack`（或 recipe property）作为输入/输出与 NBT 单位，不要为了复用 `ItemStack` 接口而注册假 `Item`；仅当确有真实物品对应时才做转换。
- 不补物品模型 JSON、物品材质、物品 lang key；显示名由 ingredient helper 提供。
- 禁止为了 JEI 预览、配方书或 GUI 注册一个正常游戏拿不到的 `Item`。

#### 3.1.2 注册入口

- 物品注册集中在 `MetaItems` / `MetaItem1`（MetaItem）与 `Items`（独立物品、工具；假物品不在此列）。
- 方块与方块物品注册集中在 `MetaBlocks`（`registerBlocks` / `registerItemBlocks`）。
- `CommonProxy` / `ClientProxy` 只订阅事件并转发（如 `MetaBlocks.registerBlocks(registry)`、`Items.register(registry)`），不得出现 `new ItemX()`、`registry.register(...)` 或大段注册列表。
- 客户端物品模型/材质/贴图注册集中在 `client/renderer/ItemModelRegistry`，由各物品 renderer 提供细节；`ClientProxy` 只调用 `ItemModelRegistry`，不得出现具体物品名。
- 注册顺序稳定、显式、与字段声明顺序一致；新增物品/方块加到所属段末尾。

### 3.2 机器

```
common/metatileentities/
  MetaTileEntities.java              单方块/多方块注册入口
  multi/electric/<feature>/
    MetaTileEntityX.java                 机器主类
    logic/                               RecipeLogic、执行器
    gui/                                 ModularUI 面板
    hatch/                               仓口
    modules/                             模块
    util/                                机器内部工具
```

规则：

- 单方块写在 `MetaTileEntities` 的“单方块”段，多方块写在“多方块”段。
- 机器 ID 显式、连续、按段分配；不要从 catalog/字符串动态推导。
- `RecipeMap`、`ICubeRenderer`、贴图显式传入构造器；禁止用 `deviceId` 字符串查 overlay/recipe map。
- 单方块用标准 `RecipeLogicEnergy`/`RecipeLogic`，多方块用 `MultiblockRecipeLogic`。
- 禁止“空实现 `update()` / `updateWorkable()`，再由别的类手动 tick”的假 Logic。
- 禁止 `*Runtime` 单例、`*Module` 门面、`*Lifecycle` 这类非项目约定抽象。
- 机器状态、进度、EU/t 由 Logic 自己维护，UI 只读。

### 3.3 方块、NBT、网络

- 方块：`common/blocks`。
- NBT 编解码：`common/nbt`，一个 schema 一个 codec。
- 网络入口：`common/network/PacketHandler`，只放注册与发送入口。
- 网络消息：`common/network/packet`，所有 `IMessage`/网络 payload 放这里，并在 `PacketHandler` 显式注册；入口与消息不得同层。
- 事件：`common/event`。
- 虚拟/领域数据：`common/<domain>`。

## 4. client 包摆放

```
client/textures/Textures.java     机器 overlay、静态贴图字段
client/gui/                           普通 GuiScreen
client/renderer/                      自定义渲染
client/model/                         模型
```

规则：

- 机器 overlay、`SimpleOverlayRenderer`、`OrientedOverlayRenderer` 统一进 `Textures`，不要为单台机器新建 `XxxTextures`。
- `client/gui` 只放屏幕；机器 ModularUI 面板放机器包的 `gui/`。
- 物品纹理：`assets/gtqtcore/textures/items/metaitems/<name>.png`。
- 方块纹理：`assets/gtqtcore/textures/blocks/...`。

## 5. 配方摆放

```
loaders/recipe/
  RecipeManager.java        总入口
  chain/<domain>/               真正的生产链
  <feature>/                    功能配方注册器
  multiblock/                   多方块/结构配方
  circuit/                      电路
  fuel/                         燃料
  oreprocessing/                矿物处理
```

规则：

- 只有“多步生产链”才放 `chain/`。
- 功能内容配方放 `loaders/recipe/<feature>`。
- 多方块机器外壳、控制器、结构配方放 `loaders/recipe/multiblock`。
- 配方注册器统一 `public static void register()`，由 `RecipeManager` 显式调用。
- `RecipeMap` 字段统一在 `RecipeMaps`，显式命名、显式槽位。
- 禁止用字符串在运行时查 RecipeMap/overlay。

### 5.1 配方写法

静态配方直接引用物品/流体，不经过字符串索引，也不自建 `*Preparation` 一类配方 DSL：

```java
RecipeMaps.ASSEMBLER_RECIPES.recipeBuilder()
        .input(OrePrefix.gem, Materials.NetherQuartz, 2)
        .input(Blocks.GLASS_PANE, 2)
        .output(MetaItems.BIOLOGY_UV_QUARTZ_SLEEVE, 2)
        .EUt(VA[MV]).duration(5 * SECOND).buildAndRegister();
```

规则：

- 优先用 builder 重载：`.input(OrePrefix, Material, int)`、`.input(Block/Item, int)`、`.input(MetaValueItem, int)`、`.output(MetaValueItem, int)`、`.fluidInputs(Material, int)`。
- 输入/输出是现成 `ItemStack` 或方块变体时，用 `.inputs(...)` / `.outputs(...)`；不要为了套 `.input` 手工拆 `ItemStack`。
- `EUt` 用 `VA[LV/MV/HV/EV/IV/LuV/ZPM/UV]`，不要写 `30`、`480`、`1920`。
- `duration` 用 `n * SECOND` / `n * TICK`，不要写裸 `100`、`200`、`400`。
- 禁止在静态配方里写 `BiologyItems.biologyItem("id")`、`BiologyFluids.biologyFluid("id")`、`OreDictUnifier.get(...)`、`new ItemStack(...)`。
- 字符串 id 只允许出现在“配方内容本身来自运行期数据”的注册器里，例如遍历 `OperationDefinition`、`DishPreparation` 的 id。
- 禁止自建 `UvLampPreparation`、`MutagenesisCasingPreparation` 这种平行配方结构；配方直接调用 `recipeBuilder()`。
- 配方注册器统一 `public static void register()`，由功能聚合器显式调用；不要新增独立启动入口。

## 6. 文本与 Tooltip

- 客户端 tooltip 统一 `net.minecraft.client.resources.I18n.format(...)`。
- 禁止 `net.minecraft.util.text.translation.I18n.translateToLocal*`；服务端文本用 `Translator`。
- 机器 `getDescription()` 用 `I18n.format`，不要手写 `translateToLocalFormatted`。
- 物品 tooltip 优先用 lang key：`metaitem.<unlocalized>.tooltip`。
- 能用 lang 解决时，不要写 `TooltipBehavior`。
- 需要本地化的新增文本必须同时补 `en_us.lang` 和 `zh_cn.lang`；仅中文硬编码文本见 6.3。

### 6.1 lang 文件结构

- 语言文件只有 `src/main/resources/assets/gtqtcore/lang/en_us.lang` 和 `zh_cn.lang`，UTF-8 无 BOM、CRLF。
- 新 key 必须放到对应功能区块，紧挨该功能已有 key：材料在 `# Ore Prefixes` 之前，流体/物品/方块/配方/机器分别进 `# fluids`、`# Items`、`# Blocks`、`# RecipeMaps`、`# MetaTileEntity`，UI/命令/市场文本放 `# Misc` 下的 UI 区或对应功能区块，JEI/微生物进各自区块。
- 禁止把新 key 追加到文件末尾，禁止再建 `# Player-facing text localization` 这类“杂物区”，也禁止把 GUI、命令、市场、机器文本混在同一个新增区块里。
- 同一功能的 key 连续放置，不要跨区块散落；全文件不要求字母序，但同一组内保持局部有序。
- `en_us.lang` 和 `zh_cn.lang` 的新增/删除必须两边同步，放在同一锚点（同一相邻 key 之后）；历史遗留的 `gtqtcore.circuit_component.*` 等单边 key 不扩大。
- 删除 key 前必须全项目搜索引用：`Translator.translate`、`I18n.format`、`TextComponentTranslation`、`InformationHandler.topTooltips`、`KeyUtil.lang`、`IKey.lang`、`setTooltips`、`TechNode`、枚举标签、`tr.format`、常量字段都算引用。
- 只删 lang 行、不处理 Java 引用，视为未完成。

### 6.2 key 命名与值格式

- 新 key 用 `<命名空间>.<功能>.<语义短名>`；优先功能名和稳定语义，不要照搬 Java 类名，也不要把整句英文/中文压成 key。
- 同一功能的多条文本用 `...tooltip.1`、`...status.failed`、`...button.confirm` 这类后缀区分，不要用 `_2`、`_3` 这种无意义后缀。
- 多参数用 `%1$s`/`%2$s` 并保持参数顺序；单参数可用 `%s`；值里的字面 `%` 写成 `%%`。
- 新 key 的值不要写 `§` 颜色/样式码，颜色由代码的 `TextFormatting`/`Style` 控制；历史值可保留。
- 值不要以空格结尾、不要用 Tab、不要换行；`zh_cn.lang` 用中文（专有名词、单位、版本号可保留英文），`en_us.lang` 用英文。
- 禁止重复 key；重复 key 是后值覆盖前值，不要依赖覆盖行为。

### 6.3 硬编码与 lang 的边界

- 需要本地化的玩家文本（物品、方块、机器、GUI、命令、提示）必须走 lang key。
- 仅中文、不打算翻译的文本可以直接硬编码中文；硬编码后不得再保留同名 lang key。
- 禁止新增硬编码英文玩家文本；英文或多语言文本必须走 lang。
- 机器/配方 ID、NBT key、资源路径不要拿来当 lang key。

## 7. 命名与导入

- 类名：`MetaTileEntityX`、`XLogic`、`XRecipeLogic`、`XGUI`、`XTextures`、`XCodec`。
- 包名全小写，不用下划线；领域名用完整单词。
- 禁止 `import xxx.*;`。只导入实际使用的类。
- 嵌套类只在确实属于同一领域时使用；不要为了少建文件把无关类硬塞一起。
- 注释写“为什么”，不写“是什么”；段注释用 `// ##`、`// ###`。

## 8. 注册与兼容

- `MetaValueItem` 的 ID、NBT key、流体名、配方 ID 一经发布不得随意改。
- 必须改时提供迁移逻辑，不能直接换号。
- 注册顺序必须稳定，不依赖 `HashMap`/`HashSet` 遍历顺序。
- 新增机器/物品 ID 放在自己所属段的末尾，不插空、不跳号。

## 9. 禁止清单

以下写法不要出现在新代码里：

- `import ...*;`
- `I18n.translateToLocalFormatted(...)` / `I18n.translateToLocal(...)`
- `Item` + `setHasSubtypes(true)` + `INSTANCE` 的假物品
- 数组/`List`/`Map`/循环批量注册物品
- 用字符串 `deviceId` 查 `RecipeMap`、overlay、贴图
- 空实现的 `update()` / `updateWorkable()`
- `*Runtime`、`*Module`、`*Lifecycle` 单例/门面
- 把领域模型全塞 `api/utils`
- 机器注册依赖 `BiologyContent.catalog()` 之类的运行时 catalog 遍历
- 在 `common` 里 import `client`
- 未补 lang 的硬编码英文
- 把新 key 追加到 lang 文件末尾，或新建 `# Player-facing text localization` 之类的杂物区
- 只在一侧 lang 新增 key
- 删除 lang key 但不同步修改 Java 引用
- 同一文本既硬编码又保留 lang key

## 10. 提交前检查

1. `./gradlew compileJava` 通过。
2. 没有新增 `import ...*;`。
3. 物品/机器注册位置正确，ID 连续且属于正确段。
4. 需要本地化的新增/删除文本已同步 `en_us.lang` 和 `zh_cn.lang`，key 在对应功能区块，无重复 key、无死 key；硬编码文本未保留 lang key。
5. 没有把实现类放进 `api`，没有把客户端类放进 `common`。
6. 没有复制旧的反模式（假物品、假 Logic、字符串查表）。
7. 新增 key 的占位符与调用参数一致（`%s`/`%1$s`，字面 `%` 用 `%%`）。
