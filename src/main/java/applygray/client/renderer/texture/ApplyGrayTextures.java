package applygray.client.renderer.texture;

import applygray.api.ApplyGrayAPI;

import gregtech.client.renderer.texture.cube.SimpleOverlayRenderer;

public final class ApplyGrayTextures {

    public static final SimpleOverlayRenderer ME_BUFFER_HATCH_OVERLAY = overlay("overlay/machine/me_buffer_hatch");
    public static final SimpleOverlayRenderer ME_BUFFER_HATCH_PROXY_OVERLAY = overlay("overlay/machine/me_buffer_hatch_proxy");
    public static final SimpleOverlayRenderer ME_MUFFLER_OVERLAY = overlay("overlay/machine/overlay_me_muffler");

    public static final SimpleOverlayRenderer ME_OUTPUT_HATCH = overlay("overlay/appeng/me_output_hatch");
    public static final SimpleOverlayRenderer ME_OUTPUT_HATCH_ACTIVE = overlay("overlay/appeng/me_output_hatch_active");
    public static final SimpleOverlayRenderer ME_INPUT_HATCH = overlay("overlay/appeng/me_input_hatch");
    public static final SimpleOverlayRenderer ME_INPUT_HATCH_ACTIVE = overlay("overlay/appeng/me_input_hatch_active");
    public static final SimpleOverlayRenderer ME_OUTPUT_BUS = overlay("overlay/appeng/me_output_bus");
    public static final SimpleOverlayRenderer ME_OUTPUT_BUS_ACTIVE = overlay("overlay/appeng/me_output_bus_active");
    public static final SimpleOverlayRenderer ME_INPUT_BUS = overlay("overlay/appeng/me_input_bus");
    public static final SimpleOverlayRenderer ME_INPUT_BUS_ACTIVE = overlay("overlay/appeng/me_input_bus_active");
    public static final SimpleOverlayRenderer ME_DUAL_INPUT_HATCH_ACTIVE = overlay("overlay/appeng/me_dual_input_hatch_active");
    public static final SimpleOverlayRenderer ME_DUAL_INPUT_HATCH = overlay("overlay/appeng/me_dual_input_hatch");
    public static final SimpleOverlayRenderer ME_DUAL_OUTPUT_HATCH_ACTIVE = overlay("overlay/appeng/me_dual_output_hatch_active");
    public static final SimpleOverlayRenderer ME_DUAL_OUTPUT_HATCH = overlay("overlay/appeng/me_dual_output_hatch");

    public static final SimpleOverlayRenderer MATTER_MANIPULATOR_UPLINK_FRONT_OFF =
            overlay("matter_manipulator/uplink/front_off");
    public static final SimpleOverlayRenderer MATTER_MANIPULATOR_UPLINK_FRONT_IDLE_GLOW =
            overlay("matter_manipulator/uplink/front_idle_glow");
    public static final SimpleOverlayRenderer MATTER_MANIPULATOR_UPLINK_FRONT_ACTIVE_GLOW =
            overlay("matter_manipulator/uplink/front_active_glow");

    private ApplyGrayTextures() {}

    public static void init() {}

    private static SimpleOverlayRenderer overlay(String path) {
        return new SimpleOverlayRenderer(ApplyGrayAPI.MODID + ":" + path);
    }
}
