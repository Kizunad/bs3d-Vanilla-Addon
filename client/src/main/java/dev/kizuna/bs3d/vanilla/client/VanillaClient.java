package dev.kizuna.bs3d.vanilla.client;

import com.google.protobuf.Message;

import dev.kizuna.backpack3d.client.VolumeModule;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.bs3d.vanilla.protocol.VanillaMessages;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.*;
import dev.kizuna.inventoryui.client.*;
import dev.kizuna.inventoryui.protocol.UiMessageRouter;
import dev.kizuna.inventoryui.protocol.UiWire;
import dev.kizuna.inventoryui.registry.FrameworkCatalog;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;

import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** 客户端只发送引用与指纹，光标栈和槽位内容始终由服务器同步。 */
public final class VanillaClient implements ClientModule {
    private static VanillaClient instance;
    private VolumeModule volume;
    private ClientRuntime runtime;
    private MenuState state = MenuState.getDefaultInstance();
    private String pending = "";
    private String cancelling = "";
    private String error = "";
    private long generation;

    public static VanillaClient instance() {
        return instance;
    }

    public VolumeModule volume() {
        return volume;
    }

    public MenuState state() {
        return state;
    }

    public boolean busy() {
        return !pending.isEmpty() || state.getStatus() == Status.RUNNING;
    }

    public String detail() {
        return error.isEmpty() ? state.getDetail() : error;
    }

    @Override
    public FrameworkCatalog.Module definition() {
        return FrameworkCatalog.Module.of(VanillaMessages.MODULE, Set.of("bs3d:inventory"));
    }

    @Override
    public void register(ClientBindings bindings) {
        // 取 Fabric 已创建的 BS3D 实例，不能 new 第二份模块、快照或模型注册表。
        instance = this;
        volume =
                FabricLoader.getInstance()
                        .getEntrypoints("kizuna_inventory_ui", ClientModule.class)
                        .stream()
                        .filter(VolumeModule.class::isInstance)
                        .map(VolumeModule.class::cast)
                        .findFirst()
                        .orElseThrow();
        bind(
                bindings,
                VanillaMessages.SUBSCRIBE,
                SubscribeRequest.getDefaultInstance(),
                UiMessageRouter.Direction.CLIENT_TO_SERVER,
                null);
        bind(
                bindings,
                VanillaMessages.ACTION,
                ActionRequest.getDefaultInstance(),
                UiMessageRouter.Direction.CLIENT_TO_SERVER,
                null);
        bind(
                bindings,
                VanillaMessages.STATE,
                MenuState.getDefaultInstance(),
                UiMessageRouter.Direction.SERVER_TO_CLIENT,
                this::accept);
        ClientTickEvents.END_CLIENT_TICK.register(
                client -> {
                    // UI 输入和服务端位置锁同时存在；修改客户端不可能缩短服务端计时。
                    if (busy() && client.player != null) {
                        client.player.input.movementForward = 0;
                        client.player.input.movementSideways = 0;
                        client.player.input.jumping = false;
                    }
                });
    }

    private static <T extends Message> void bind(
            ClientBindings bindings,
            String id,
            T sample,
            UiMessageRouter.Direction direction,
            Consumer<T> receiver) {
        @SuppressWarnings("unchecked")
        Class<T> type = (Class<T>) sample.getClass();
        var contract =
                new UiMessageRouter.Contract<>(
                        VanillaMessages.MODULE,
                        id,
                        VanillaMessages.VERSION,
                        direction,
                        type,
                        receiver);
        bindings.message(new UiWire.Contract<>(contract, UiWire.Codec.protobuf(sample), false));
    }

    @Override
    public void protocolReady(ClientRuntime runtime) {
        this.runtime = runtime;
        long current = generation;
        // 缺少服务器附属时保持原版页面；可选能力失败不伪造本地可用库存。
        runtime.protocol()
                .request(
                        VanillaMessages.SUBSCRIBE,
                        SubscribeRequest.getDefaultInstance(),
                        System.currentTimeMillis())
                .thenAccept(
                        result -> {
                            if (current == generation && !result.accepted()) {
                                error = "服务器未启用原版交互附属：" + result.code();
                            }
                        });
    }

    public boolean ready(HandledScreen<?> screen) {
        return runtime != null
                && !state.getMenu().isEmpty()
                && screen.getScreenHandler().syncId == state.getSyncId();
    }

    public boolean action(HandledScreen<?> screen, Consumer<ActionRequest.Builder> fill) {
        // 等到当前页面协商完成再发意图；请求不携带 NBT 或客户端生成的物品数量。
        if (!ready(screen) || busy() || volume.busy()) {
            return false;
        }
        var request = base(screen);
        fill.accept(request);
        pending = request.getOperation();
        cancelling = "";
        error = "";
        send(request.build());
        return true;
    }

    private ActionRequest.Builder base(HandledScreen<?> screen) {
        return ActionRequest.newBuilder()
                .setMenu(state.getMenu())
                .setSyncId(screen.getScreenHandler().syncId)
                .setOperation(UUID.randomUUID().toString())
                .setInventoryRevision(volume.state().getRevision())
                .setCursorFingerprint(
                        StackCodec.fingerprint(screen.getScreenHandler().getCursorStack()));
    }

    public void cancel(HandledScreen<?> screen) {
        // CANCEL 保留原操作身份，不能把取消请求本身误当成另一项正在转移的物品。
        if (ready(screen) && busy()) {
            String operation = pending.isEmpty() ? state.getOperation() : pending;
            if (operation.equals(cancelling)) {
                return;
            }
            cancelling = operation;
            send(base(screen).setCancelOperation(operation).build());
        }
    }

    private void send(ActionRequest request) {
        long current = generation;
        runtime.protocol()
                .request(VanillaMessages.ACTION, request, System.currentTimeMillis())
                .thenAccept(
                        result -> {
                            // 原页面被关掉后不再执行排队的原版点击，失败时也必须清理拖动捕获。
                            if (current == generation
                                    && !result.accepted()
                                    && pending.equals(request.getOperation())) {
                                pending = "";
                                error = "操作未提交：" + result.code();
                                notifyPanel(false);
                            }
                        });
    }

    private void accept(MenuState next) {
        // 页面身份变化先清理旧拖放；迟到的旧操作结果不能落入新页面的槽位。
        if (!next.getMenu().equals(state.getMenu())) {
            pending = "";
            cancelling = "";
            notifyPanel(false);
        }
        state = next;
        if (next.getStatus() == Status.RUNNING || next.getOperation().isEmpty()) {
            return;
        }
        if (next.getOperation().equals(pending)) {
            pending = "";
            notifyPanel(next.getStatus() == Status.COMPLETED);
        }
    }

    private void notifyPanel(boolean completed) {
        // 只通知仍在显示的原版页面，已关闭场景不再执行异步拖放。
        var screen = MinecraftClient.getInstance().currentScreen;
        if (screen instanceof StationHost host && host.stationPanel() != null) {
            host.stationPanel().completed(completed);
        }
    }

    @Override
    public void disconnected(ClientRuntime runtime) {
        // 更换连接身份并清空捕获，上一世界的异步回执不能恢复旧页面。
        generation++;
        pending = "";
        cancelling = "";
        state = MenuState.getDefaultInstance();
        this.runtime = null;
        error = "";
    }
}
