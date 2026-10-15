package dev.kizuna.bs3d.vanilla.server;

import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Cell;
import dev.kizuna.backpack3d.server.HandlingRules;
import dev.kizuna.backpack3d.server.InventoryAccess;
import dev.kizuna.backpack3d.server.VolumeServer;
import dev.kizuna.backpack3d.service.CursorTransfer;
import dev.kizuna.backpack3d.service.TransferService;
import dev.kizuna.bs3d.vanilla.protocol.VanillaMenus;
import dev.kizuna.bs3d.vanilla.protocol.VanillaMessages;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.*;
import dev.kizuna.inventoryui.server.ServerConnection;
import dev.kizuna.inventoryui.server.ServerModuleSession;
import dev.kizuna.inventoryui.server.ServerRequest;

import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 页面身份、计时和幂等收据均属于本连接；请求不能访问未打开的方块。 */
final class MenuSession implements ServerModuleSession<ServerPlayerEntity> {
    private static final int RECEIPT_LIMIT = 128;
    private static final double MOVEMENT_TOLERANCE_SQUARED_BLOCKS = 0.01;
    private final MinecraftServer server;
    private final ServerConnection<ServerPlayerEntity> connection;
    private final Map<String, Receipt> receipts = new LinkedHashMap<>();
    private ScreenHandler handler;
    private String menu = "";
    private boolean subscribed;
    private Pending pending;

    MenuSession(MinecraftServer server, ServerConnection<ServerPlayerEntity> connection) {
        this.server = server;
        this.connection = connection;
    }

    MinecraftServer server() {
        return server;
    }

    boolean locks(ServerPlayerEntity player) {
        return pending != null && connection.context() == player;
    }

    @Override
    public boolean handle(ServerRequest<ServerPlayerEntity> request) {
        // Framework 已认证连接身份；业务层仍需确认消息形状与当前原版页面。
        if (request.message().equals(VanillaMessages.SUBSCRIBE)) {
            var subscription = request.payload(SubscribeRequest.getDefaultInstance());
            if (!subscription.getUnknownFields().asMap().isEmpty()) {
                return false;
            }
            subscribed = true;
            refreshMenu();
            publish(state("", handler == null ? Status.CLOSED : Status.READY, ""));
            return true;
        }
        if (!subscribed || !request.message().equals(VanillaMessages.ACTION)) {
            return false;
        }
        ActionRequest action = request.payload(ActionRequest.getDefaultInstance());
        refreshMenu();
        // 关闭原版页面后可能还有移除回调发出的取消；它不能覆盖新页面的生命周期状态。
        if (action.hasCancelOperation() && (handler == null || !action.getMenu().equals(menu))) {
            return true;
        }
        try {
            validate(action);
            Receipt receipt = receipts.get(action.getOperation());
            if (receipt != null) {
                if (!receipt.request().equals(action)) {
                    throw new IllegalArgumentException("同一操作号不能更换参数");
                }
                publish(receipt.response());
                return true;
            }
            if (action.hasCancelOperation()) {
                cancel(action.getCancelOperation(), "操作已取消");
                return true;
            }
            if (pending != null || VolumeServer.locked(connection.context())) {
                throw new IllegalArgumentException("请等待当前转移结束");
            }
            requireCursor(action);
            if (action.hasPlayerSlot()) {
                PlayerCursor.click(connection.context(), action.getPlayerSlot());
                finish(action, Status.COMPLETED, "");
                return true;
            }
            // 开始时只验证副本。真实物品留在来源，ESC 或断线不需要回滚物品。
            CursorTransfer.Request exchange = exchange(action);
            var candidate =
                    InventoryAccess.previewCursor(
                            connection.context(), exchange, action.getCursorFingerprint());
            var material = VolumeServer.RULES.material(candidate.moved());
            pending = new Pending(action, exchange, material, connection.context().getPos());
            remember(action, progress());
            publish(progress());
        } catch (IllegalArgumentException invalid) {
            // 原版全量同步纠正客户端光标预测；失败说明只发给这条已认证连接。
            connection.context().currentScreenHandler.syncState();
            publish(state(action.getOperation(), Status.REJECTED, invalid.getMessage()));
        }
        return true;
    }

    private void validate(ActionRequest action) {
        // syncId 会复用，因此同时验证服务端新生成的页面 UUID。
        if (handler == null
                || !action.getMenu().equals(menu)
                || action.getSyncId() != handler.syncId) {
            throw new IllegalArgumentException("页面已关闭或更换");
        }
        var player = connection.context();
        if (!player.isAlive() || player.isSpectator() || !handler.canUse(player)) {
            throw new IllegalArgumentException("玩家或容器当前不可用");
        }
        if (!TransferService.validId(action.getOperation())) {
            throw new IllegalArgumentException("操作身份不是 UUID");
        }
        if (!action.getUnknownFields().asMap().isEmpty()
                || !action.getStoreCursor().getUnknownFields().asMap().isEmpty()
                || !action.getPlayerSlot().getUnknownFields().asMap().isEmpty()) {
            throw new IllegalArgumentException("请求包含未定义字段");
        }
        if (action.getActionCase() == ActionRequest.ActionCase.ACTION_NOT_SET) {
            throw new IllegalArgumentException("缺少操作");
        }
    }

