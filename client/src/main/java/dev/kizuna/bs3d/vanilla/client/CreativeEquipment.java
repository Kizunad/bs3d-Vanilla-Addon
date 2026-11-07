package dev.kizuna.bs3d.vanilla.client;

import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.DragTransfer;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.MenuSlot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** 创造光标先使用原版授权写槽，业务请求只引用之后的真实槽与指纹。 */
final class CreativeEquipment {
    private CreativeEquipment() {}

    static void wear(AbstractContainerScreen<?> screen, DragTransfer transfer) {
        var client = Minecraft.getInstance();
        var addon = VanillaClient.instance();
        var cursor = screen.getMenu().getCarried();
        if (!(screen instanceof CreativeModeInventoryScreen) || !client.player.hasInfiniteMaterials()
                || cursor.isEmpty() || !addon.ready(screen) || addon.busy() || addon.volume().busy()) {
            return;
        }
        Slot slot = availableSlot(cursor);
        if (slot == null) {
            addon.reject("背包已满，无法取得这件装备");
            return;
        }
        var source = MenuSlot.newBuilder()
                .setSlot(slot.index)
                .setFingerprint(StackCodec.fingerprint(cursor, client.player.registryAccess()))
                .build();
        var request = transfer.toBuilder().setSlot(source).build();
        // 同一连接先处理原版创造包，再处理业务意图；原版检查创造权限、物品和槽位限制。
        // 穿戴失败时真实物品留在该槽，由菜单全量同步返回，不重新创建或覆盖原有栈。
        client.gameMode.handleCreativeModeItemAdd(cursor.copy(), slot.index);
        screen.getMenu().setCarried(ItemStack.EMPTY);
        addon.action(screen, action -> action.setTransfer(request));
    }

    private static Slot availableSlot(ItemStack stack) {
        var player = Minecraft.getInstance().player;
        var inventory = player.getInventory();
        for (Slot slot : player.inventoryMenu.slots) {
            int index = slot.getContainerSlot();
            if (slot.container == inventory && index >= 0 && index < Inventory.INVENTORY_SIZE
                    && PlayerInventoryRows.visible(index) && !slot.hasItem() && slot.mayPlace(stack)) {
                return slot;
            }
        }
        return null;
    }
}
