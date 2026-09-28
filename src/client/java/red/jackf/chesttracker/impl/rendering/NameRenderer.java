package red.jackf.chesttracker.impl.rendering;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import red.jackf.chesttracker.api.memory.Memory;
import red.jackf.chesttracker.api.memory.MemoryKey;
import red.jackf.chesttracker.api.providers.ProviderUtils;
import red.jackf.chesttracker.impl.config.ChestTrackerConfig;
import red.jackf.chesttracker.impl.memory.MemoryBankAccessImpl;
import red.jackf.chesttracker.impl.memory.MemoryBankImpl;
import red.jackf.whereisit.client.api.RenderUtils;
import red.jackf.whereisit.config.WhereIsItConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH;

public class NameRenderer {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final int FULL_BRIGHT = 0x00F000F0;
    private static final List<ScheduledLabel> scheduledLabels = new ArrayList<>();
    private static final StagedVertexBuffer STAGED_BUFFER = new StagedVertexBuffer(() -> "ChestTracker", 262144);

    private record ScheduledLabel(Vec3 position, Component text, boolean focused) {}

    public static void renderWorld(Camera camera, RenderPass renderPass) {
        DrawCollector drawCollector = new DrawCollector();
        renderLabels(camera, drawCollector);
        drawCollector.draw(renderPass);
    }

    /**
     * Releases the staging buffers. Must be called <b>outside</b> of a render pass: 26.3 creates a
     * fence here, and the command encoder rejects that while a pass is still open.
     */
    public static void endFrame() {
        STAGED_BUFFER.endFrame();
    }

    /**
     * In 26.3 {@link PoseStack#mulPose} no longer accepts a {@link Quaternionf} directly, only a matrix.
     */
    private static void mulPose(PoseStack pose, Quaternionf rotation) {
        pose.mulPose(new Matrix4f().rotation(rotation));
    }

    public static void scheduleLabels() {
        scheduledLabels.clear();

        if (ChestTrackerConfig.INSTANCE.instance().debug.disableContainerNames) return;

        MemoryBankAccessImpl.INSTANCE.getLoadedInternal().ifPresent(bank -> {
            if (bank.getMetadata().getCompatibilitySettings().nameRenderMode == NameRenderMode.DISABLED)
                return;
            bank.getKey(ProviderUtils.getPlayersCurrentKey()).ifPresent(key -> {
                HitResult hitResult = MC.hitResult;
                collectLabels(key, bank, hitResult);
            });
        });
    }

    private static void collectLabels(MemoryKey key, MemoryBankImpl bank, @Nullable HitResult hitResult) {
        @Nullable Memory focused = null;

        if (hitResult instanceof BlockHitResult blockHit && blockHit.getType() != HitResult.Type.MISS) {
            focused = key.get(blockHit.getBlockPos())
                    .filter(Memory::hasCustomName)
                    .orElse(null);
        }

        if (bank.getMetadata().getCompatibilitySettings().nameRenderMode == NameRenderMode.FULL) {
            Map<BlockPos, Memory> named = key.getNamedMemories();
            int maxRangeSq = ChestTrackerConfig.INSTANCE.instance().rendering.nameRange *
                    ChestTrackerConfig.INSTANCE.instance().rendering.nameRange;
            Set<BlockPos> alreadyRendering = RenderUtils.getCurrentlyRenderedWithNames();

            for (var entry : named.entrySet()) {
                if (entry.getValue() == focused) continue;
                if (alreadyRendering.contains(entry.getKey())) continue;
                if (entry.getKey().distToCenterSqr(MC.player.position()) < maxRangeSq) {
                    Component name = entry.getValue().renderName();
                    if (name != null && !isBlockedLabel(name)) {
                        Vec3 pos = entry.getValue().getCenterPosition().add(0, 1, 0);
                        scheduledLabels.add(new ScheduledLabel(pos, name, false));
                    }
                }
            }
        }

        if (focused != null) {
            Component name = focused.renderName();
            if (name != null && !isBlockedLabel(name)) {
                Vec3 pos = focused.getCenterPosition().add(0, 1, 0);
                scheduledLabels.add(new ScheduledLabel(pos, name, true));
            }
        }
    }

    private static boolean isBlockedLabel(Component name) {
        String normalizedName = normalizeLabelText(name.getString());
        if (normalizedName.isEmpty()) return false;

        List<String> blocked = WhereIsItConfig.INSTANCE.instance().getClient().blockedContainerLabelNames;
        if (blocked == null || blocked.isEmpty()) return false;

        for (String blockedName : blocked) {
            if (normalizedName.equals(normalizeLabelText(blockedName))) {
                return true;
            }
        }

        return false;
    }

