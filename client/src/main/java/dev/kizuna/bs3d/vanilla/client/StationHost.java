package dev.kizuna.bs3d.vanilla.client;

import net.minecraft.screen.slot.Slot;

/** 读取原版页面的实际布局，不复制各工作站的槽位坐标。 */
public interface StationHost {
    StationPanel stationPanel();

    int stationLeft();

    int stationTop();

    int stationWidth();

    Slot stationSlotAt(double x, double y);
}
