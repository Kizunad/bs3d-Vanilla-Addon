package dev.kizuna.bs3d.vanilla.client;

import dev.kizuna.bs3d.vanilla.protocol.VanillaMenus;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

/** 读取原版页面的实际布局，不复制各工作站的槽位坐标。 */
public interface StationHost {
    StationPanel stationPanel();

    int stationLeft();

    int stationTop();

    int stationWidth();

    Slot stationSlotAt(double x, double y);

    /** 以原版页面自身的点击入口操作页面槽，页面专有的槽位规则随之生效。 */
    void stationClick(Slot slot);

    default StationLayout stationLayout() {
        // 默认场景使用原版工作站两侧，附属可为玩家 E 页提供专用区域。
        var screen = (AbstractContainerScreen<?>) this;
        return StationLayout.beside(screen.width, screen.height, stationLeft(), stationWidth());
    }

    default boolean stationSlotVisible(Slot slot) {
        // 默认保留所有装备和工作站槽位，行数裁剪由统一玩家区处理。
        return true;
    }

    default boolean stationSupported() {
        // 默认只接入服务器菜单已适配的页面；客户端专有页面由页面自身声明。
        return VanillaMenus.supports(((AbstractContainerScreen<?>) this).getMenu());
    }

    default boolean stationActive() {
        // 有页签的页面只在承载玩家库存的页签显示场景。
        return true;
    }

    default Slot stationMenuSlot(Slot slot) {
        // 页面槽默认就是服务器菜单槽；返回 null 表示该槽只存在于客户端页面。
        return slot;
    }

    default boolean stationSharedCursor() {
        // 默认光标由服务器同步；返回 false 表示光标只在客户端，携物时不能提交事务。
        return true;
    }
}
