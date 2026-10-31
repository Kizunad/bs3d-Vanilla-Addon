package dev.kizuna.bs3d.vanilla.server;

import com.google.protobuf.ByteString;
import dev.kizuna.backpack3d.protocol.PlayerSlots;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Cell;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.SavedInventory;
import dev.kizuna.backpack3d.server.InventoryAccess;
import dev.kizuna.backpack3d.server.PlayerAccount;
import dev.kizuna.backpack3d.server.PlayerStorage;
import dev.kizuna.backpack3d.service.CursorTransfer;
import dev.kizuna.bs3d.vanilla.mixin.ScreenHandlerAccess;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.ActionRequest;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.BagDestination;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.DragTransfer;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.MenuSlot;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.PlayerSlot;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** 一次拖放在服务器调用内完成来源、背包交换和原版点击，失败恢复整份库存。 */
final class MenuTransfers {
    private static final int PLAYER_STORAGE_START = 9;
    private static final int PLAYER_STORAGE_END = 45;

    private MenuTransfers() {
        // 事务只使用当前服务器调用的快照，不保存跨 tick 状态。
    }

    static ItemStack apply(ServerPlayer player, ActionRequest action) {
        // 页面与操作身份由会话验证，这里统一验证来源、落点及库存回滚边界。
        var handler = player.containerMenu;
        long revision = ((PlayerStorage) player).bs3d$getInventory().getRevision();
        if (revision != action.getInventoryRevision()) {
            throw new IllegalArgumentException("背包已变化，请重新选择物品");
        }
        var before = snapshot(player);
        var transaction = InventoryAccess.cursorTransaction(player);
        try {
            DragTransfer transfer = action.getTransfer();
            take(player, transfer, action.getInventoryRevision(), transaction);
            ItemStack moved = handler.getCarried().copy();
            if (moved.isEmpty()) {
                throw new IllegalArgumentException("来源没有物品");
            }
            put(player, transfer, transaction);
            if (!handler.getCarried().isEmpty()) {
                throw new IllegalArgumentException("目标槽位放不下整栈物品");
            }
            transaction.commit();
            return moved;
        } catch (RuntimeException failure) {
            restore(player, before);
            throw failure;
        } finally {
            handler.sendAllDataToRemote();
            player.inventoryMenu.broadcastChanges();
        }
    }

    private static Snapshot snapshot(ServerPlayer player) {
        // 所有栈都复制，原版 onSlotClick 对栈的原地修改也可以完整恢复。
        var account = new PlayerAccount(player);
        var handler = player.containerMenu;
        List<ItemStack> slots = handler.slots.stream().map(slot -> slot.getItem().copy()).toList();
        return new Snapshot(account.data(), account.inventory(), slots, handler.getCarried().copy());
    }

    private static void restore(ServerPlayer player, Snapshot snapshot) {
        // 先恢复菜单，再恢复原版玩家槽位，避免菜单中映射的玩家槽互相覆盖。
        var handler = player.containerMenu;
        for (int index = 0; index < snapshot.menu().size(); index++) {
            handler.getSlot(index).setByPlayer(snapshot.menu().get(index).copy());
        }
        for (int index = 0; index < snapshot.player().size(); index++) {
            player.getInventory().setItem(index, snapshot.player().get(index).copy());
        }
        ((PlayerStorage) player).bs3d$setInventory(snapshot.data());
        handler.setCarried(snapshot.cursor().copy());
    }

    private static void take(ServerPlayer player, DragTransfer transfer, long revision,
                             InventoryAccess.CursorTransaction transaction) {
        // 非光标来源必须从空光标开始，不能用拖放隐式交换已有光标物品。
        var handler = player.containerMenu;
        if (transfer.hasCursor()) {
            if (!transfer.getCursor()) {
                throw new IllegalArgumentException("光标来源无效");
            }
            return;
        }
        if (!handler.getCarried().isEmpty()) {
            throw new IllegalArgumentException("请先放下光标中的物品");
        }
        switch (transfer.getSourceCase()) {
            case ITEM -> transaction.exchange(CursorTransfer.Request.take(revision, transfer.getItem()));
            case SLOT -> takeSlot(player, menuSlot(handler, transfer.getSlot()));
            case BODY -> takeBody(player, transfer.getBody());
            default -> throw new IllegalArgumentException("缺少物品来源");
        }
    }

