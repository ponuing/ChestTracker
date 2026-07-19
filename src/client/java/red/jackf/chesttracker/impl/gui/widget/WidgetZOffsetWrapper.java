package red.jackf.chesttracker.impl.gui.widget;

import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.jetbrains.annotations.Nullable;
import red.jackf.chesttracker.mixins.AbstractWidgetAccessor;

/**
 * Wrapper for a widget that renders it with a Z offset on the screen.
 */
public class WidgetZOffsetWrapper<T extends AbstractWidget> extends AbstractWidget {
    private final T baseWidget;
    private final int zOffset;

    public WidgetZOffsetWrapper(T baseWidget, int zOffset) {
        super(baseWidget.getX(), baseWidget.getY(), baseWidget.getWidth(), baseWidget.getHeight(), baseWidget.getMessage());
        this.baseWidget = baseWidget;
        this.zOffset = zOffset;
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.pose().pushMatrix(); // 2D equivalent of pushPose()
        guiGraphics.pose().translate(0.0f, 0.0f); // only X and Y; no Z in Matrix3x2fStack
        ((AbstractWidgetAccessor) baseWidget).renderWidget(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.pose().popMatrix(); // 2D equivalent of popPose()
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        // TODO try fix
        // this crashes with a non-descriptive IllegalAccessException when used on AutoComplete
        // ((AbstractWidgetAccessor) baseWidget).updateWidgetNarration(output);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        baseWidget.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return baseWidget.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return baseWidget.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        return baseWidget.keyReleased(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return baseWidget.charTyped(event);
    }

    @Nullable
    @Override
    public ComponentPath getCurrentFocusPath() {
        return baseWidget.getCurrentFocusPath();
    }

    @Override
    public void setPosition(int x, int y) {
        baseWidget.setPosition(x, y);
    }
}
