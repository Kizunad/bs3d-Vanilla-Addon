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

/** 用真实按键状态和页面输入覆盖双向快捷搬运及合盖模型落点。 */
final class InstantMenuProbe {
    private static final int SETTLE_TICKS = 30;
    private static final int SLOT_CENTER_PIXELS = 8;
    private static final long INITIAL_REVISION = 4;
    private static final float MODEL_SEARCH_DEGREES = 15;
    /** 合成的左键输入不带修饰键；Shift 由 SmokeShift 设置真实按键状态。 */
    private static final MouseButtonInfo LEFT = new MouseButtonInfo(0, 0);
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
            if (step < 4) {
                done = dragSteps(client, screen, bag);
            } else if (step > 8) {
                done = groundSteps(client, screen);
            } else {
                done = shiftSteps(client, screen, bag);
            }
            if (done) {
                step++;
                ticks = 0;
            }
            return step > 13;
        } finally {
            if (step > 7) {
                SmokeShift.release();
            }
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

    private boolean shiftSteps(Minecraft client, AbstractContainerScreen<?> screen, String bag) {
        // Xvfb 下按住真实 Shift，再通过 Screen 的鼠标入口触发快捷搬运。
        var addon = VanillaClient.instance();
        switch (step) {
            case 4 -> {
                if (ticks < SETTLE_TICKS) {
                    return false;
                }
                SmokeShift.press();
            }
            case 5 -> {
                if (!Minecraft.getInstance().hasShiftDown()) {
                    return false;
                }
                click(screen, itemPoint(screen, bag));
                SmokeShift.release();
            }
            case 6 -> {
                if (addon.volume().state().getRevision() != INITIAL_REVISION + 3) {
                    return false;
                }
                SmokeShift.press();
            }
            case 7 -> {
                if (!Minecraft.getInstance().hasShiftDown()) {
                    return false;
                }
                Slot source = screen.getMenu().slots.stream()
                        .filter(slot -> slot.container == client.player.getInventory())
                        .filter(slot -> StackCodec.fingerprint(slot.getItem(), Minecraft.getInstance().player.registryAccess()).equals(moved))
                        .findFirst().orElseThrow();
                click(screen, slotPoint(screen, source));
                SmokeShift.release();
            }
            case 8 -> {
                if (addon.volume().state().getRevision() != INITIAL_REVISION + 4) {
                    return false;
                }
                screen.onClose();
                client.gui.setScreen(new InventoryScreen(client.player));
            }
            default -> throw new IllegalStateException("未知快捷搬运验收步骤");
        }
        return true;
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