    private static String normalizeLabelText(@Nullable String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean hasScheduledLabels() {
        return !scheduledLabels.isEmpty();
    }

    public static void clearScheduledLabels() {
        scheduledLabels.clear();
    }

    public static void renderLabels(Camera camera, DrawCollector drawCollector) {
        if (scheduledLabels.isEmpty()) return;

        Vec3 camPos = camera.position();

        PoseStack pose = new PoseStack();
        mulPose(pose, Axis.XP.rotationDegrees(camera.xRot()));
        mulPose(pose, Axis.YP.rotationDegrees(camera.yRot() + 180f));

        scheduledLabels.stream()
                .sorted(Comparator.comparingDouble(label -> -camPos.distanceToSqr(label.position)))
                .forEach(label -> renderLabel(label, pose, camera, camPos, drawCollector));

        scheduledLabels.clear();
    }

    private static void renderLabel(ScheduledLabel label, PoseStack pose, Camera camera, Vec3 camPos, DrawCollector drawCollector) {
        pose.pushPose();

        // Offset from the camera
        final double xOffset = label.position.x - camPos.x;
        final double yOffset = label.position.y + WhereIsItConfig.INSTANCE.instance().getClient().Ypositiontext - camPos.y;
        final double zOffset = label.position.z - camPos.z;
        pose.translate(xOffset, yOffset, zOffset);

        // Additional rotation for billboard
        mulPose(pose, Axis.YP.rotationDegrees(-camera.yRot()));
        mulPose(pose, Axis.XP.rotationDegrees(camera.xRot()));

        // Scale
        float scale = 0.025f * WhereIsItConfig.INSTANCE.instance().getClient().containerNameLabelScale;
        pose.scale(-scale, -scale, scale);

        Matrix4f matrix = pose.last().pose();
        Font font = MC.font;
        int width = font.width(label.text);
        float x = -width / 2f;

        // Background
        int bgColour = ((int)(MC.options.getBackgroundOpacity(0.25F) * 255F)) << 24;
        if (bgColour != 0) {
            TextRenderable background = font.prepareBackground(x - 1, -1f, x + width, 10f, bgColour);
            VertexConsumer bgBuffer = drawCollector.getBuffer(background.renderType(SEE_THROUGH));
            background.render(matrix, bgBuffer, FULL_BRIGHT, false);
        }

        // Text
        Font.PreparedText preparedText = Minecraft.getInstance().font.prepareText(
                label.text.getVisualOrderText(),
                x,
                0,
                0xFFFFFFFF,
                false,
                false,
                0
        );

        preparedText.visit(new Font.GlyphVisitor() {
            @Override
            public void acceptRenderable(TextRenderable renderable) {
                VertexConsumer buffer = drawCollector.getBuffer(renderable.renderType(SEE_THROUGH));
                renderable.render(matrix, buffer, FULL_BRIGHT, false);
            }
        });

        pose.popPose();
    }
    private static final class DrawCollector {
        private final List<StagedVertexBuffer.Draw> draws = new ArrayList<>();
        private final List<PreparedRenderType> preparedRenderTypes = new ArrayList<>();
        @Nullable private RenderType lastRenderType;
        @Nullable private StagedVertexBuffer.Draw lastDraw;

        private VertexConsumer getBuffer(RenderType renderType) {
            if (lastDraw == null || lastRenderType != renderType || !renderType.canConsolidateConsecutiveGeometry()) {
                lastDraw = createDraw(renderType);
                lastRenderType = renderType;
            }

            return STAGED_BUFFER.getVertexBuilder(lastDraw);
        }

        private StagedVertexBuffer.Draw createDraw(RenderType renderType) {
            PreparedRenderType preparedRenderType = renderType.prepare();
            VertexSorting quadSorting = renderType.sortOnUpload() ? RenderSystem.getProjectionType().vertexSorting() : null;
            StagedVertexBuffer.Draw draw = STAGED_BUFFER.appendDraw(renderType.format(), renderType.primitiveTopology(), quadSorting);
            draws.add(draw);
            preparedRenderTypes.add(preparedRenderType);
            return draw;
        }

        private void draw(RenderPass renderPass) {
            STAGED_BUFFER.upload();

            for (int i = 0; i < draws.size(); i++) {
                StagedVertexBuffer.ExecuteInfo executeInfo = STAGED_BUFFER.getExecuteInfo(draws.get(i));
                if (executeInfo != null) {
                    preparedRenderTypes.get(i).drawFromBuffer(executeInfo, renderPass);
                }
            }
        }
    }
}
