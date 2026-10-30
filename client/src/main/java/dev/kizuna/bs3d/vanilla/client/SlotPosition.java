package dev.kizuna.bs3d.vanilla.client;

/** 页面只移动原版槽位坐标，不替换槽位对象及其装备或配方规则。 */
public interface SlotPosition {
    void bs3dVanilla$setX(int x);

    void bs3dVanilla$setY(int y);
}
