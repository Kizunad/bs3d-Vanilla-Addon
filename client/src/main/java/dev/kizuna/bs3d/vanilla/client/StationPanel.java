package dev.kizuna.bs3d.vanilla.client;

import dev.kizuna.backpack3d.client.*;
import dev.kizuna.backpack3d.client.InteriorRenderer.Rect;
import dev.kizuna.backpack3d.item.ContainerSounds;
import dev.kizuna.backpack3d.protocol.OrthogonalRotation;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Container;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.BagDestination;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.PlayerSlot;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.theme.ThemeTokens;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;

/** 原版操作区两侧的玩家与背包场景；不重绘工作站槽位，也不接管配方按钮。 */
public final class StationPanel {
    private static final int GAP_PIXELS = 4;
    private static final int BUTTON_PIXELS = 16;
    private static final int PROGRESS_HEIGHT_PIXELS = 3;
    private static final int DRAG_THRESHOLD_SQUARED_PIXELS = 9;
    private static final int TOOLTIP_OFFSET_PIXELS = 8;
    private static final float PLAYER_BASELINE_RATIO = 0.84f;
    private static final float PLAYER_HEIGHT_RATIO = 0.36f;
    private static final float PLAYER_WIDTH_RATIO = 0.45f;
    private static final float NANOS_PER_SECOND = 1_000_000_000f;
    private static final int INFO_RADIUS_PIXELS = 7;
    private final HandledScreen<?> screen;
    private final StationHost host;
    private final VanillaClient addon;
    private final VolumeModule volume;
    private final WornPreview player;
    private final Map<String, BagAnimation> animations = new HashMap<>();
    private final Map<String, InteriorRenderer> interiors = new HashMap<>();
    private final Map<String, LayerRangeSlider> ranges = new HashMap<>();
    private String active = "";
    private boolean minimized;
    private boolean help;
    private boolean rotatingPlayer;
    private boolean rotatingInterior;
    private boolean sliding;
    private boolean ownPickup;
    private boolean moved;
    private double pressX;
    private double pressY;
    private int mouseX;
    private int mouseY;
    private int orientation;
    private Runnable queuedDrop;
    private long lastFrame;

    public StationPanel(HandledScreen<?> screen) {
        this.screen = screen;
        host = (StationHost) screen;
        addon = VanillaClient.instance();
        volume = addon.volume();
        player = new WornPreview(volume);
    }

    private StationLayout layout() {
        return StationLayout.beside(
                screen.width, screen.height, host.stationLeft(), host.stationWidth());
    }

    public boolean enabled() {
        // 高 GUI 缩放或展开配方书挤占空间时，保留完整原版交互，不叠压模型到槽位上。
        if (screen instanceof net.minecraft.client.gui.screen.recipebook.RecipeBookProvider recipes
                && recipes.getRecipeBookWidget().isOpen()) {
            return false;
        }
        return addon.ready(screen) && layout().visible();
    }

    public void draw(DrawContext context, int mx, int my) {
        if (!enabled()) {
            return;
        }
        mouseX = mx;
        mouseY = my;
        syncAnimations();
        var layout = layout();
        Rect body = layout.player();
        int scale =
                (int)
                        Math.min(
                                body.width() * PLAYER_WIDTH_RATIO,
                                body.height() * PLAYER_HEIGHT_RATIO);
        player.draw(
                context,
                body.x() + body.width() / 2,
                body.y() + (int) (body.height() * PLAYER_BASELINE_RATIO),
                scale,
                mx,
                my,
                !help && !addon.busy() && body.contains(mx, my),
                animations);
        drawInterior(context, layout.interior(), mx, my);
        drawInfo(context, layout);
        var font = MinecraftClient.getInstance().textRenderer;
        if (!addon.detail().isBlank()) {
            context.drawTextWithShadow(
                    font,
                    font.trimToWidth(addon.detail(), layout.interior().width()),
                    layout.interior().x(),
                    screen.height - StationLayout.HEADER_PIXELS,
                    color(ThemeTokens.ACCENT));
        }
        // 模型和内景共用原版物品 tooltip，简介由 BS3D 已注册的回调追加。
        if (!addon.busy() && screen.getScreenHandler().getCursorStack().isEmpty() && !help) {
            ItemStack hovered = hovered(mx, my);
            if (!hovered.isEmpty()) {
                context.drawItemTooltip(font, hovered, mx, my);
            }
        }
    }

