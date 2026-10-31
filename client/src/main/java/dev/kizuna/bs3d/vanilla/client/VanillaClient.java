package dev.kizuna.bs3d.vanilla.client;

import com.google.protobuf.Message;

import dev.kizuna.backpack3d.client.VolumeModule;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.bs3d.vanilla.protocol.VanillaMessages;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.ActionRequest;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.MenuState;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.SubscribeRequest;
import dev.kizuna.inventoryui.client.ClientBindings;
import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.ClientRuntime;
import dev.kizuna.inventoryui.protocol.UiMessageRouter;
import dev.kizuna.inventoryui.protocol.UiWire;
import dev.kizuna.inventoryui.registry.FrameworkCatalog;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
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
    private int pendingMenuSlot = -1;
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
        // 客户端只等待终态回执，不维护服务器执行进度。
        return !pending.isEmpty();
    }

    public boolean pendingMenuSlot(int slot) {
        // 原版槽位保留来源占位，收到终态后恢复原版绘制。
        return !pending.isEmpty() && pendingMenuSlot == slot;
    }

    public String detail() {
        return error.isEmpty() ? state.getDetail() : error;
    }

    public void reject(String reason) {
        // 页面在本地拒绝的操作同样显示在反馈行，下一次提交意图时清除。
        error = reason;
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
        UiWire.Codec<T> codec = UiWire.Codec.protobuf(sample, value -> value, VanillaMessages::validatePayload);
        bindings.message(new UiWire.Contract<>(contract, codec, false /* required */));
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

    public boolean ready(AbstractContainerScreen<?> screen) {
        return runtime != null
                && !state.getMenu().isEmpty()
                && screen.getMenu().containerId == state.getSyncId();
    }

    public boolean action(AbstractContainerScreen<?> screen, Consumer<ActionRequest.Builder> fill) {
        // 等到当前页面协商完成再发意图；请求不携带 NBT 或客户端生成的物品数量。
        if (!ready(screen) || busy() || volume.busy()) {
            return false;
        }
        var request = base(screen);
        fill.accept(request);
        pending = request.getOperation();
        var transfer = request.getTransfer();
        if (transfer.hasSlot()) {
            pendingMenuSlot = transfer.getSlot().getSlot();
        }
        if (transfer.hasItem()) {
            volume.showPendingPreview(transfer.getItem());
        } else if (transfer.hasBody()) {
            volume.showPendingPreview(VolumeModule.playerSlotPreviewId(transfer.getBody().getSlot()));
        }
        error = "";
        send(request.build());
        return true;
    }

    private ActionRequest.Builder base(AbstractContainerScreen<?> screen) {
        return ActionRequest.newBuilder()
                .setMenu(state.getMenu())
                .setSyncId(screen.getMenu().containerId)
                .setOperation(UUID.randomUUID().toString())
                .setInventoryRevision(volume.state().getRevision())
                .setCursorFingerprint(
                        StackCodec.fingerprint(screen.getMenu().getCarried()));
    }

    private void send(ActionRequest request) {
        // 同一请求携带完整落点，失败回执负责恢复来源占位。
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
                                clearPendingPreview();
                            }
                        });
    }

    private void accept(MenuState next) {
        // 页面身份变化先清理旧拖放；迟到的旧操作结果不能落入新页面的槽位。
        if (!next.getMenu().equals(state.getMenu())) {
            pending = "";
            clearPendingPreview();
        }
        state = next;
        if (next.getOperation().isEmpty()) {
            return;
        }
        if (next.getOperation().equals(pending)) {
            pending = "";
            clearPendingPreview();
        }
    }

    private void clearPendingPreview() {
        // 终态或页面更换后恢复来源占位，不再执行排队的落点点击。
        volume.clearPendingPreview();
        pendingMenuSlot = -1;
    }

    @Override
    public void disconnected(ClientRuntime runtime) {
        // 更换连接身份并清空捕获，上一世界的异步回执不能恢复旧页面。
        generation++;
        pending = "";
        state = MenuState.getDefaultInstance();
        this.runtime = null;
        error = "";
    }
}
