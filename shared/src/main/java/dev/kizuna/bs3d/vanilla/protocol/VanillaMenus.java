package dev.kizuna.bs3d.vanilla.protocol;

import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;

import java.util.Set;

/** 显式列出适配的原版菜单，第三方菜单保留自己的布局和交互。 */
public final class VanillaMenus {
    private static final Set<ScreenHandlerType<?>> TYPES =
            Set.of(
                    ScreenHandlerType.CRAFTING,
                    ScreenHandlerType.FURNACE,
                    ScreenHandlerType.BLAST_FURNACE,
                    ScreenHandlerType.SMOKER,
                    ScreenHandlerType.ANVIL,
                    ScreenHandlerType.SMITHING,
                    ScreenHandlerType.GRINDSTONE,
                    ScreenHandlerType.STONECUTTER,
                    ScreenHandlerType.LOOM,
                    ScreenHandlerType.CARTOGRAPHY_TABLE,
                    ScreenHandlerType.ENCHANTMENT,
                    ScreenHandlerType.BREWING_STAND,
                    ScreenHandlerType.BEACON,
                    ScreenHandlerType.GENERIC_9X1,
                    ScreenHandlerType.GENERIC_9X2,
                    ScreenHandlerType.GENERIC_9X3,
                    ScreenHandlerType.GENERIC_9X4,
                    ScreenHandlerType.GENERIC_9X5,
                    ScreenHandlerType.GENERIC_9X6,
                    ScreenHandlerType.GENERIC_3X3,
                    ScreenHandlerType.HOPPER,
                    ScreenHandlerType.SHULKER_BOX);

    private VanillaMenus() {}

    public static boolean supports(ScreenHandler handler) {
        // 玩家背包复用同一场景与事务，E 页和工作站页使用相同拖放规则。
        if (handler instanceof PlayerScreenHandler) {
            return true;
        }
        try {
            return TYPES.contains(handler.getType());
        } catch (UnsupportedOperationException unregistered) {
            return false;
        }
    }
}
