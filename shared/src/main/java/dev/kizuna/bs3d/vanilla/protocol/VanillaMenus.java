package dev.kizuna.bs3d.vanilla.protocol;

import java.util.Set;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;

/** 显式列出适配的原版菜单，第三方菜单保留自己的布局和交互。 */
public final class VanillaMenus {
    private static final Set<MenuType<?>> TYPES =
            Set.of(
                    MenuType.CRAFTING,
                    MenuType.FURNACE,
                    MenuType.BLAST_FURNACE,
                    MenuType.SMOKER,
                    MenuType.ANVIL,
                    MenuType.SMITHING,
                    MenuType.GRINDSTONE,
                    MenuType.STONECUTTER,
                    MenuType.LOOM,
                    MenuType.CARTOGRAPHY_TABLE,
                    MenuType.ENCHANTMENT,
                    MenuType.BREWING_STAND,
                    MenuType.BEACON,
                    MenuType.GENERIC_9x1,
                    MenuType.GENERIC_9x2,
                    MenuType.GENERIC_9x3,
                    MenuType.GENERIC_9x4,
                    MenuType.GENERIC_9x5,
                    MenuType.GENERIC_9x6,
                    MenuType.GENERIC_3x3,
                    MenuType.HOPPER,
                    MenuType.SHULKER_BOX);

    private VanillaMenus() {}

    public static boolean supports(AbstractContainerMenu handler) {
        // 玩家背包复用同一场景与事务，E 页和工作站页使用相同拖放规则。
        if (handler instanceof InventoryMenu) {
            return true;
        }
        try {
            return TYPES.contains(handler.getType());
        } catch (UnsupportedOperationException unregistered) {
            return false;
        }
    }
}
