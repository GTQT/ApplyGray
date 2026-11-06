package applygray.mixins.supergiant;

import ae2.client.gui.AEBaseGui;
import ae2.client.gui.Icon;
import ae2.client.gui.Tooltip;
import ae2.client.gui.WidgetContainer;
import ae2.client.gui.me.patternencode.ProcessingEncodingPanel;
import ae2.client.gui.widgets.ActionButton;
import ae2.client.gui.widgets.Scrollbar;
import ae2.client.gui.widgets.ToggleButton;
import ae2.container.me.patternencode.ContainerPatternEncodingTerm;
import ae2.integration.Integrations;
import applygray.integration.jei.AutoPatternItemNaming;
import applygray.integration.jei.PatternEncodingPanelContainerAccess;
import applygray.integration.jei.ProcessingPatternCircuitImportMode;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.util.text.TextComponentTranslation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.Rectangle;
import java.util.List;
import java.util.function.Consumer;

@Mixin(value = ProcessingEncodingPanel.class, remap = false)
public abstract class MixinProcessingEncodingPanel {
    @Unique
    private static final int APPLYGRAY_BUTTON_SPACING = 10;

    @Unique
    private static final float APPLYGRAY_HALF_SIZE_ICON_SCALE = 0.5F;

    @Shadow
    @Final
    private ActionButton clearBtn;

    @Unique
    private ToggleButton applygray$ignoreCircuitButton;

    @Unique
    private ToggleButton applygray$autoNameButton;

    @Unique
    private ProcessingPatternCircuitImportMode applygray$importMode;

    @Unique
    private AutoPatternItemNaming applygray$namingMode;

    @Unique
    private ContainerPatternEncodingTerm applygray$container;

    @Redirect(
        method = "<init>(Lae2/client/gui/AEBaseGui;Lae2/client/gui/WidgetContainer;)V",
        at = @At(
            value = "INVOKE",
            target = "Lae2/client/gui/widgets/Scrollbar;setRange(III)V"),
        require = 1)
    private void applygray$includeAllOutputSlots(
        Scrollbar scrollbar, int min, int max, int pageSize
    ) {
        int outputSlots = ((PatternEncodingPanelContainerAccess) this)
            .applygray$getContainer()
            .getProcessingOutputSlots().length;
        scrollbar.setRange(min, Math.max(max, outputSlots - pageSize), pageSize);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void applygray$addHeiImportButtons(
        AEBaseGui<?> screen, WidgetContainer widgets, CallbackInfo ci
    ) {
        this.applygray$container = ((PatternEncodingPanelContainerAccess) this).applygray$getContainer();
        this.applygray$importMode = (ProcessingPatternCircuitImportMode) this.applygray$container;
        this.applygray$namingMode = (AutoPatternItemNaming) this.applygray$container;

        this.applygray$ignoreCircuitButton = new ToggleButton(
            Icon.S_SUBSTITUTION_DISABLED,
            Icon.S_SUBSTITUTION_ENABLED,
            this::applygray$setIgnoreCircuit);
        this.applygray$ignoreCircuitButton.setHalfSize(true);
        this.applygray$ignoreCircuitButton.setDisableBackground(true);
        this.applygray$ignoreCircuitButton.setTooltipOn(List.of(
            new TextComponentTranslation("applygray.encoding.tooltip.ignore_circuit"),
            new TextComponentTranslation("applygray.encoding.tooltip.ignore_circuit.on")));
        this.applygray$ignoreCircuitButton.setTooltipOff(List.of(
            new TextComponentTranslation("applygray.encoding.tooltip.ignore_circuit"),
            new TextComponentTranslation("applygray.encoding.tooltip.ignore_circuit.off")));

        this.applygray$autoNameButton = new ToggleButton(
            Icon.PATTERN_AUTO_FILL_ON,
            Icon.PATTERN_AUTO_FILL_OFF,
            this::applygray$setAutoNameItems);
        this.applygray$autoNameButton.setHalfSize(true);
        this.applygray$autoNameButton.setDisableBackground(true);
        this.applygray$autoNameButton.setIconScale(APPLYGRAY_HALF_SIZE_ICON_SCALE);
        this.applygray$autoNameButton.setTooltipOn(List.of(
            new TextComponentTranslation("applygray.encoding.tooltip.auto_name"),
            new TextComponentTranslation("applygray.encoding.tooltip.auto_name.on")));
        this.applygray$autoNameButton.setTooltipOff(List.of(
            new TextComponentTranslation("applygray.encoding.tooltip.auto_name"),
            new TextComponentTranslation("applygray.encoding.tooltip.auto_name.off")));
    }

    @Inject(method = "updateBeforeRender", at = @At("TAIL"))
    private void applygray$updateHeiImportButtons(CallbackInfo ci) {
        this.applygray$ignoreCircuitButton.setState(this.applygray$shouldIgnoreCircuit());
        this.applygray$ignoreCircuitButton.x = this.clearBtn.x;
        this.applygray$ignoreCircuitButton.y = this.clearBtn.y + APPLYGRAY_BUTTON_SPACING;

        this.applygray$autoNameButton.setState(this.applygray$namingMode.applygray$autoNamePatternItems());
        this.applygray$autoNameButton.x = this.clearBtn.x;
        this.applygray$autoNameButton.y = this.clearBtn.y + 3 * APPLYGRAY_BUTTON_SPACING;
    }

    @Inject(method = "setVisible", at = @At("TAIL"))
    private void applygray$setHeiImportButtonsVisible(boolean visible, CallbackInfo ci) {
        this.applygray$ignoreCircuitButton.setVisibility(visible);
        this.applygray$autoNameButton.setVisibility(visible);
    }

    @Unique
    private boolean applygray$shouldIgnoreCircuit() {
        return this.applygray$importMode.applygray$ignoreCircuit();
    }

    @Unique
    private void applygray$setIgnoreCircuit(boolean ignoreCircuit) {
        this.applygray$importMode.applygray$setIgnoreCircuit(ignoreCircuit);
    }

    @Unique
    private void applygray$setAutoNameItems(boolean autoName) {
        this.applygray$namingMode.applygray$setAutoNamePatternItems(autoName);
        if (autoName) {
            // Auto naming keeps every occurrence in its own slot, so use the existing HEI merge switch to match.
            this.applygray$container.setMergeHeiProcessingInputs(false);
        }
    }

    @Unique
    @SuppressWarnings("all")
    public void populateScreen(Consumer<GuiButton> addWidget, Rectangle bounds, AEBaseGui<?> baseGui) {
        if(Integrations.hei().isEnabled()) {
            addWidget.accept(this.applygray$ignoreCircuitButton);
            addWidget.accept(this.applygray$autoNameButton);
        }
    }

    @Unique
    @SuppressWarnings("all")
    public boolean wantsAllMouseDownEvents() {
        return true;
    }

    @Unique
    @SuppressWarnings("all")
    public Tooltip getTooltip(int mouseX, int mouseY) {
        if(!Integrations.hei().isEnabled()) {
            return null;
        }

        if(this.applygray$autoNameButton.visible && this.applygray$autoNameButton.isMouseOver()) {
            return new Tooltip(applygray$autoNameButton.getTooltipMessage());
        }
        if(this.applygray$ignoreCircuitButton.visible && this.applygray$ignoreCircuitButton.isMouseOver()) {
            return new Tooltip(applygray$ignoreCircuitButton.getTooltipMessage());
        }
        return null;
    }
}
