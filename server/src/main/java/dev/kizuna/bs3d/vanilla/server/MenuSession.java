package dev.kizuna.bs3d.vanilla.server;

import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Cell;
import dev.kizuna.backpack3d.server.InventoryAccess;
import dev.kizuna.backpack3d.server.VolumeServer;
import dev.kizuna.backpack3d.service.CursorTransfer;
import dev.kizuna.backpack3d.service.TransferService;
import dev.kizuna.bs3d.vanilla.protocol.VanillaMenus;
import dev.kizuna.bs3d.vanilla.protocol.VanillaMessages;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.ActionRequest;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.MenuState;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.Status;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.SubscribeRequest;
import dev.kizuna.inventoryui.server.ServerConnection;
import dev.kizuna.inventoryui.server.ServerModuleSession;
import dev.kizuna.inventoryui.server.ServerRequest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 页面身份和幂等收据均属于本连接；请求不能访问未打开的方块。 */
final class MenuSession implements ServerModuleSession<ServerPlayer> {
    private static final int RECEIPT_LIMIT = 128;
    private final MinecraftServer server;
    private final ServerConnection<ServerPlayer> connection;
    private final Map<String, Receipt> receipts = new LinkedHashMap<>();
    private AbstractContainerMenu handler;
    private String menu = "";
    private boolean subscribed;

    MenuSession(MinecraftServer server, ServerConnection<ServerPlayer> connection) {
        this.server = server;
        this.connection = connection;
    }

    MinecraftServer server() {
        return server;
    }

    @Override
    public boolean handle(ServerRequest<ServerPlayer> request) {
        // Framework 已认证连接身份；业务层仍需确认消息形状与当前原版页面。
        if (request.message().equals(VanillaMessages.SUBSCRIBE)) {
            return subscribe(request);
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
                // 即时事务没有可取消的阶段，旧关闭通知不撤销已提交库存。
                return true;
            }
            execute(action);
        } catch (IllegalArgumentException invalid) {
            // 原版全量同步纠正客户端光标预测；失败说明只发给这条已认证连接。
            connection.context().containerMenu.sendAllDataToRemote();
            if (receipts.containsKey(action.getOperation())) {
                publish(state(action.getOperation(), Status.REJECTED, invalid.getMessage()));
            } else {
                finish(action, Status.REJECTED, invalid.getMessage());
            }
        }
        return true;
    }

    private boolean subscribe(ServerRequest<ServerPlayer> request) {
        // 订阅只建立页面生命周期，地面模型在实际打开场景时授权。
        var subscription = request.payload(SubscribeRequest.getDefaultInstance());
        if (!subscription.getUnknownFields().asMap().isEmpty()) {
            return false;
        }
        subscribed = true;
        refreshMenu();
        Status status = Status.READY;
        if (handler == null) {
            status = Status.CLOSED;
        }
        publish(state("", status, ""));
        return true;
    }

    private void execute(ActionRequest action) {
        // 页面与重放校验完成后，只执行当前操作并发布一次终态。
        requireCursor(action);
        if (action.hasOpenScene()) {
            InventoryAccess.openScene(connection.context());
            finish(action, Status.COMPLETED, "");
            return;
        }
        if (action.hasPlayerSlot()) {
            PlayerCursor.click(connection.context(), action.getPlayerSlot());
            handler.sendAllDataToRemote();
            connection.context().inventoryMenu.broadcastChanges();
            finish(action, Status.COMPLETED, "");
            return;
        }
        var player = connection.context();
        ItemStack moved;
        if (action.hasTransfer()) {
            moved = MenuTransfers.apply(player, action);
        } else {
            CursorTransfer.Request exchange = exchange(action);
            var candidate = InventoryAccess.previewCursor(player, exchange, action.getCursorFingerprint());
            moved = candidate.moved();
            InventoryAccess.exchangeCursor(player, exchange, action.getCursorFingerprint());
        }
        playCompletion(moved);
        finish(action, Status.COMPLETED, "");
    }

    private void validate(ActionRequest action) {
        // syncId 会复用，因此同时验证服务端新生成的页面 UUID。
        if (handler == null
                || !action.getMenu().equals(menu)
                || action.getSyncId() != handler.containerId) {
            throw new IllegalArgumentException("页面已关闭或更换");
        }
        var player = connection.context();
        if (!player.isAlive() || player.isSpectator() || !handler.stillValid(player)) {
            throw new IllegalArgumentException("玩家或容器当前不可用");
        }
        if (!TransferService.validId(action.getOperation())) {
            throw new IllegalArgumentException("操作身份不是 UUID");
        }
        if (!action.getUnknownFields().asMap().isEmpty()
                || !action.getStoreCursor().getUnknownFields().asMap().isEmpty()
                || !action.getPlayerSlot().getUnknownFields().asMap().isEmpty()
                || !action.getTransfer().getUnknownFields().asMap().isEmpty()
                || !action.getTransfer().getBag().getUnknownFields().asMap().isEmpty()
                || !action.getTransfer().getSlot().getUnknownFields().asMap().isEmpty()
                || !action.getTransfer().getBody().getUnknownFields().asMap().isEmpty()
                || !action.getTransfer().getDestination().getUnknownFields().asMap().isEmpty()
                || !action.getTransfer().getBodyDestination().getUnknownFields().asMap().isEmpty()
                || !action.getOpenScene().getUnknownFields().asMap().isEmpty()) {
            throw new IllegalArgumentException("请求包含未定义字段");
        }
        if (action.getActionCase() == ActionRequest.ActionCase.ACTION_NOT_SET) {
            throw new IllegalArgumentException("缺少操作");
        }
    }

    private void requireCursor(ActionRequest action) {
        // 指纹来自真实光标，而不是客户端声明的物品类型或数量。
        var fingerprint = StackCodec.fingerprint(handler.getCarried());
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
        // tick 只跟踪菜单生命周期，转移在请求处理时已经结束。
        if (subscribed) {
            refreshMenu();
        }
    }

    private void playCompletion(ItemStack stack) {
        // 每个成功的新操作只播放一次材质声；幂等重试只重发收据。
        var material = VolumeServer.RULES.material(stack);
        var sound = BuiltInRegistries.SOUND_EVENT.get(new ResourceLocation(material.sound()));
        connection.context().playNotifySound(sound, SoundSource.PLAYERS, material.volume(), material.pitch());
    }

    private void refreshMenu() {
        // 更换原版页面会失效旧菜单身份和收据，不能重放到新页面。
        var player = connection.context();
        AbstractContainerMenu next = player.containerMenu;
        if (!VanillaMenus.supports(next)
                || !player.isAlive()
                || player.isSpectator()
                || !next.stillValid(player)) {
            next = null;
        }
        if (next == handler) {
            return;
        }
        // 旧页面的收据释放；光标交由原版 closeHandledScreen 归还。
        receipts.clear();
        handler = next;
        menu = next == null ? "" : UUID.randomUUID().toString();
        publish(state("", next == null ? Status.CLOSED : Status.READY, ""));
    }

    private MenuState state(String operation, Status status, String detail) {
        return MenuState.newBuilder()
                .setMenu(menu)
                .setSyncId(handler == null ? 0 : handler.containerId)
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
        // 即时事务已经结束，断线仅释放会话收据。
        receipts.clear();
        VanillaServer.forget(this);
    }

    private record Receipt(ActionRequest request, MenuState response) {}

}