    private void syncAnimations() {
        // 物品被原版移走后及时清理内景，不能继续点击上一个快照留下的投影。
        long now = System.nanoTime();
        float seconds = lastFrame == 0 ? 0 : (now - lastFrame) / NANOS_PER_SECOND;
        lastFrame = now;
        animations.keySet().removeIf(id -> findContainer(id) == null);
        for (Container container : volume.state().getContainersList()) {
            var animation =
                    animations.computeIfAbsent(container.getId(), ignored -> new BagAnimation());
            animation.configure(ModelResources.find(container.getModel()));
            animation.advance(seconds);
        }
        interiors.keySet().retainAll(animations.keySet());
        ranges.keySet().retainAll(animations.keySet());
        if (!active.isEmpty() && findContainer(active) == null) {
            resetCapture();
            active = "";
        }
    }

    private Container findContainer(String id) {
        // 未打开内景和来源已移走都属于正常状态，不调用要求实例必定存在的查询入口。
        return volume.state().getContainersList().stream()
                .filter(container -> container.getId().equals(id))
                .findFirst()
                .orElse(null);
    }

    private void drawInterior(DrawContext context, Rect area, int mx, int my) {
        var container = findContainer(active);
        if (container == null) {
            return;
        }
        var font = MinecraftClient.getInstance().textRenderer;
        int headerBottom = area.y() + StationLayout.HEADER_PIXELS;
        context.fill(
                area.x(), area.y(), area.right(), headerBottom, color(ThemeTokens.HUD_BACKGROUND));
        context.drawText(
                font,
                font.trimToWidth(container.getTitle(), area.width() - BUTTON_PIXELS * 2),
                area.x() + GAP_PIXELS,
                area.y() + GAP_PIXELS,
                color(ThemeTokens.TEXT),
                false);
        context.drawText(
                font,
                minimized ? "+" : "−",
                area.right() - BUTTON_PIXELS * 2,
                area.y() + GAP_PIXELS,
                color(ThemeTokens.TEXT),
                false);
        context.drawText(
                font,
                "×",
                area.right() - BUTTON_PIXELS,
                area.y() + GAP_PIXELS,
                color(ThemeTokens.TEXT),
                false);
        if (minimized || !animations.get(active).ready()) {
            return;
        }
        var interior = interiors.computeIfAbsent(active, ignored -> new InteriorRenderer());
        var range =
                ranges.computeIfAbsent(active, ignored -> new LayerRangeSlider(interior.layers()));
        interior.layers().resize(container.getSize().getHeight());
        range.layout(new Rect(area.x(), headerBottom, area.width(), LayerRangeSlider.HEIGHT));
        range.draw(context, font);
        Rect viewport = viewport();
        interior.draw(context, volume, container, viewport, mx, my, "", null, orientation);
        var state = addon.state();
        if (state.getDurationTicks() > 0 && addon.busy()) {
            int filled =
                    (int)
                            ((long) area.width()
                                    * state.getElapsedTicks()
                                    / state.getDurationTicks());
            context.fill(
                    area.x(),
                    viewport.bottom(),
                    area.right(),
                    viewport.bottom() + PROGRESS_HEIGHT_PIXELS,
                    color(ThemeTokens.PROGRESS_TRACK));
            context.fill(
                    area.x(),
                    viewport.bottom(),
                    area.x() + filled,
                    viewport.bottom() + PROGRESS_HEIGHT_PIXELS,
                    color(ThemeTokens.PROGRESS_FILL));
        }
    }

    private void drawInfo(DrawContext context, StationLayout layout) {
        // 圆形信息按钮只在需要时展开操作说明，不增加常驻底栏。
        var font = MinecraftClient.getInstance().textRenderer;
        int centerX = screen.width - StationLayout.MARGIN_PIXELS - INFO_RADIUS_PIXELS;
        int centerY = INFO_RADIUS_PIXELS + GAP_PIXELS;
        for (int dy = -INFO_RADIUS_PIXELS; dy <= INFO_RADIUS_PIXELS; dy++) {
            int span = (int) Math.sqrt(INFO_RADIUS_PIXELS * INFO_RADIUS_PIXELS - dy * dy);
            context.fill(
                    centerX - span,
                    centerY + dy,
                    centerX + span + 1,
                    centerY + dy + 1,
                    color(ThemeTokens.HUD_BACKGROUND));
        }
        context.drawText(
                font,
                "i",
                centerX - font.getWidth("i") / 2,
                centerY - font.fontHeight / 2,
                color(ThemeTokens.TEXT),
                false);
        if (help) {
            Rect area = layout.player();
            var lines =
                    font.wrapLines(
                            Text.literal(
                                    "点击身上背包打开内景。\n"
                                            + "双手、盔甲和内景物品可拖到原版槽位；反向拖动可存回。\n"
                                            + "拖动空白处旋转，滚轮缩放内景。R/T 旋转待存物品，范围条选择可见层。\n"
                                            + "背包取放需要时间；ESC 取消并关闭。"),
                            area.width());
            int bottom = area.y() + lines.size() * (font.fontHeight + GAP_PIXELS) + GAP_PIXELS * 2;
            context.fill(
                    area.x(), area.y(), area.right(), bottom, color(ThemeTokens.HUD_BACKGROUND));
            int y = area.y() + GAP_PIXELS;
            for (var line : lines) {
                context.drawText(
                        font, line, area.x() + GAP_PIXELS, y, color(ThemeTokens.TEXT), false);
                y += font.fontHeight + GAP_PIXELS;
            }
        }
    }

