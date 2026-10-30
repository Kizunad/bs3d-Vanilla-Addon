package dev.kizuna.bs3d.vanilla.client;

import dev.kizuna.backpack3d.client.BagAnimation;
import dev.kizuna.backpack3d.client.InteriorRenderer;
import dev.kizuna.backpack3d.client.LayerRangeSlider;
import dev.kizuna.backpack3d.client.ModelResources;
import dev.kizuna.backpack3d.client.VolumeModule;
import dev.kizuna.backpack3d.client.WornPreview;
import dev.kizuna.backpack3d.client.InteriorRenderer.Rect;
import dev.kizuna.backpack3d.item.ContainerSounds;
import dev.kizuna.backpack3d.protocol.OrthogonalRotation;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.DragTransfer;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.MenuSlot;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Container;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.BagDestination;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.PlayerSlot;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.OpenSceneRequest;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.theme.ThemeTokens;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.screen.slot.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/** 原版操作区两侧的玩家与背包场景；不重绘工作站槽位，也不接管配方按钮。 */
public final class StationPanel {
    private static final int GAP_PIXELS = 4;
    private static final int BUTTON_PIXELS = 16;
    private static final int DRAG_THRESHOLD_SQUARED_PIXELS = 9;
    private static final int TOOLTIP_OFFSET_PIXELS = 8;
    private static final float PLAYER_BASELINE_RATIO = 0.84f;
    private static final float PLAYER_HEIGHT_RATIO = 0.36f;
    private static final float PLAYER_WIDTH_RATIO = 0.45f;
    private static final float NANOS_PER_SECOND = 1_000_000_000f;
    private static final int INFO_RADIUS_PIXELS = 7;
    private static final String LOCAL_CURSOR_DETAIL = "请先把光标上的物品放进槽位";
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
    private boolean moved;
    private double pressX;
    private double pressY;
    private int mouseX;
    private int mouseY;
    private int orientation;
    private DragTransfer selection;
    private long lastFrame;
    private String sceneMenu = "";

    public StationPanel(HandledScreen<?> screen) {
        this.screen = screen;
        host = (StationHost) screen;
        addon = VanillaClient.instance();
        volume = addon.volume();
        player = new WornPreview(volume);
    }

    private StationLayout layout() {
        // 页面提供区域，人物和内景继续使用同一套渲染与拖放逻辑。
        return host.stationLayout();
    }

    public boolean interiorOpen() {
        // 玩家 E 页用内景替换合成展示，合成库存仍由原版持有。
        return !active.isEmpty();
    }

    public boolean enabled() {
        // 页签不承载玩家库存、高 GUI 缩放或展开配方书挤占空间时，保留完整原版交互，不叠压模型到槽位上。
        if (!host.stationActive()) {
            return false;
        }
        if (screen instanceof net.minecraft.client.gui.screen.recipebook.RecipeBookProvider recipes
                && recipes.getRecipeBookWidget().isOpen()) {
            return false;
        }
        return addon.ready(screen) && layout().visible();
    }

    public void draw(DrawContext context, int mx, int my) {
        // 页面状态就绪后绘制人物、内景与同一事务的反馈。
        if (!enabled()) {
            return;
        }
        // 新页面首次绘制时建立地面租约，不在渲染循环里重复扫描世界目录。
        String menu = addon.state().getMenu();
        if (!sceneMenu.equals(menu) && openScene()) {
            sceneMenu = menu;
        }
        mouseX = mx;
        mouseY = my;
        syncAnimations();
        var layout = layout();
        drawPlayer(context, layout.player(), mx, my);
        drawInterior(context, layout.interior(), mx, my);
        drawInfo(context, layout);
        player.drawDropTarget(context, MinecraftClient.getInstance().textRenderer, mx, my);
        drawFeedback(context, layout, mx, my);
    }

    private boolean openScene() {
        // 本地光标携物时服务器光标为空，场景请求会被指纹拒绝；放下物品后的下一帧再建立。
        if (localCursor()) {
            return false;
        }
        return addon.action(screen, request -> request.setOpenScene(OpenSceneRequest.getDefaultInstance()));
    }

    private void drawPlayer(DrawContext context, Rect body, int mx, int my) {
        // 抓取来源使模型高亮变为可落色，网络等待仍保留权威来源占位。
        int scale =
                (int)
                        Math.min(
                                body.width() * PLAYER_WIDTH_RATIO,
                                body.height() * PLAYER_HEIGHT_RATIO);
        String source = "";
        if (selection != null) {
            source = selection.getItem();
            if (source.isEmpty()) {
                source = "cursor";
            }
        } else if (!screen.getScreenHandler().getCursorStack().isEmpty()) {
            source = "cursor";
        }
        player.dropSource(source);
        player.draw(
                context,
                body.x() + body.width() / 2,
                body.y() + (int) (body.height() * PLAYER_BASELINE_RATIO),
                scale,
                mx,
                my,
                !help && !addon.busy() && body.contains(mx, my),
                animations);
    }

