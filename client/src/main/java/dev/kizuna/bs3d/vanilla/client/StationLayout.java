package dev.kizuna.bs3d.vanilla.client;

import dev.kizuna.backpack3d.client.InteriorRenderer.Rect;

/** GUI 逻辑像素布局；原版区域始终保留，空间不足时退回原版页面。 */
public record StationLayout(Rect player, Rect interior, boolean visible) {
    public static final int MARGIN_PIXELS = 8;
    public static final int HEADER_PIXELS = 18;
    private static final int MIN_SCENE_WIDTH_PIXELS = 96;
    private static final int MIN_SCENE_HEIGHT_PIXELS = 160;

    public static StationLayout beside(int width, int height, int menuLeft, int menuWidth) {
        // 不移动原版槽位和配方书按钮；两个附属场景只使用两侧剩余空间。
        int sceneHeight = Math.max(0, height - MARGIN_PIXELS * 2 - HEADER_PIXELS);
        int leftWidth = Math.max(0, menuLeft - MARGIN_PIXELS * 2);
        int right = menuLeft + menuWidth + MARGIN_PIXELS;
        int rightWidth = Math.max(0, width - right - MARGIN_PIXELS);
        boolean visible =
                leftWidth >= MIN_SCENE_WIDTH_PIXELS
                        && rightWidth >= MIN_SCENE_WIDTH_PIXELS
                        && sceneHeight >= MIN_SCENE_HEIGHT_PIXELS;
        return new StationLayout(
                new Rect(MARGIN_PIXELS, HEADER_PIXELS, leftWidth, sceneHeight),
                new Rect(right, HEADER_PIXELS, rightWidth, sceneHeight),
                visible);
    }
}
