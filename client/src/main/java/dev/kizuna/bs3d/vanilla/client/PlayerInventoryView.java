package dev.kizuna.bs3d.vanilla.client;

import dev.kizuna.bs3d.vanilla.protocol.VanillaMenus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;

/** 已适配工作站按可见行数裁接原版纹理，第三方菜单保持原布局。 */
public final class PlayerInventoryView {
    public static final int SLOT_PITCH_PIXELS = 18;
    private static final int SLOT_BORDER_PIXELS = 1;
    private static final int HOTBAR_GAP_PIXELS = 4;
    private static boolean textureOnly;

    private PlayerInventoryView() {
        // 所有库存值仍从原版槽位读取。
    }

    public static boolean visible(AbstractContainerScreen<?> screen, Slot slot) {
        // 未适配菜单保留自己的绘制；人物装备的展示由页面进一步决定。
        if (!((StationHost) screen).stationSlotVisible(slot)) {
            return false;
        }
        if (!VanillaMenus.supports(screen.getMenu())) {
            return true;
        }
        var player = Minecraft.getInstance().player;
        if (slot.container == player.getInventory() && !PlayerInventoryRows.visible(slot.getContainerSlot())) {
            return false;
        }
        return true;
    }

    public static void arrange(AbstractContainerScreen<?> screen) {
        // 保留玩家区第一行位置，只把快捷栏移到最后一条可见主背包行下方。
        Slot first = stationMainSlot(screen);
        if (first == null) {
            return;
        }
        int hotbarY = first.y + PlayerInventoryRows.visibleRows() * SLOT_PITCH_PIXELS + HOTBAR_GAP_PIXELS;
        var inventory = Minecraft.getInstance().player.getInventory();
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container == inventory && slot.getContainerSlot() < PlayerInventoryRows.COLUMNS) {
                ((SlotPosition) slot).bs3dVanilla$setY(hotbarY);
            }
        }
    }

    /**
     * @param backdrop 只画原版暗幕层
     * @param original 页面完整的背景绘制（暗幕层加工作站纹理）
     */
    public static void drawBackground(
            AbstractContainerScreen<?> screen, GuiGraphicsExtractor context, Runnable backdrop, Runnable original) {
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
        // 26.2 各工作站在同一个背景方法里先画暗幕再画纹理；暗幕铺满全屏只画一次，
        // 两段裁剪期间由 ScreenBackgroundMixin 跳过暗幕，避免上移后底部留出无暗幕的空带。
        backdrop.run();
        textureOnly = true;
        context.enableScissor(0, 0, screen.width, cut);
        original.run();
        context.disableScissor();
        context.enableScissor(0, cut, screen.width, screen.height);
        context.pose().pushMatrix();
        context.pose().translate(0, -removedRows * SLOT_PITCH_PIXELS);
        original.run();
        context.pose().popMatrix();
        context.disableScissor();
        textureOnly = false;
    }

    /** 裁接纹理的两段绘制期间为 true，此时原版暗幕已经画过。 */
    public static boolean textureOnly() {
        return textureOnly;
    }

    private static Slot stationMainSlot(AbstractContainerScreen<?> screen) {
        // 玩家 E 页由页面自身排版，第三方菜单保持三行；其余工作站以第一格主背包定位玩家区。
        var handler = screen.getMenu();
        if (handler instanceof InventoryMenu || !VanillaMenus.supports(handler)) {
            return null;
        }
        var inventory = Minecraft.getInstance().player.getInventory();
        return handler.slots.stream()
                .filter(slot -> slot.container == inventory)
                .filter(slot -> slot.getContainerSlot() == PlayerInventoryRows.COLUMNS)
                .findFirst()
                .orElse(null);
    }
}