    private void drawFeedback(DrawContext context, StationLayout layout, int mx, int my) {
        // 拒绝原因贴在内景区域底边，原版槽位排在场景下方时也不会被背景盖住。
        var font = MinecraftClient.getInstance().textRenderer;
        if (!addon.detail().isBlank()) {
            context.drawTextWithShadow(
                    font,
                    font.trimToWidth(addon.detail(), layout.interior().width()),
                    layout.interior().x(),
                    layout.interior().bottom() - font.fontHeight,
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
                                            + "Shift 双向快捷搬运；拖到容器模型自动放入；ESC 关闭。"),
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
        // 原版槽位先于人物和内景解析，按下时只记录来源。
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
            return pressOther(x, y, button, layout);
        }
        if (x >= screen.width - BUTTON_PIXELS - StationLayout.MARGIN_PIXELS
                && y < StationLayout.HEADER_PIXELS) {
            help = !help;
            return true;
        }
        if (captureMenuSlot(x, y) || selection != null) {
            return true;
        }
        if (host.stationSlotAt(x, y) != null) {
            // 场景区域内的原版槽位（如 E 页合成格）同样按原版槽位处理，落点在松开时解析。
            return false;
        }
        if (layout.player().contains(x, y)) {
            return pressPlayer(x, y);
        }
        if (!layout.interior().contains(x, y)) {
            return false;
        }
        return pressInterior(x, y, layout.interior());
    }