    private void requireCursor(ActionRequest action) {
        // 指纹来自真实光标，而不是客户端声明的物品类型或数量。
        var fingerprint = StackCodec.fingerprint(handler.getCursorStack());
        if (!fingerprint.equals(action.getCursorFingerprint())) {
            throw new IllegalArgumentException("光标物品已变化，请重试");
        }
    }

    private CursorTransfer.Request exchange(ActionRequest action) {
        // 只构造随身背包交换；地面背包继续使用 BS3D 自己的租约与事务。
        if (action.hasTakeItem()) {
            return CursorTransfer.Request.take(action.getInventoryRevision(), action.getTakeItem());
        }
        var destination = action.getStoreCursor();
        var cell =
                Cell.newBuilder()
                        .setX(destination.getX())
                        .setY(destination.getY())
                        .setZ(destination.getZ())
                        .build();
        return CursorTransfer.Request.store(
                action.getInventoryRevision(),
                destination.getContainer(),
                cell,
                destination.getOrientation());
    }

    void tick() {
        // 页面更换首先使旧事务失效；炉子仍由原版方块实体独立推进燃烧。
        if (!subscribed) {
            return;
        }
        refreshMenu();
        if (pending == null) {
            return;
        }
        var player = connection.context();
        ActionRequest action = pending.request;
        try {
            validate(action);
            requireCursor(action);
            if (player.squaredDistanceTo(pending.origin) > MOVEMENT_TOLERANCE_SQUARED_BLOCKS) {
                throw new IllegalArgumentException("玩家位置已变化");
            }
            pending.elapsed++;
            if (pending.elapsed < pending.material.ticks()) {
                remember(action, progress());
                publish(progress());
                return;
            }
            // 完成时重新校验最新树和指纹，再在同一服务器调用内更新背包与原版光标。
            InventoryAccess.exchangeCursor(player, pending.exchange, action.getCursorFingerprint());
            var sound = Registries.SOUND_EVENT.get(new Identifier(pending.material.sound()));
            player.playSound(
                    sound,
                    SoundCategory.PLAYERS,
                    pending.material.volume(),
                    pending.material.pitch());
            pending = null;
            finish(action, Status.COMPLETED, "");
        } catch (IllegalArgumentException invalid) {
            pending = null;
            finish(action, Status.CANCELLED, invalid.getMessage());
        }
    }

    private void refreshMenu() {
        var player = connection.context();
        ScreenHandler next = player.currentScreenHandler;
        if (!VanillaMenus.supports(next)
                || !player.isAlive()
                || player.isSpectator()
                || !next.canUse(player)) {
            next = null;
        }
        if (next == handler) {
            return;
        }
        // 旧页面的计时和收据一并释放；光标交由原版 closeHandledScreen 归还。
        pending = null;
        receipts.clear();
        handler = next;
        menu = next == null ? "" : UUID.randomUUID().toString();
        publish(state("", next == null ? Status.CLOSED : Status.READY, ""));
    }

    private void cancel(String operation, String detail) {
        // 仅取消指定操作，延迟到达的 ESC 不能取消后来开始的另一件物品。
        if (pending != null && pending.request.getOperation().equals(operation)) {
            var action = pending.request;
            pending = null;
            finish(action, Status.CANCELLED, detail);
        }
    }

    private MenuState progress() {
        return state(pending.request.getOperation(), Status.RUNNING, "").toBuilder()
                .setElapsedTicks(pending.elapsed)
                .setDurationTicks(pending.material.ticks())
                .build();
    }

    private MenuState state(String operation, Status status, String detail) {
        return MenuState.newBuilder()
                .setMenu(menu)
                .setSyncId(handler == null ? 0 : handler.syncId)
                .setOperation(operation)
                .setStatus(status)
                .setDetail(detail)
                .build();
    }

    private void finish(ActionRequest action, Status status, String detail) {
        var response = state(action.getOperation(), status, detail);
        remember(action, response);
        publish(response);
    }

    private void remember(ActionRequest action, MenuState response) {
        // 重发仅重发收据，不再次执行取物；容量按页面限制，避免长期开箱积累内存。
        receipts.put(action.getOperation(), new Receipt(action, response));
        if (receipts.size() > RECEIPT_LIMIT) {
            receipts.remove(receipts.keySet().iterator().next());
        }
    }

    private void publish(MenuState state) {
        connection.send(VanillaMessages.STATE, state);
    }

    @Override
    public void close() {
        // 尚未提交的动作没有改过真实栈，断线只需释放计时和位置锁。
        pending = null;
        receipts.clear();
        VanillaServer.forget(this);
    }

    private record Receipt(ActionRequest request, MenuState response) {}

    private static final class Pending {
        final ActionRequest request;
        final CursorTransfer.Request exchange;
        final HandlingRules.Material material;
        final Vec3d origin;
        int elapsed;

        Pending(
                ActionRequest request,
                CursorTransfer.Request exchange,
                HandlingRules.Material material,
                Vec3d origin) {
            this.request = request;
            this.exchange = exchange;
            this.material = material;
            this.origin = origin;
        }
    }
}
