package applygray.common.metatileentities.multi.electric.eco;

import gregtech.api.pattern.casing.DeclarativePatternBuilder;
import gregtech.api.pattern.casing.GTStructureChannels;
import gregtech.api.pattern.element.StructureDefinition;
import gregtech.api.util.RelativeDirection;
import gregtech.common.blocks.BlockComputerCasing;
import gregtech.common.blocks.MetaBlocks;

import applygray.common.metatileentities.multi.multiblockpart.eco.EcoAbilities;

import net.minecraft.block.state.IBlockState;

import static gregtech.api.pattern.element.Elements.abilities;
import static gregtech.api.pattern.element.Elements.block;
import static gregtech.api.pattern.element.Elements.choice;

/**
 * 四个 ECO 控制器的结构定义。
 *
 * <p>结构写法与 CEU 的 {@code MetaTileEntityDataBank} 一致：多 piece 顺序、控制器放在中间 piece、
 * 可重复翼沿 aisle 方向展开。字符轴用 {@link RelativeDirection#BACK}，char 0 是控制器所在平面，
 * char 1 在其背后，所以控制器正面朝外时结构在背后成型。</p>
 *
 * <p>JEI 预览的正面由 {@code MetaTileEntityEcoController#getPreviewFrontFacing()} 固定为 SOUTH，
 * 与 DataBank / AssemblyLine 的写法一致，避免预览把结构旋转 180° 导致按 JEI 摆放却不成型。</p>
 *
 * <p>与 GT 的 {@code MetaTileEntityDataBank} 一样，结构在类加载时构建一次并复用。</p>
 */
final class EcoStructures {

    private EcoStructures() {}

    static StructureDefinition<?> computer() {
        return DeclarativePatternBuilder.start(RelativeDirection.BACK, RelativeDirection.UP,
                        RelativeDirection.RIGHT)
                .piece("front").aisle(EcoLayouts.RACK_CAP).aisle(EcoLayouts.RACK_CONTROLLER).end()
                .repeatablePiece("rack", 1, EcoTiers.MAX_SEGMENTS)
                .aisle(EcoLayouts.COMPUTER_SECTION)
                .withAisleChannel(GTStructureChannels.STRUCTURE_LENGTH.getName()).end()
                .piece("rear").aisle(EcoLayouts.RACK_REAR).end()
                .block('X', casing())
                .block('H', vent())
                .block('U', MetaBlocks.COMPUTER_CASING.getState(
                        BlockComputerCasing.CasingType.ADVANCED_COMPUTER_CASING))
                .where('T', abilities(EcoAbilities.MEMORY))
                .where('P', abilities(EcoAbilities.PARALLEL))
                .where('F', choice(abilities(EcoAbilities.THREAD), abilities(EcoAbilities.HYPER_THREAD)))
                .self('S', MetaTileEntityEcoComputer.class)
                .where('N', abilities(EcoAbilities.network(EcoControllerType.COMPUTER)))
                .buildStructureDefinition();
    }

    static StructureDefinition<?> storage() {
        return DeclarativePatternBuilder.start(RelativeDirection.BACK, RelativeDirection.UP,
                        RelativeDirection.RIGHT)
                .piece("front").aisle(EcoLayouts.RACK_CAP).aisle(EcoLayouts.RACK_CONTROLLER).end()
                .repeatablePiece("rack", 1, EcoTiers.MAX_SEGMENTS)
                .aisle(EcoLayouts.STORAGE_SECTION)
                .withAisleChannel(GTStructureChannels.STRUCTURE_LENGTH.getName()).end()
                .piece("rear").aisle(EcoLayouts.RACK_REAR).end()
                .block('X', casing())
                .block('H', vent())
                .where('T', abilities(EcoAbilities.DRIVE))
                .self('S', MetaTileEntityEcoStorage.class)
                .where('N', abilities(EcoAbilities.network(EcoControllerType.STORAGE)))
                .buildStructureDefinition();
    }

    static StructureDefinition<?> assembler() {
        // 与 NeoECO 的 crafting system 同构：控制器居中，ME 接口在背面一侧，
        // 工坊翼接在外壳一侧。piece 顺序保证翼沿 +aisle 方向展开，和货架类一致（向左）。
        return DeclarativePatternBuilder.start(RelativeDirection.BACK, RelativeDirection.UP,
                        RelativeDirection.RIGHT)
                .piece("interface").aisle(EcoLayouts.WORKSHOP_INTERFACE).end()
                .piece("controller").aisle(EcoLayouts.WORKSHOP_CONTROLLER).end()
                .piece("casing").aisle(EcoLayouts.WORKSHOP_CASING).end()
                .repeatablePiece("workshop", 1, EcoTiers.MAX_SEGMENTS)
                .aisle(EcoLayouts.WORKSHOP_SECTION)
                .withAisleChannel(GTStructureChannels.STRUCTURE_LENGTH.getName()).end()
                .block('X', casing())
                .block('H', vent())
                .where('W', abilities(EcoAbilities.WORKER))
                .where('B', abilities(EcoAbilities.PATTERN_BUS))
                .self('S', MetaTileEntityEcoMolecularAssembler.class)
                .where('N', abilities(EcoAbilities.network(EcoControllerType.ASSEMBLER)))
                .buildStructureDefinition();
    }

    static StructureDefinition<?> battery() {
        return DeclarativePatternBuilder.start(RelativeDirection.BACK, RelativeDirection.UP,
                        RelativeDirection.RIGHT)
                .piece("front").aisle(EcoLayouts.RACK_CAP).aisle(EcoLayouts.RACK_CONTROLLER).end()
                .repeatablePiece("rack", 1, EcoTiers.MAX_SEGMENTS)
                .aisle(EcoLayouts.BATTERY_SECTION)
                .withAisleChannel(GTStructureChannels.STRUCTURE_LENGTH.getName()).end()
                .piece("rear").aisle(EcoLayouts.RACK_REAR).end()
                .block('X', casing())
                .block('H', vent())
                .where('J', choice(block(casing()), abilities(EcoAbilities.ENERGY)))
                .self('S', MetaTileEntityEcoEnergyStorage.class)
                .where('N', abilities(EcoAbilities.network(EcoControllerType.BATTERY)))
                .buildStructureDefinition();
    }

    private static IBlockState casing() {
        return MetaBlocks.COMPUTER_CASING.getState(BlockComputerCasing.CasingType.COMPUTER_CASING);
    }

    private static IBlockState vent() {
        return MetaBlocks.COMPUTER_CASING.getState(BlockComputerCasing.CasingType.COMPUTER_HEAT_VENT);
    }
}