    private boolean pressOther(double x, double y, int button, StationLayout layout) {
        // 原版槽位的右键分堆交给原版；人物区按住右键拖动只旋转模型。
        if (host.stationSlotAt(x, y) != null) {
            return false;
        }
        boolean onPlayer = layout.player().contains(x, y);
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && onPlayer) {
            rotatingPlayer = true;
        }
        return onPlayer || layout.interior().contains(x, y);
    }

    private boolean captureMenuSlot(double x, double y) {
        // 原版抓取只记录来源，Shift 才直接选择随身自动目标；页面专有槽交给原版。
        Slot pressed = host.stationSlotAt(x, y);
        if (pressed != null && pressed.hasStack()
                && screen.getScreenHandler().getCursorStack().isEmpty()) {
            var source = menuSlot(pressed);
            if (source == null) {
                return false;
            }
            if (selection == null) {
                orientation = 0;
                selection = DragTransfer.newBuilder()
                        .setSlot(source)
                        .build();
                if (Screen.hasShiftDown()) {
                    submit(selection.toBuilder().setBag(automatic(Volumes.CARRIED)));
                }
            }
            return true;
        }
        return false;
    }

    private boolean pressPlayer(double x, double y) {
        // 模型命中优先于身体部位，携物点击模型使用自动寻位。
        String bag = player.pick(x, y);
        if (!bag.isEmpty()) {
            if (screen.getScreenHandler().getCursorStack().isEmpty()) {
                open(bag);
            } else {
                var target = automatic(bag);
                submit(DragTransfer.newBuilder()
                        .setCursor(true /* cursor */)
                        .setBag(target));
            }
            return true;
        }
        int slot = player.slotAt(x, y);
        if (slot < 0) {
            rotatingPlayer = true;
        } else if (screen.getScreenHandler().getCursorStack().isEmpty()) {
            selectBody(slot);
        } else {
            dropOnBody(slot);
        }
        return true;
    }

    private void selectBody(int slot) {
        // 身体部位只记录来源，Shift 直接选择随身自动目标。
        orientation = 0;
        var source = bodySlot(slot);
        selection = DragTransfer.newBuilder()
                .setBody(source)
                .build();
        if (Screen.hasShiftDown()) {
            submit(selection.toBuilder().setBag(automatic(Volumes.CARRIED)));
        }
    }

    private void dropOnBody(int slot) {
        // 本地光标没有服务器对应物，穿戴改为点击对应的原版页面槽，盔甲槽规则仍由原版槽位判断。
        if (localCursor()) {
            Slot target = bodyPageSlot(slot);
            if (target != null) {
                host.stationClick(target);
            }
            return;
        }
        var target = bodySlot(slot);
        submit(DragTransfer.newBuilder()
                .setCursor(true /* cursor */)
                .setBodyDestination(target));
    }

    private boolean pressInterior(double x, double y, Rect area) {
        // 标题和切片控件先处理，只有可用内景才允许选择物品。
        if (active.isEmpty()) {
            return true;
        }
        if (y < area.y() + StationLayout.HEADER_PIXELS) {
            if (x >= area.right() - BUTTON_PIXELS) {
                closeInterior();
            } else if (x >= area.right() - BUTTON_PIXELS * 2) {
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
        selectInteriorItem(x, y);
        return true;
    }

    private void selectInteriorItem(double x, double y) {
        // 内景保留物品朝向，空白处仅旋转相机。
        String item = interiors.get(active).itemAt(x, y);
        if (item.isEmpty()) {
            rotatingInterior = true;
        } else {
            selection = DragTransfer.newBuilder()
                    .setItem(item)
                    .build();
            orientation = volume.state().getItemsList().stream()
                    .filter(value -> value.getId().equals(item))
                    .findFirst()
                    .orElseThrow()
                    .getOrientation();
            if (Screen.hasShiftDown()) {
                submit(selection.toBuilder().setPlayerInventory(true /* playerInventory */));
            }
        }
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
        // 关闭只改变展示和声音，即时转移已经完成。
        animations.get(active).close();
        volume.containerSound(active, ContainerSounds.Action.CLOSE);
        active = "";
    }

    private PlayerSlot bodySlot(int slot) {
        // 模型部位使用真实玩家槽号，指纹固定在抓取或选择落点时。
        var stack = MinecraftClient.getInstance().player.getInventory().getStack(slot);
        return PlayerSlot.newBuilder()
                .setSlot(slot)
                .setFingerprint(StackCodec.fingerprint(stack))
                .build();
    }

    private MenuSlot menuSlot(Slot pageSlot) {
        // 原版页面槽号与 PlayerInventory 槽号不同，协议明确区分；页面专有槽没有服务器槽，返回 null。
        Slot slot = host.stationMenuSlot(pageSlot);
        if (slot == null) {
            return null;
        }
        return MenuSlot.newBuilder()
                .setSlot(slot.id)
                .setFingerprint(StackCodec.fingerprint(slot.getStack()))
                .build();
    }

    private Slot findPageSlot(Predicate<Slot> menuSlot) {
        // 按服务器菜单槽反查页面上的点击入口，普通页面两者是同一个槽。
        for (Slot slot : screen.getScreenHandler().slots) {
            Slot target = host.stationMenuSlot(slot);
            if (target != null && menuSlot.test(target)) {
                return slot;
            }
        }
        return null;
    }

    private Slot bodyPageSlot(int inventorySlot) {
        // 创造页的盔甲与副手槽虽不显示，仍是原版创造写入的入口。
        var inventory = MinecraftClient.getInstance().player.getInventory();
        return findPageSlot(target -> target.inventory == inventory && target.getIndex() == inventorySlot);
    }

    private boolean localCursor() {
        // 创造页光标只在客户端，携物时提交事务会被指纹拒绝，随后的全量同步还会清掉本地光标。
        if (host.stationSharedCursor()) {
            return false;
        }
        return !screen.getScreenHandler().getCursorStack().isEmpty();
    }

    private BagDestination automatic(String container) {
        // 模型落点不提交切片和格位，服务端保留朝向搜索完整容量。
        return BagDestination.newBuilder()
                .setContainer(container)
                .setOrientation(orientation)
                .setAutoPlace(true /* autoPlace */)
                .build();
    }

    private BagDestination manual(double x, double y) {
        // 只有内景具体格位使用手动放置，合盖模型由 automatic 处理。
        if (active.isEmpty() || minimized || !viewport().contains(x, y)) {
            return null;
        }
        var interior = interiors.get(active);
        if (interior == null) {
            return null;
        }
        var cell = interior.cellAt(x, y);
        if (cell == null) {
            return null;
        }
        return BagDestination.newBuilder()
                .setContainer(active)
                .setX(cell.getX())
                .setY(cell.getY())
                .setZ(cell.getZ())
                .setOrientation(orientation)
                .build();
    }

    private void store(double x, double y) {
        // 原版光标来源同样只提交一次完整转移意图。
        var target = manual(x, y);
        if (target != null) {
            submit(DragTransfer.newBuilder()
                    .setCursor(true /* cursor */)
                    .setBag(target));
        }
    }

    private void submit(DragTransfer.Builder transfer) {
        // 请求发出前不碰原版光标，回执无需再补一次客户端点击；本地光标携物时只提示，不提交。
        if (localCursor()) {
            addon.reject(LOCAL_CURSOR_DETAIL);
            resetCapture();
            return;
        }
        var request = transfer.build();
        addon.action(screen, action -> action.setTransfer(request));
        resetCapture();
    }

    public boolean drag(double x, double y, int button, double dx, double dy) {
        // 只有抓取的物品进入拖放，其余输入维持场景旋转。
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
        return selection != null || addon.busy() || sliding || rotatingPlayer || rotatingInterior;
    }

    public boolean release(double x, double y, int button) {
        // 松开时才确定完整来源与落点，网络回执不再触发第二次鼠标操作。
        if (!enabled()) {
            resetCapture();
            return false;
        }
        boolean camera = sliding || rotatingPlayer || rotatingInterior;
        sliding = false;
        rotatingPlayer = false;
        rotatingInterior = false;
        if (camera || addon.busy()) {
            return true;
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        if (clicksSource(x, y)) {
            clickSelection();
            return true;
        }
        if (selection == null && localCursor()) {
            // 本地光标的放置交给原版；场景内的松开由场景消费，不被原版当作窗口外丢出。
            return inScene(x, y);
        }
        DragTransfer source = releaseSource();
        if (source == null) {
            return false;
        }
        if (!moved && source.hasItem() && source.getItem().equals(itemAt(x, y))) {
            return true;
        }
        var target = destination(source.toBuilder(), x, y);
        if (target != null) {
            submit(target);
            return true;
        }
        return selection != null;
    }

    private DragTransfer releaseSource() {
        // 没有按下记录的来源时，原版光标上的物品就是来源。
        if (selection != null) {
            return selection;
        }
        if (screen.getScreenHandler().getCursorStack().isEmpty()) {
            return null;
        }
        return DragTransfer.newBuilder()
                .setCursor(true /* cursor */)
                .build();
    }

    private boolean clicksSource(double x, double y) {
        // 未拖动，或落在删除格等页面专有槽上，都与原版一样只把来源槽点起到光标上。
        if (selection == null || !selection.hasSlot()) {
            return false;
        }
        if (!moved) {
            return true;
        }
        Slot slot = host.stationSlotAt(x, y);
        if (slot == null) {
            return false;
        }
        return host.stationMenuSlot(slot) == null;
    }

    private void clickSelection() {
        // 未拖动的原版槽按一次原版点击处理，页面自身的点击规则随之生效。
        int source = selection.getSlot().getSlot();
        Slot clicked = findPageSlot(target -> target.id == source);
        if (clicked != null) {
            host.stationClick(clicked);
        }
        resetCapture();
    }

    private boolean inScene(double x, double y) {
        var layout = layout();
        if (layout.player().contains(x, y)) {
            return true;
        }
        return layout.interior().contains(x, y);
    }

    private DragTransfer.Builder destination(DragTransfer.Builder source, double x, double y) {
        // 原版槽、容器模型与具体内景格位按显示层次选择唯一落点；页面专有槽不接收事务。
        Slot slot = host.stationSlotAt(x, y);
        if (slot != null) {
            var target = menuSlot(slot);
            if (target == null) {
                return null;
            }
            return source.setDestination(target);
        }
        if (layout().player().contains(x, y)) {
            String bag = player.pick(x, y);
            if (!bag.isEmpty()) {
                return source.setBag(automatic(bag));
            }
            int body = player.slotAt(x, y);
            if (body >= 0) {
                return source.setBodyDestination(bodySlot(body));
            }
        }
        var target = manual(x, y);
        if (target != null) {
            return source.setBag(target);
        }
        return null;
    }

    private String itemAt(double x, double y) {
        // 仅查询当前已展开内景，不能沿用被关闭窗口的旧命中。
        if (active.isEmpty() || minimized || !viewport().contains(x, y)) {
            return "";
        }
        var interior = interiors.get(active);
        if (interior == null) {
            return "";
        }
        return interior.itemAt(x, y);
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
        // 旋转键沿用 BS3D 配置，只有选中物品时消费输入。
        if (!enabled()) {
            return false;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE
                || MinecraftClient.getInstance().options.inventoryKey.matchesKey(key, scan)) {
            resetCapture();
            return false;
        }
        // 只有光标携物且鼠标位于内景时消费 R/T，不能抢走铁砧重命名框的文字输入。
        if (!active.isEmpty()
                && viewport().contains(mouseX, mouseY)
                && (selection != null || !screen.getScreenHandler().getCursorStack().isEmpty())) {
            var pressedKey = InputUtil.fromKeyCode(key, scan);
            boolean yaw = volume.rotateYaw().equals(pressedKey);
            boolean pitch = volume.rotatePitch().equals(pressedKey);
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
        // 页面关闭立即释放地面访问，不让已消失的模型继续占用其他玩家的租约。
        volume.closeGround("");
        resetCapture();
    }

    private void resetCapture() {
        // 一并清掉旋转、滑块和本地选择，窗口消失后不能沿用旧命中。
        selection = null;
        rotatingPlayer = false;
        rotatingInterior = false;
        sliding = false;
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
