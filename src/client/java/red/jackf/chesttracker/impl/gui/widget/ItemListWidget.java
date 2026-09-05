package red.jackf.chesttracker.impl.gui.widget;

import com.mojang.datafixers.util.Pair;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;
import red.jackf.chesttracker.impl.config.ChestTrackerConfig;
import red.jackf.chesttracker.impl.gui.GuiConstants;
import red.jackf.chesttracker.impl.util.GuiUtil;
import red.jackf.chesttracker.impl.util.Strings;
import red.jackf.whereisit.api.SearchRequest;
import red.jackf.whereisit.client.api.events.SearchInvoker;
import red.jackf.whereisit.client.api.events.SearchRequestPopulator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class ItemListWidget extends AbstractWidget {
    private static final Identifier BACKGROUND_SPRITE = GuiUtil.sprite("widgets/slot_background");
    private static final ItemStack DUMMY_ITEM_FOR_COUNT = new ItemStack(Items.EMERALD);

    private final int gridWidth;
    private final int gridHeight;
    private List<ItemStack> items = Collections.emptyList();
    private int offset = 0;
    private boolean hideTooltip;
    private boolean hasTooltip = false;
    private List<ClientTooltipComponent> pendingTooltip = Collections.emptyList();
    private int pendingTooltipX = 0;
    private int pendingTooltipY = 0;

    public ItemListWidget(int x, int y, int gridWidth, int gridHeight) {
        super(x, y, gridWidth * GuiConstants.GRID_SLOT_SIZE, gridHeight * GuiConstants.GRID_SLOT_SIZE, Component.empty());
        this.gridWidth = gridWidth;
        this.gridHeight = gridHeight;
    }

    public void setItems(List<ItemStack> items) {
        this.items = items;
        int rows = getRows();
        this.offset = Mth.clamp(this.offset, 0, Math.max((rows - gridHeight) * gridWidth, 0));
    }

    private List<ItemStack> getOffsetItems() {
        if (this.items.isEmpty()) return Collections.emptyList();
        int min = Mth.clamp(this.offset, 0, this.items.size() - 1);
        int max = Mth.clamp(this.offset + gridWidth * gridHeight, 0, this.items.size());
        return this.items.subList(min, max);
    }

    public int getRows() {
        return Mth.positiveCeilDiv(this.items.size(), this.gridWidth);
    }

    public void onScroll(float progress) {
        int rows = getRows();
        if (rows <= gridHeight) return;
        int range = rows - gridHeight;
        int rowOffset = (int) (progress * (range + 0.5f));
        this.offset = rowOffset * gridWidth;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean isDoubleClick) {
        var items = getOffsetItems();
        int x = (int) ((event.x() - getX()) / GuiConstants.GRID_SLOT_SIZE);
        int y = (int) ((event.y() - getY()) / GuiConstants.GRID_SLOT_SIZE);
        int index = (y * gridWidth) + x;
        if (index >= items.size()) return;
        var request = new SearchRequest();
        SearchRequestPopulator.addItemStack(request, items.get(index), Minecraft.getInstance().hasShiftDown() ? SearchRequestPopulator.Context.FAVOURITE : SearchRequestPopulator.Context.INVENTORY_PRECISE);
        SearchInvoker.doSearch(request);
    }

    @Override
    protected void extractWidgetRenderState(@NotNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        this.hasTooltip = false;
        this.pendingTooltip = new ArrayList<>();
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BACKGROUND_SPRITE, getWidth(), getHeight(),
                0, 0, getX(), getY(), getWidth(), getHeight());
        this.renderItems(graphics);
        this.renderItemDecorations(graphics);
        this.renderAdditional(graphics, mouseX, mouseY);
    }

    private void renderItems(GuiGraphicsExtractor graphics) {
        var items = getOffsetItems();
        for (int i = 0; i < (gridWidth * gridHeight); i++) {
            var x = this.getX() + GuiConstants.GRID_SLOT_SIZE * (i % gridWidth);
            var y = this.getY() + GuiConstants.GRID_SLOT_SIZE * (i / gridWidth);
            if (i < items.size()) {
                var item = items.get(i);
                graphics.item(item, x + 1, y + 1);
            }
        }
    }

    private static Pair<Integer, Integer> getScales() {
        int currentScale = Minecraft.getInstance().getWindow().calculateScale(
                Minecraft.getInstance().options.guiScale().get(),
                Minecraft.getInstance().isEnforceUnicode()
        );
        int textScale = Math.max(1, currentScale + ChestTrackerConfig.INSTANCE.instance().gui.itemListTextScale);

        return Pair.of(textScale, currentScale);
    }

    private void renderItemDecorations(GuiGraphicsExtractor graphics) {
        var items = getOffsetItems();
        for (int i = 0; i < items.size(); i++) {
            ItemStack item = items.get(i);
            int offset = -GuiConstants.GRID_SLOT_SIZE + 2;

            // move to correct slot on screen
            graphics.pose().pushMatrix();
            int bottomRightX = this.getX() + GuiConstants.GRID_SLOT_SIZE * ((i % gridWidth) + 1);
            int bottomRightY = this.getY() + GuiConstants.GRID_SLOT_SIZE * ((i / gridWidth) + 1);
            graphics.pose().translate(bottomRightX - 1, bottomRightY - 1);

            // durability, scaled normally
            graphics.itemDecorations(Minecraft.getInstance().font, item, offset, offset, "");

            // scale down for text
            Pair<Integer, Integer> scales = getScales();
            int textScale = scales.getFirst();
            int guiScale = scales.getSecond();
            float scaleFactor = (float) textScale / guiScale;
            graphics.pose().scale(scaleFactor, scaleFactor);

            // render count text scaled down
            String text = Strings.magnitude(item.getCount(), 0);
            graphics.itemDecorations(Minecraft.getInstance().font, DUMMY_ITEM_FOR_COUNT, offset, offset, text);
            graphics.pose().popMatrix();
        }
    }

    private void renderAdditional(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        var items = getOffsetItems();
        if (!this.isHovered()) return;
        var x = (mouseX - getX()) / GuiConstants.GRID_SLOT_SIZE;
        var y = (mouseY - getY()) / GuiConstants.GRID_SLOT_SIZE;
        if (x < 0 || x >= gridWidth || y < 0 || y >= gridHeight) return;
        var index = (y * gridWidth) + x;
        if (index >= items.size()) return;
        var slotX = getX() + x * GuiConstants.GRID_SLOT_SIZE;
        var slotY = getY() + y * GuiConstants.GRID_SLOT_SIZE;
        graphics.fill(slotX + 1, slotY + 1, slotX + GuiConstants.GRID_SLOT_SIZE - 1, slotY + GuiConstants.GRID_SLOT_SIZE - 1, 0x80_FFFFFF);
        if (!this.hideTooltip) {
            var stack = items.get(index);
            List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(Minecraft.getInstance(), stack));
            if (stack.getCount() > 999) {
                lines.add(Component.literal(Strings.commaSeparated(stack.getCount()))
                        .withStyle(ChatFormatting.GREEN));
            }
            var image = stack.getTooltipImage();

            List<ClientTooltipComponent> components = lines.stream()
                    .map(Component::getVisualOrderText)
                    .filter(Objects::nonNull)
                    .map(ClientTooltipComponent::create)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            image.ifPresent(img -> {
                ClientTooltipComponent imgComponent = ClientTooltipComponent.create(img);
                if (imgComponent != null) {
                    components.add(0, imgComponent);
                }
            });

            if (!components.isEmpty()) {
                this.hasTooltip = true;
                this.pendingTooltip = components;
                this.pendingTooltipX = mouseX;
                this.pendingTooltipY = mouseY + 12;
            }
        }
    }

    public void renderTooltip(GuiGraphicsExtractor graphics) {
        if (!this.hasTooltip || this.pendingTooltip.isEmpty()) return;
        graphics.tooltip(
                Minecraft.getInstance().font,
                this.pendingTooltip,
                this.pendingTooltipX,
                this.pendingTooltipY,
                DefaultTooltipPositioner.INSTANCE,
                null
        );
    }

    @Override
    protected void updateWidgetNarration(@NotNull NarrationElementOutput narrationElementOutput) {

    }

    public void setHideTooltip(boolean shouldHideTooltip) {
        this.hideTooltip = shouldHideTooltip;
    }
}
