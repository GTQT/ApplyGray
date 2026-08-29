package applygray.mattermanipulator;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import applygray.mattermanipulator.building.BlockSpec;
import applygray.mattermanipulator.state.ManipulatorMaterialPicker;
import applygray.mattermanipulator.state.ManipulatorPickTarget;
import applygray.mattermanipulator.state.ManipulatorState;
import net.minecraft.init.Bootstrap;

class ManipulatorMaterialPickerTest {

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        Bootstrap.register();
    }

    @Test
    void airCanReplaceAConfiguredGeometryMaterial() {
        ManipulatorState state = new ManipulatorState();
        state.setPickTarget(ManipulatorPickTarget.ALL);
        state.geometryConfiguration().setAll(BlockSpec.fromState(
                net.minecraft.init.Blocks.STONE.getDefaultState()));

        assertTrue(ManipulatorMaterialPicker.apply(state, BlockSpec.air()) == ManipulatorMaterialPicker.Result.SET);
        assertTrue(state.geometryConfiguration().corners().select(new java.util.Random(0L)).isAir());
        assertTrue(state.geometryConfiguration().edges().select(new java.util.Random(0L)).isAir());
        assertTrue(state.geometryConfiguration().faces().select(new java.util.Random(0L)).isAir());
        assertTrue(state.geometryConfiguration().volumes().select(new java.util.Random(0L)).isAir());
    }

    @Test
    void airCanBeSelectedAsExchangeReplacement() {
        ManipulatorState state = new ManipulatorState();
        state.setPickTarget(ManipulatorPickTarget.EXCHANGE_REPLACEMENT);

        ManipulatorMaterialPicker.apply(state, BlockSpec.air());

        assertTrue(state.exchangeReplacement().select(new java.util.Random(0L)).isAir());
    }
}
