package dev.kizuna.bs3d.vanilla.server;

import dev.kizuna.backpack3d.protocol.PlayerSlots;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.PlayerSlot;

import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;

/** 在当前页面的光标与模型部位之间操作，部位规则仍由原版玩家 ScreenHandler 决定。 */
final class PlayerCursor {
    private PlayerCursor() {}

    static void click(ServerPlayerEntity player, PlayerSlot request) {
        // 槽号采用 PlayerInventory 体系，不把工作站的 slot.id 当作身体部位。
        int index = request.getSlot();
        if (!PlayerSlots.isInteractive(index, player.getInventory().selectedSlot)) {
            throw new IllegalArgumentException("只能选择当前双手或盔甲部位");
        }
        ItemStack stack = player.getInventory().getStack(index);
        if (!StackCodec.fingerprint(stack).equals(request.getFingerprint())) {
            throw new IllegalArgumentException("部位物品已变化");
        }
        if (PlayerSlots.bindingLocked(index, stack, player.isCreative())) {
            throw new IllegalArgumentException("绑定诅咒装备不能取下");
        }
        var active = player.currentScreenHandler;
        var body = player.playerScreenHandler;
        var slot =
                body.slots.stream()
                        .filter(
                                value ->
                                        value.inventory == player.getInventory()
                                                && value.getIndex() == index)
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("部位槽位不存在"));
        if (active == body) {
            // E 页本来就持有玩家光标，不需要借用和归还。
            body.onSlotClick(slot.id, 0, SlotActionType.PICKUP, player);
            return;
        }
        if (!body.getCursorStack().isEmpty()) {
            throw new IllegalArgumentException("玩家背包光标尚未归还");
        }
        // 暂借原版玩家菜单执行一次点击，finally 把唯一光标交回正在打开的工作站。
        body.setCursorStack(active.getCursorStack());
        active.setCursorStack(ItemStack.EMPTY);
        try {
            body.onSlotClick(slot.id, 0, SlotActionType.PICKUP, player);
        } finally {
            active.setCursorStack(body.getCursorStack());
            body.setCursorStack(ItemStack.EMPTY);
        }
    }
}