    private static Slot menuSlot(AbstractContainerMenu handler, MenuSlot request) {
        // 指纹在点击前核对，不能把页面上已经更换的栈误当作原拖动来源或落点。
        int index = request.getSlot();
        if (index < 0 || index >= handler.slots.size()) {
            throw new IllegalArgumentException("原版槽位无效");
        }
        Slot slot = handler.getSlot(index);
        requireFingerprint(slot.getItem(), request.getFingerprint());
        return slot;
    }

    private static void requireFingerprint(ItemStack stack, ByteString expected) {
        // 服务端读取真实栈，客户端只提供抓取时的摘要。
        if (!StackCodec.fingerprint(stack).equals(expected)) {
            throw new IllegalArgumentException("槽位物品已变化");
        }
    }

    private static void takeSlot(ServerPlayer player, Slot slot) {
        // 合成结果、燃料和普通槽位都走原版点击，保留其取出权限与回调。
        if (!slot.mayPickup(player) || !slot.hasItem()) {
            throw new IllegalArgumentException("这个槽位不能取出物品");
        }
        player.containerMenu.clicked(slot.index, 0, ClickType.PICKUP, player);
    }

    private static void takeBody(ServerPlayer player, PlayerSlot request) {
        // 玩家模型使用 Inventory 槽号，绑定装备必须先验证卸装权限。
        int index = request.getSlot();
        if (!PlayerSlots.isInteractive(index, player.getInventory().selected)) {
            throw new IllegalArgumentException("来源不是当前双手或盔甲");
        }
        var stack = player.getInventory().getItem(index);
        requireFingerprint(stack, request.getFingerprint());
        if (PlayerSlots.bindingLocked(index, stack, player.isCreative())) {
            throw new IllegalArgumentException("绑定诅咒装备不能取下");
        }
        PlayerCursor.click(player, request);
    }

    private static void put(ServerPlayer player, DragTransfer transfer,
                            InventoryAccess.CursorTransaction transaction) {
        // 快捷取出直接复用原版 insertItem；普通落点执行一次原版左键点击。
        switch (transfer.getTargetCase()) {
            case BAG -> {
                long revision = ((PlayerStorage) player).bs3d$getInventory().getRevision();
                transaction.exchange(destination(revision, transfer.getBag()));
            }
            case DESTINATION -> putSlot(player, menuSlot(player.containerMenu, transfer.getDestination()));
            case BODY_DESTINATION -> PlayerCursor.click(player, transfer.getBodyDestination());
            case PLAYER_INVENTORY -> {
                if (!transfer.getPlayerInventory()) {
                    throw new IllegalArgumentException("自动取出目标无效");
                }
                var cursor = player.containerMenu.getCarried();
                ((ScreenHandlerAccess) player.inventoryMenu).bs3dVanilla$insertItem(
                        cursor, PLAYER_STORAGE_START, PLAYER_STORAGE_END, false /* fromLast */);
            }
            default -> throw new IllegalArgumentException("缺少物品落点");
        }
    }

    private static void putSlot(ServerPlayer player, Slot slot) {
        // 拒绝交换和部分存入，确保整个拖动物品只有一个确定归属。
        var handler = player.containerMenu;
        ItemStack cursor = handler.getCarried();
        ItemStack existing = slot.getItem();
        if (!slot.mayPlace(cursor)
                || !existing.isEmpty() && !ItemStack.isSameItemSameTags(existing, cursor)
                || existing.getCount() + cursor.getCount() > slot.getMaxStackSize(cursor)) {
            throw new IllegalArgumentException("目标槽位放不下整栈物品");
        }
        handler.clicked(slot.index, 0, ClickType.PICKUP, player);
    }

    static CursorTransfer.Request destination(long revision, BagDestination destination) {
        // 自动寻位由容器树处理，精确格位只在手动内景放置时传入。
        if (destination.getAutoPlace()) {
            return CursorTransfer.Request.autoStore(revision, destination.getContainer(), destination.getOrientation());
        }
        var cell = Cell.newBuilder()
                .setX(destination.getX())
                .setY(destination.getY())
                .setZ(destination.getZ())
                .build();
        return CursorTransfer.Request.store(revision, destination.getContainer(), cell, destination.getOrientation());
    }

    private record Snapshot(SavedInventory data, List<ItemStack> player, List<ItemStack> menu, ItemStack cursor) {}
}
