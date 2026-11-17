package dev.kizuna.bs3d.vanilla.smoke;

import dev.kizuna.backpack3d.client.InteriorRenderer;
import dev.kizuna.backpack3d.client.WornPreview;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.bs3d.vanilla.client.StationHost;
import dev.kizuna.bs3d.vanilla.client.StationLayout;
import com.google.protobuf.ByteString;
import dev.kizuna.bs3d.vanilla.client.StationPanel;
import dev.kizuna.bs3d.vanilla.client.VanillaClient;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;

/** 用真实按键状态和页面输入覆盖双向快捷搬运及合盖模型落点。 */
final class InstantMenuProbe {
    private static final int SETTLE_TICKS = 30;
    /** 原版的快速移动要服务端回一趟才落定，等这么多 tick 再看结果。 */
    private static final int QUICK_SETTLE_TICKS = 10;
    private static final int SLOT_CENTER_PIXELS = 8;
    private static final long INITIAL_REVISION = 4;
    private static final float MODEL_SEARCH_DEGREES = 15;
    /** 合成的左键输入不带修饰键；打开背包键由 SmokeKey 设置真实按键状态。 */
    private static final MouseButtonInfo LEFT = new MouseButtonInfo(0, 0);
    /** 原版页面从鼠标事件上读 Shift：按着真实 Shift 的同时，事件上也带这个修饰位。 */
    private static final MouseButtonInfo SHIFT_LEFT = new MouseButtonInfo(0, GLFW.GLFW_MOD_SHIFT);
    /** 快捷搬运一段在总步骤里占的位置：从第 4 步进来，走完跳到第 8 步收尾。 */
    private static final int QUICK_FIRST_STEP = 4;
    private static final int QUICK_LAST_STEP = 8;

    /** 快捷搬运一段的小步，按顺序走；条件不满足就留在原地等下一个 tick。 */
    private enum Quick {
        TAKE_DOWN, TAKE_CLICK, TAKE_RESULT, SHIFT_TO_CHEST, SHIFT_IN_CHEST, SHIFT_BACK, STORE_DOWN, STORE_CLICK,
        STORE_RESULT, REOPEN
    }

    private Quick quick = Quick.TAKE_DOWN;
    private int step;
    private int ticks;
    private ByteString moved;

    boolean tick(Minecraft client, AbstractContainerScreen<?> screen, String bag) {
        // 每个网络操作先等权威快照，再进行下一次鼠标输入。
        var addon = VanillaClient.instance();
        if (screen == null || !addon.ready(screen) || addon.busy()) {
            return false;
        }
        ticks++;
        try {
            boolean done;
            if (step < QUICK_FIRST_STEP) {
                done = dragSteps(client, screen, bag);
            } else if (step > QUICK_LAST_STEP) {
                done = groundSteps(client, screen);
            } else {
                done = quickMoveSteps(client, screen, bag);
            }
            if (done) {
                step++;
                ticks = 0;
            }
            return step > 13;
        } catch (RuntimeException failure) {
            // 失败时不把按着的键留在 Xvfb 里。
            SmokeKey.SHIFT.release();
            SmokeKey.OPEN_BACKPACK.release();
            SmokeRecorder.stop();
            throw failure;
        }
    }

