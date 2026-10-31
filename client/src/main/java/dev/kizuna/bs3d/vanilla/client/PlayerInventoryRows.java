package dev.kizuna.bs3d.vanilla.client;

import net.minecraft.world.entity.player.Inventory;

/** 原版玩家主背包的可见行数接入点；槽位权限由安装该策略的附属负责。 */
public final class PlayerInventoryRows {
    public static final int COLUMNS = 9;
    public static final int VANILLA_ROWS = 3;
    private static int visibleRows = VANILLA_ROWS;

    private PlayerInventoryRows() {
        // 展示策略只在模组初始化时登记，不保存玩家库存。
    }

    public static int visibleRows() {
        // 读者共享同一行数，工作站页面不自行猜测口袋大小。
        return visibleRows;
    }

    public static void setVisibleRows(int rows) {
        // 原版主背包最多三行，快捷栏始终单独保留。
        if (rows < 1 || rows > VANILLA_ROWS) {
            throw new IllegalArgumentException("玩家主背包可见行数必须位于 1..3");
        }
        visibleRows = rows;
    }

    public static boolean visible(int inventorySlot) {
        // 快捷栏、装备和副手不计入主背包行数，仍由人物模型或原版页面展示。
        int end = COLUMNS * (visibleRows + 1);
        return inventorySlot < end || inventorySlot >= Inventory.INVENTORY_SIZE;
    }
}
