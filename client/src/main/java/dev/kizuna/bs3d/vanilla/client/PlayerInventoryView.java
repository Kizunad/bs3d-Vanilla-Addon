package dev.kizuna.bs3d.vanilla.client;

import dev.kizuna.bs3d.vanilla.protocol.VanillaMenus;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.Slot;

/** 已适配工作站按可见行数裁接原版纹理，第三方菜单保持原布局。 */
public final class PlayerInventoryView {
    public static final int SLOT_PITCH_PIXELS = 18;
    private static final int SLOT_BORDER_PIXELS = 1;
    private static final int HOTBAR_GAP_PIXELS = 4;

    private PlayerInventoryView() {
        // 所有库存值仍从原版槽位读取。
    }

    public static boolean visible(HandledScreen<?> screen, Slot slot) {
        // 未适配菜单保留自己的绘制；人物装备的展示由页面进一步决定。
        if (!((StationHost) screen).stationSlotVisible(slot)) {
            return false;
        }
        if (!VanillaMenus.supports(screen.getScreenHandler())) {
            return true;
        }
        var player = MinecraftClient.getInstance().player;
        if (slot.inventory == player.getInventory() && !PlayerInventoryRows.visible(slot.getIndex())) {
            return false;
        }
        return true;
    }

    public static void arrange(HandledScreen<?> screen) {
        // 保留玩家区第一行位置，只把快捷栏移到最后一条可见主背包行下方。
        Slot first = stationMainSlot(screen);
        if (first == null) {
            return;
        }
        int hotbarY = first.y + PlayerInventoryRows.visibleRows() * SLOT_PITCH_PIXELS + HOTBAR_GAP_PIXELS;
        var inventory = MinecraftClient.getInstance().player.getInventory();
        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.inventory == inventory && slot.getIndex() < PlayerInventoryRows.COLUMNS) {
                ((SlotPosition) slot).bs3dVanilla$setY(hotbarY);
            }
        }
    }

    public static void drawBackground(HandledScreen<?> screen, DrawContext context, Runnable original) {
        // 背景在最后一条可见行的下边框处切开，下半段整体上移，方块槽位纹理不重写。
        Slot first = stationMainSlot(screen);
        int visibleRows = PlayerInventoryRows.visibleRows();
        int removedRows = PlayerInventoryRows.VANILLA_ROWS - visibleRows;
        if (first == null || removedRows == 0) {
            original.run();
            return;
        }
        int frameTop = ((StationHost) screen).stationTop() + first.y - SLOT_BORDER_PIXELS;
        int cut = frameTop + visibleRows * SLOT_PITCH_PIXELS;
        context.enableScissor(0, 0, screen.width, cut);
        original.run();
        context.disableScissor();
        context.enableScissor(0, cut, screen.width, screen.height);
        context.getMatrices().push();
        context.getMatrices().translate(0, -removedRows * SLOT_PITCH_PIXELS, 0);
        original.run();
        context.getMatrices().pop();
        context.disableScissor();
    }

    private static Slot stationMainSlot(HandledScreen<?> screen) {
        // 玩家 E 页由页面自身排版，第三方菜单保持三行；其余工作站以第一格主背包定位玩家区。
        var handler = screen.getScreenHandler();
        if (handler instanceof PlayerScreenHandler || !VanillaMenus.supports(handler)) {
            return null;
        }
        var inventory = MinecraftClient.getInstance().player.getInventory();
        return handler.slots.stream()
                .filter(slot -> slot.inventory == inventory)
                .filter(slot -> slot.getIndex() == PlayerInventoryRows.COLUMNS)
                .findFirst()
                .orElse(null);
    }
}