    private boolean dragSteps(Minecraft client, AbstractContainerScreen<?> screen, String bag) {
        // 箱子页面从内景取出，随后关页重开，让下一次存入命中合盖模型。
        var addon = VanillaClient.instance();
        var panel = ((StationHost) screen).stationPanel();
        switch (step) {
            case 0 -> {
                if (ticks < SETTLE_TICKS) {
                    return false;
                }
                var source = itemPoint(screen, bag);
                drag(screen, source, slotPoint(screen, screen.getMenu().getSlot(0)));
            }
            case 1 -> {
                if (addon.volume().state().getRevision() != INITIAL_REVISION + 1) {
                    return false;
                }
                moved = StackCodec.fingerprint(screen.getMenu().getSlot(0).getItem(), Minecraft.getInstance().player.registryAccess());
                var host = (StationHost) screen;
                var layout = StationLayout.beside(
                        screen.width, screen.height, host.stationLeft(), host.stationWidth());
                var area = layout.interior();
                click(screen, new int[] {area.right() - SLOT_CENTER_PIXELS, area.y() + SLOT_CENTER_PIXELS});
            }
            case 2 -> {
                if (ticks < SETTLE_TICKS) {
                    return false;
                }
                var player = read(panel, "player", WornPreview.class);
                int[] target = modelPoint(screen, player, bag);
                if (target == null) {
                    player.rotate(MODEL_SEARCH_DEGREES, 0);
                    return false;
                }
                drag(screen, slotPoint(screen, screen.getMenu().getSlot(0)), target);
            }
            case 3 -> {
                if (addon.volume().state().getRevision() != INITIAL_REVISION + 2) {
                    return false;
                }
                panel.open(bag);
            }
            default -> throw new IllegalStateException("未知拖放验收步骤");
        }
        return true;
    }

    private boolean quickMoveSteps(Minecraft client, AbstractContainerScreen<?> screen, String bag) {
        // Xvfb 下按住真实的键，再通过 Screen 的鼠标入口点物品。整段走完返回 true。
        var volume = VanillaClient.instance().volume();
        long revision = volume.state().getRevision();
        Quick before = quick;
        if (ticks < SmokeRecorder.stepTicks()) {
            return false;
        }
        switch (quick) {
            case TAKE_DOWN -> {
                if (ticks < SETTLE_TICKS) {
                    return false;
                }
                SmokeRecorder.start("vanilla-quick-move", client.getWindow().getWidth(), client.getWindow().getHeight());
                SmokeKey.OPEN_BACKPACK.press();
                quick = Quick.TAKE_CLICK;
            }
            case TAKE_CLICK -> {
                // 按着打开背包键点内景物品：取回原版背包。
                if (!volume.openKeyDown()) {
                    return false;
                }
                click(screen, itemPoint(screen, bag));
                SmokeKey.OPEN_BACKPACK.release();
                quick = Quick.TAKE_RESULT;
            }
            case TAKE_RESULT -> {
                if (revision != INITIAL_REVISION + 3 || volume.openKeyDown()) {
                    return false;
                }
                require(client.gui.screen() == screen, "按着打开背包键取物以后原版页面不该关");
                SmokeKey.SHIFT.press();
                quick = Quick.SHIFT_TO_CHEST;
            }
            case SHIFT_TO_CHEST -> {
                // Shift+左键归原版：点玩家背包里的那件，原版把它挪进箱子，背包附属不接手。
                if (!client.hasShiftDown()) {
                    return false;
                }
                shiftClick(screen, slotPoint(screen, slotHolding(client, screen, true /* inPlayerInventory */)));
                quick = Quick.SHIFT_IN_CHEST;
            }
            case SHIFT_IN_CHEST -> {
                if (ticks < QUICK_SETTLE_TICKS) {
                    return false;
                }
                require(revision == INITIAL_REVISION + 3, "Shift+左键点原版槽位仍然触发了背包的快捷存入");
                // 再 Shift+左键点箱子里的它，原版挪回玩家背包。
                shiftClick(screen, slotPoint(screen, slotHolding(client, screen, false /* inPlayerInventory */)));
                quick = Quick.SHIFT_BACK;
            }
            case SHIFT_BACK -> {
                if (ticks < QUICK_SETTLE_TICKS) {
                    return false;
                }
                slotHolding(client, screen, true /* inPlayerInventory */);
                require(revision == INITIAL_REVISION + 3, "Shift+左键点箱子槽位不该动背包里的东西");
                SmokeKey.SHIFT.release();
                quick = Quick.STORE_DOWN;
            }
            case STORE_DOWN -> {
                if (client.hasShiftDown()) {
                    return false;
                }
                SmokeKey.OPEN_BACKPACK.press();
                quick = Quick.STORE_CLICK;
            }
            case STORE_CLICK -> {
                // 按着打开背包键点原版槽位：存进随身容器。键先不松，看页面会不会被关掉。
                if (!volume.openKeyDown()) {
                    return false;
                }
                click(screen, slotPoint(screen, slotHolding(client, screen, true /* inPlayerInventory */)));
                quick = Quick.STORE_RESULT;
            }
            case STORE_RESULT -> {
                if (revision != INITIAL_REVISION + 4) {
                    return false;
                }
                require(client.gui.screen() == screen, "按着打开背包键存物时原版页面被关掉了");
                SmokeKey.OPEN_BACKPACK.release();
                quick = Quick.REOPEN;
            }
            case REOPEN -> {
                if (volume.openKeyDown() || ticks < QUICK_SETTLE_TICKS) {
                    return false;
                }
                SmokeRecorder.stop();
                screen.onClose();
                client.gui.setScreen(new InventoryScreen(client.player));
                step = QUICK_LAST_STEP;
                return true;
            }
        }
        if (quick != before) {
            ticks = 0;
            SmokeRecorder.mark(quick.name());
        }
        return false;
    }