    public boolean press(double x, double y, int button) {
        if (!enabled()) {
            return false;
        }
        if (addon.busy()) {
            return true;
        }
        pressX = x;
        pressY = y;
        moved = false;
        var layout = layout();
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return layout.player().contains(x, y) || layout.interior().contains(x, y);
        }
        if (x >= screen.width - BUTTON_PIXELS - StationLayout.MARGIN_PIXELS
                && y < StationLayout.HEADER_PIXELS) {
            help = !help;
            return true;
        }
        if (layout.player().contains(x, y)) {
            String bag = player.pick(x, y);
            if (!bag.isEmpty()) {
                open(bag);
                return true;
            }
            int slot = player.slotAt(x, y);
            if (slot >= 0) {
                boolean picking = screen.getScreenHandler().getCursorStack().isEmpty();
                if (clickPlayer(slot)) {
                    ownPickup = picking;
                }
            } else {
                rotatingPlayer = true;
            }
            return true;
        }
        if (!layout.interior().contains(x, y)) {
            return false;
        }
        if (active.isEmpty()) {
            return true;
        }
        if (y < layout.interior().y() + StationLayout.HEADER_PIXELS) {
            if (x >= layout.interior().right() - BUTTON_PIXELS) {
                closeInterior();
            } else if (x >= layout.interior().right() - BUTTON_PIXELS * 2) {
                minimized = !minimized;
            }
            return true;
        }
        if (minimized || !animations.get(active).ready()) {
            return true;
        }
        if (!interiors.containsKey(active)) {
            // 恢复窗口后的下一帧才建立投影，不能使用不存在或已被清理的命中数据。
            return true;
        }
        var range = ranges.get(active);
        if (range != null && range.contains(x, y)) {
            sliding = true;
            range.press(x, y);
            interiors.get(active).invalidateHits();
            return true;
        }
        if (!screen.getScreenHandler().getCursorStack().isEmpty()) {
            store(x, y);
            return true;
        }
        String item = interiors.get(active).itemAt(x, y);
        if (item.isEmpty()) {
            rotatingInterior = true;
        } else {
            ownPickup = addon.action(screen, request -> request.setTakeItem(item));
        }
        return true;
    }

    public void open(String id) {
        // 切换内景只改变展示，不生成转移请求；空白点击不会自动移动任何物品。
        if (!active.isEmpty() && !active.equals(id)) {
            animations.get(active).close();
        }
        active = id;
        minimized = false;
        animations.computeIfAbsent(id, ignored -> new BagAnimation()).open();
        volume.containerSound(id, ContainerSounds.Action.OPEN);
    }

    private void closeInterior() {
        // 关闭只改变展示和声音，仍在计时的物品由服务器与页面生命周期处理。
        animations.get(active).close();
        volume.containerSound(active, ContainerSounds.Action.CLOSE);
        active = "";
    }

    private boolean clickPlayer(int slot) {
        var stack = MinecraftClient.getInstance().player.getInventory().getStack(slot);
        var target =
                PlayerSlot.newBuilder()
                        .setSlot(slot)
                        .setFingerprint(StackCodec.fingerprint(stack))
                        .build();
        return addon.action(screen, request -> request.setPlayerSlot(target));
    }

    private void store(double x, double y) {
        // 落点来自真实投影；是否放得下以及尺寸仍由服务器决定。
        if (active.isEmpty() || minimized || !viewport().contains(x, y)) {
            return;
        }
        var cell = interiors.get(active).cellAt(x, y);
        if (cell == null) {
            return;
        }
        var target =
                BagDestination.newBuilder()
                        .setContainer(active)
                        .setX(cell.getX())
                        .setY(cell.getY())
                        .setZ(cell.getZ())
                        .setOrientation(orientation)
                        .build();
        addon.action(screen, request -> request.setStoreCursor(target));
    }

    public boolean drag(double x, double y, int button, double dx, double dy) {
        if (!enabled()) {
            return false;
        }
        double distanceX = x - pressX;
        double distanceY = y - pressY;
        moved |= distanceX * distanceX + distanceY * distanceY >= DRAG_THRESHOLD_SQUARED_PIXELS;
        if (sliding) {
            ranges.get(active).drag(x);
            interiors.get(active).invalidateHits();
        } else if (rotatingPlayer) {
            player.rotate(dx, dy);
        } else if (rotatingInterior) {
            interiors.get(active).rotate(dx, dy);
        }
        return ownPickup || addon.busy() || sliding || rotatingPlayer || rotatingInterior;
    }

    public boolean release(double x, double y, int button) {
        if (!enabled()) {
            resetCapture();
            return false;
        }
        boolean camera = sliding || rotatingPlayer || rotatingInterior;
        sliding = false;
        rotatingPlayer = false;
        rotatingInterior = false;
        if (camera) {
            return true;
        }
        boolean fromModel = ownPickup;
        ownPickup = false;
        if (addon.busy() && !fromModel) {
            return true;
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !moved) {
            return fromModel || addon.busy();
        }
        Runnable drop = dropAt(x, y);
        if (fromModel && addon.busy()) {
            // 松开鼠标可能早于计时完成；仅成功回执到达后才执行同一页面的原版落点。
            queuedDrop = drop;
            return true;
        }
        if (drop != null && !screen.getScreenHandler().getCursorStack().isEmpty()) {
            drop.run();
            return true;
        }
        return fromModel;
    }

    private Runnable dropAt(double x, double y) {
        var slot = host.stationSlotAt(x, y);
        if (slot != null) {
            return () ->
                    MinecraftClient.getInstance()
                            .interactionManager
                            .clickSlot(
                                    screen.getScreenHandler().syncId,
                                    slot.id,
                                    0,
                                    SlotActionType.PICKUP,
                                    MinecraftClient.getInstance().player);
        }
        if (layout().player().contains(x, y)) {
            int bodySlot = player.slotAt(x, y);
            return bodySlot < 0 ? null : () -> clickPlayer(bodySlot);
        }
        if (!active.isEmpty() && !minimized && viewport().contains(x, y)) {
            return () -> store(x, y);
        }
        return null;
    }

    public void completed(boolean accepted) {
        // 服务器失败、换页或断线都清掉预定落点，不把旧拖放带到另一个容器。
        Runnable drop = queuedDrop;
        queuedDrop = null;
        if (!accepted) {
            ownPickup = false;
            return;
        }
        if (drop != null && MinecraftClient.getInstance().currentScreen == screen) {
            drop.run();
        }
    }

    public boolean scroll(double x, double y, double amount) {
        if (enabled() && !active.isEmpty() && !minimized && viewport().contains(x, y)) {
            var interior = interiors.get(active);
            if (interior != null) {
                interior.zoom(amount);
            }
            return true;
        }
        return false;
    }

    public boolean key(int key, int scan, int modifiers) {
        if (!enabled()) {
            return false;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE
                || MinecraftClient.getInstance().options.inventoryKey.matchesKey(key, scan)) {
            addon.cancel(screen);
            resetCapture();
            return false;
        }
        // 只有光标携物且鼠标位于内景时消费 R/T，不能抢走铁砧重命名框的文字输入。
        if (!active.isEmpty()
                && viewport().contains(mouseX, mouseY)
                && !screen.getScreenHandler().getCursorStack().isEmpty()) {
            boolean yaw = volume.rotateYaw().matchesKey(key, scan);
            boolean pitch = volume.rotatePitch().matchesKey(key, scan);
            if (yaw || pitch) {
                orientation =
                        OrthogonalRotation.turn(
                                orientation, pitch, (modifiers & GLFW.GLFW_MOD_SHIFT) != 0);
                return true;
            }
        }
        return addon.busy();
    }

    public void removed() {
        addon.cancel(screen);
        resetCapture();
    }

    private void resetCapture() {
        // 一并清掉旋转、滑块和排队落点，窗口消失后不能沿用旧命中。
        ownPickup = false;
        rotatingPlayer = false;
        rotatingInterior = false;
        sliding = false;
        queuedDrop = null;
    }

    private ItemStack hovered(double x, double y) {
        if (layout().player().contains(x, y)) {
            String bag = player.pick(x, y);
            if (!bag.isEmpty()) {
                return volume.stack(bag);
            }
            int slot = player.slotAt(x, y);
            return slot < 0
                    ? ItemStack.EMPTY
                    : MinecraftClient.getInstance().player.getInventory().getStack(slot);
        }
        if (!active.isEmpty()
                && !minimized
                && interiors.containsKey(active)
                && viewport().contains(x, y)) {
            return volume.stack(interiors.get(active).itemAt(x, y));
        }
        return ItemStack.EMPTY;
    }

    private Rect viewport() {
        Rect area = layout().interior();
        int top = area.y() + StationLayout.HEADER_PIXELS + LayerRangeSlider.HEIGHT;
        return new Rect(
                area.x(),
                top,
                area.width(),
                Math.max(1, area.bottom() - top - TOOLTIP_OFFSET_PIXELS));
    }

    private static int color(String token) {
        return InventoryUiClient.runtime().color(token);
    }
}
