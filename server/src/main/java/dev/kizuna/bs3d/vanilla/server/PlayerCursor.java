package dev.kizuna.bs3d.vanilla.server;

import dev.kizuna.backpack3d.protocol.PlayerSlots;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.PlayerSlot;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

/** 在当前页面的光标与模型部位之间操作，部位规则仍由原版玩家 AbstractContainerMenu 决定。 */
final class PlayerCursor {
    private PlayerCursor() {}

    static void click(ServerPlayer player, PlayerSlot request) {
        // 槽号采用 Inventory 体系，不把工作站的 slot.id 当作身体部位。
        int index = request.getSlot();
        if (!PlayerSlots.isInteractive(index, player.getInventory().selected)) {
            throw new IllegalArgumentException("只能选择当前双手或盔甲部位");
        }
        ItemStack stack = player.getInventory().getItem(index);
        if (!StackCodec.fingerprint(stack).equals(request.getFingerprint())) {
            throw new IllegalArgumentException("部位物品已变化");
        }
        if (PlayerSlots.bindingLocked(index, stack, player.isCreative())) {
            throw new IllegalArgumentException("绑定诅咒装备不能取下");
        }
        var active = player.containerMenu;
        var body = player.inventoryMenu;
        var slot =
                body.slots.stream()
                        .filter(
                                value ->
                                        value.container == player.getInventory()
                                                && value.getContainerSlot() == index)
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("部位槽位不存在"));
        if (active == body) {
            // E 页本来就持有玩家光标，不需要借用和归还。
            body.clicked(slot.index, 0, ClickType.PICKUP, player);
            return;
        }
        if (!body.getCarried().isEmpty()) {
            throw new IllegalArgumentException("玩家背包光标尚未归还");
        }
        // 暂借原版玩家菜单执行一次点击，finally 把唯一光标交回正在打开的工作站。
        body.setCarried(active.getCarried());
        active.setCarried(ItemStack.EMPTY);
        try {
            body.clicked(slot.index, 0, ClickType.PICKUP, player);
        } finally {
            active.setCarried(body.getCarried());
            body.setCarried(ItemStack.EMPTY);
        }
    }
}