    private Slot slotHolding(Minecraft client, AbstractContainerScreen<?> screen, boolean inPlayerInventory) {
        // 找到装着那件搬来搬去的物品的页面槽：在玩家背包里，或者在箱子里。
        return screen.getMenu().slots.stream()
                .filter(slot -> (slot.container == client.player.getInventory()) == inPlayerInventory)
                .filter(slot -> StackCodec.fingerprint(slot.getItem(), client.player.registryAccess()).equals(moved))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        inPlayerInventory ? "那件物品不在玩家背包里" : "那件物品不在箱子里"));
    }

    private boolean groundSteps(Minecraft client, AbstractContainerScreen<?> screen) {
        // 原版页面自动发现距离内地面模型，存入和取出均通过真实 Screen 拖动入口。
        if (step < 11) {
            return storeGround(client, screen);
        }
        return takeGround(screen);
    }

    private boolean storeGround(Minecraft client, AbstractContainerScreen<?> screen) {
        // E 页的模型场景稳定后，切换到真实箱子与地面背包夹具。
        var addon = VanillaClient.instance();
        var panel = ((StationHost) screen).stationPanel();
        switch (step) {
            case 9 -> {
                if (ticks < SETTLE_TICKS || !panel.enabled()) {
                    return false;
                }
                Screenshot.grab(client.gameDirectory, "instant-e-page.png",
                        client.gameRenderer.mainRenderTarget(), 1, message -> {});
                client.getConnection().sendCommand("vanilla-fixture-ground");
            }
            case 10 -> {
                var ground = addon.volume().state().getContainersList().stream()
                        .filter(container -> container.getGrounded()).findFirst();
                if (ground.isEmpty() || ticks < SETTLE_TICKS) {
                    return false;
                }
                var player = read(panel, "player", WornPreview.class);
                int[] target = modelPoint(screen, player, ground.get().getId());
                if (target == null) {
                    player.rotate(MODEL_SEARCH_DEGREES, 0);
                    return false;
                }
                moved = StackCodec.fingerprint(screen.getMenu().getSlot(0).getItem(), Minecraft.getInstance().player.registryAccess());
                drag(screen, slotPoint(screen, screen.getMenu().getSlot(0)), target);
            }
            default -> throw new IllegalStateException("未知地面存入步骤");
        }
        return true;
    }

    private boolean takeGround(AbstractContainerScreen<?> screen) {
        // 地面存入回执到达后打开内景，取回相同指纹的物品。
        var addon = VanillaClient.instance();
        var panel = ((StationHost) screen).stationPanel();
        switch (step) {
            case 11 -> {
                if (addon.volume().state().getRevision() != INITIAL_REVISION + 6) {
                    return false;
                }
                String ground = addon.volume().state().getContainersList().stream()
                        .filter(container -> container.getGrounded()).findFirst().orElseThrow().getId();
                panel.open(ground);
            }
            case 12 -> {
                String ground = addon.volume().state().getContainersList().stream()
                        .filter(container -> container.getGrounded()).findFirst().orElseThrow().getId();
                // 开盖动画按帧推进，Xvfb 负载高时 30 tick 不一定够；等内景实际渲染后再取。
                if (ticks < SETTLE_TICKS || !read(panel, "interiors", Map.class).containsKey(ground)) {
                    return false;
                }
                drag(screen, itemPoint(screen, ground), slotPoint(screen, screen.getMenu().getSlot(0)));
            }
            case 13 -> {
                return addon.volume().state().getRevision() == INITIAL_REVISION + 7
                        && StackCodec.fingerprint(screen.getMenu().getSlot(0).getItem(), Minecraft.getInstance().player.registryAccess()).equals(moved);
            }
            default -> throw new IllegalStateException("未知地面验收步骤");
        }
        return true;
    }

    private int[] itemPoint(AbstractContainerScreen<?> screen, String bag) {
        // 投影来自实际渲染器，测试不伪造业务坐标或跳过输入分派。
        StationPanel panel = ((StationHost) screen).stationPanel();
        Map<?, ?> interiors = read(panel, "interiors", Map.class);
        var interior = (InteriorRenderer) interiors.get(bag);
        var volume = VanillaClient.instance().volume();
        for (int y = 0; y < screen.height; y++) {
            for (int x = 0; x < screen.width; x++) {
                String item = interior.itemAt(x, y);
                if (!item.isEmpty() && (moved == null || StackCodec.fingerprint(volume.stack(item), Minecraft.getInstance().player.registryAccess()).equals(moved))) {
                    return new int[] {x, y};
                }
            }
        }
        throw new IllegalStateException("没有可命中的内景物品");
    }

    private static int[] modelPoint(AbstractContainerScreen<?> screen, WornPreview player, String bag) {
        // 只选择实际模型表面，不能把装备部位矩形当成容器模型。
        for (int y = 0; y < screen.height; y++) {
            for (int x = 0; x < screen.width; x++) {
                if (player.hit(bag, x, y)) {
                    return new int[] {x, y};
                }
            }
        }
        return null;
    }

    private static int[] slotPoint(AbstractContainerScreen<?> screen, Slot slot) {
        // 原版槽位坐标相对于菜单原点。
        var host = (StationHost) screen;
        return new int[] {host.stationLeft() + slot.x + SLOT_CENTER_PIXELS,
                host.stationTop() + slot.y + SLOT_CENTER_PIXELS};
    }

    private static void shiftClick(AbstractContainerScreen<?> screen, int[] point) {
        // 带 Shift 修饰位的一次完整点击。
        var event = new MouseButtonEvent(point[0], point[1], SHIFT_LEFT);
        screen.mouseClicked(event, false /* doubleClick */);
        screen.mouseReleased(event);
    }

    private static void require(boolean condition, String detail) {
        if (!condition) {
            throw new IllegalStateException(detail);
        }
    }

    private static void click(AbstractContainerScreen<?> screen, int[] point) {
        // 完整按下和松开，防止保留上一次拖动捕获。
        var event = new MouseButtonEvent(point[0], point[1], LEFT);
        screen.mouseClicked(event, false /* doubleClick */);
        screen.mouseReleased(event);
    }

    private static void drag(AbstractContainerScreen<?> screen, int[] source, int[] target) {
        // 落点确定后只提交一次 MOVE，客户端不补发原版槽位点击。
        screen.mouseClicked(new MouseButtonEvent(source[0], source[1], LEFT), false /* doubleClick */);
        var end = new MouseButtonEvent(target[0], target[1], LEFT);
        screen.mouseDragged(end, target[0] - source[0], target[1] - source[1]);
        screen.mouseReleased(end);
    }

    private static <T> T read(Object object, String name, Class<T> type) {
        // 夹具读取场景投影，生产 API 不暴露测试专用入口。
        try {
            var field = object.getClass().getDeclaredField(name);
            field.setAccessible(true /* flag */);
            return type.cast(field.get(object));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
