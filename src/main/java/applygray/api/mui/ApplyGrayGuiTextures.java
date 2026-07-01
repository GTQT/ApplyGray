package applygray.api.mui;

import applygray.api.ApplyGrayAPI;

import com.cleanroommc.modularui.drawable.ColorType;
import com.cleanroommc.modularui.drawable.UITexture;
import org.jetbrains.annotations.Nullable;

public final class ApplyGrayGuiTextures {

    public static final UITexture PROXY_OVERLAY = fullImage("textures/gui/widget/proxy_overlay.png");
    public static final UITexture EXPORT_OVERLAY = fullImage("textures/gui/widget/pattern_export.png");
    public static final UITexture BUTTON_DUAL_OUTPUT = fullImage("textures/gui/widget/button_dual_output_overlay.png");
    public static final UITexture BUTTON_DUAL_COLLAPSE = fullImage("textures/gui/widget/button_dual_collapse_overlay.png");
    public static final UITexture PATTERN_OVERLAY = fullImage("textures/gui/overlay/pattern_overlay.png", ColorType.DEFAULT);
    public static final UITexture EXTRA_SLOT_OVERLAY = fullImage("textures/gui/overlay/extra_slot.png", ColorType.DEFAULT);
    public static final UITexture BUTTON_ITEM_OUTPUT = fullImage("textures/gui/widget/button_item_output_overlay.png");
    public static final UITexture BUTTON_FLUID_OUTPUT = fullImage("textures/gui/widget/button_fluid_output_overlay.png");
    public static final UITexture BUTTON_AUTO_COLLAPSE = fullImage("textures/gui/widget/button_auto_collapse_overlay.png");
    public static final UITexture BUTTON_X = fullImage("textures/gui/widget/button_x_overlay.png", ColorType.DEFAULT);
    public static final UITexture BUTTON_CLEAR_GRID = fullImage("textures/gui/widget/button_clear_grid.png", ColorType.DEFAULT);
    public static final UITexture BUTTON_CROSS = fullImage("textures/gui/widget/button_cross.png");
    public static final UITexture BUTTON_REDSTONE_ON = fullImage("textures/gui/widget/button_redstone_on.png");
    public static final UITexture BUTTON_REDSTONE_OFF = fullImage("textures/gui/widget/button_redstone_off.png");
    public static final UITexture BUTTON_POWER_DETAIL = fullImage("textures/gui/widget/button_power_detail.png");
    public static final UITexture BUTTON_AUTO_PULL = fullImage("textures/gui/widget/button_me_auto_pull.png");
    public static final UITexture[] AUTO_PULL = slice("textures/gui/widget/button_me_auto_pull.png",
            16, 32, 16, 16, ColorType.DEFAULT);

    private ApplyGrayGuiTextures() {}

    public static UITexture fullImage(String path) {
        return fullImage(path, null);
    }

    public static UITexture fullImage(String path, @Nullable ColorType colorType) {
        return UITexture.fullImage(ApplyGrayAPI.MODID, path, colorType);
    }

    public static UITexture[] slice(String path, int imageWidth, int imageHeight, int sliceWidth, int sliceHeight,
                                    @Nullable ColorType colorType) {
        if (imageWidth % sliceWidth != 0 || imageHeight % sliceHeight != 0) {
            throw new IllegalArgumentException("Slice height and slice width must divide the image evenly!");
        }

        int countX = imageWidth / sliceWidth;
        int countY = imageHeight / sliceHeight;
        UITexture[] slices = new UITexture[countX * countY];

        for (int indexX = 0; indexX < countX; indexX++) {
            for (int indexY = 0; indexY < countY; indexY++) {
                slices[indexX * countY + indexY] = UITexture.builder()
                        .location(ApplyGrayAPI.MODID, path)
                        .colorType(colorType)
                        .imageSize(imageWidth, imageHeight)
                        .xy(indexX * sliceWidth, indexY * sliceHeight, sliceWidth, sliceHeight)
                        .build();
            }
        }
        return slices;
    }
}
