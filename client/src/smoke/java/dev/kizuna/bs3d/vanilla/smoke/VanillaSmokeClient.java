package dev.kizuna.bs3d.vanilla.smoke;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;

import dev.kizuna.backpack3d.client.*;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Container;
import dev.kizuna.bs3d.vanilla.client.*;
import dev.kizuna.bs3d.vanilla.protocol.VanillaMessages;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.*;
import dev.kizuna.inventoryui.client.InventoryUiClient;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.*;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.world.*;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 实际网络、原版 ScreenHandler、输入与渲染验收；测试反射只用于获取场景命中点。 */
public final class VanillaSmokeClient implements ClientModInitializer {
    private static final String WORLD = "vanilla-smoke";
    private static final long TIMEOUT_MILLIS = 240_000;
    private static final int SETTLE_TICKS = 25;

    private enum Stage {
        CONNECT,
        INITIAL,
        OPENED,
        HAND_PLACED,
        CRAFTED,
        OFFHAND,
        BAG_SETTLE,
        CANCELLING,
        REOPEN,
        REOPEN_SETTLE,
        LOGS_PLACED,
        PLANKS_CURSOR,
        STORED,
        REPLAYED,
        FORGED_MENU,
        FORGED_CURSOR,
        FORGED_SLOT,
        FORGED_FIELDS,
        FURNACE,
        FURNACE_CURSOR,
        FURNACE_HAND,
        CHEST,
        CHEST_CURSOR,
        CHEST_STORED,
        SAVING,
        RESTART,
        RESTORED,
        FINISHED
    }

    private Stage stage = Stage.CONNECT;
    private long started;
    private Stage logged;
    private int settle;
    private boolean integrated;
    private String bag;
    private ActionRequest storedRequest;
    private CompletableFuture<Boolean> replay;

    @Override
    public void onInitializeClient() {
        integrated = System.getProperty("vanilla.smoke.mode", "dedicated").equals("integrated");
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(MinecraftClient client) {
        // 每阶段等真实状态后再推进，失败日志保留具体阶段和服务端业务说明。
        if (started == 0) {
            started = System.currentTimeMillis();
        }
        var addon = VanillaClient.instance();
        if (logged != stage) {
            logged = stage;
            System.out.println("VANILLA_SMOKE_STAGE " + stage);
        }
        if (System.currentTimeMillis() - started > TIMEOUT_MILLIS) {
            throw new IllegalStateException(
                    "验收超时："
                            + stage
                            + " / "
                            + addon.state()
                            + " / "
                            + addon.detail()
                            + " protocol="
                            + InventoryUiClient.runtime().protocolError());
        }
        if (client.currentScreen instanceof AccessibilityOnboardingScreen) {
            client.setScreen(new TitleScreen());
        }
        if (stage == Stage.CONNECT
                && client.currentScreen instanceof TitleScreen
                && client.getOverlay() == null) {
            stage = Stage.INITIAL;
            if (integrated) {
                var info =
                        new LevelInfo(
                                WORLD,
                                GameMode.SURVIVAL,
                                false,
                                Difficulty.PEACEFUL,
                                true,
                                new GameRules(),
                                DataConfiguration.SAFE_MODE);
                client.createIntegratedServerLoader()
                        .createAndStart(
                                WORLD,
                                info,
                                new GeneratorOptions(1L, false, false),
                                registry ->
                                        registry.get(RegistryKeys.WORLD_PRESET)
                                                .getOrThrow(WorldPresets.FLAT)
                                                .createDimensionsRegistryHolder());
            } else {
                String address = System.getProperty("vanilla.smoke.address", "127.0.0.1:25584");
                ConnectScreen.connect(
                        new TitleScreen(),
                        client,
                        ServerAddress.parse(address),
                        new ServerInfo("Vanilla smoke", address, false),
                        false);
            }
        }
        if (stage == Stage.RESTART && client.player == null && client.getOverlay() == null) {
            stage = Stage.RESTORED;
            client.createIntegratedServerLoader().start(new TitleScreen(), WORLD);
            return;
        }
        if (client.player == null || addon == null || !addon.volume().ready()) {
            return;
        }
        client.getToastManager().clear();
        var volume = addon.volume();
        var screen = client.currentScreen instanceof HandledScreen<?> handled ? handled : null;
        boolean ready =
                screen != null
                        && addon.ready(screen)
                        && ((StationHost) screen).stationPanel() != null;
        switch (stage) {
            case INITIAL -> {
                if (volume.state().getRevision() != 1) {
                    return;
                }
                require(integrated == client.isInSingleplayer(), "运行模式错误");
                bag =
                        volume.state().getContainersList().stream()
                                .filter(Container::getWorn)
                                .findFirst()
                                .orElseThrow()
                                .getId();
                command(client, "craft");
                stage = Stage.OPENED;
            }
            case OPENED -> {
                if (!ready || ++settle < SETTLE_TICKS) {
                    return;
                }
                require(panel(screen).enabled(), "原版页面没有启用附属场景");
                panel(screen).open(bag);
                dragHand(screen, 0, 1);
                stage = Stage.HAND_PLACED;
            }
            case HAND_PLACED -> {
                if (addon.busy()
                        || screen.getScreenHandler().getSlot(1).getStack().getCount() != 2) {
                    return;
                }
                // 槽位点击有客户端预测，配方结果必须等服务端同步，不能把预测当完成。
                if (screen.getScreenHandler().getSlot(0).getStack().isEmpty()) {
                    return;
                }
                require(client.player.getMainHandStack().isEmpty(), "拖动后主手没有清空");
                require(
                        screen.getScreenHandler().getSlot(0).getStack().isOf(Items.IRON_NUGGET),
                        "原版配方没有计算结果");
                nativeClick(client, screen, 0, SlotActionType.PICKUP);
                stage = Stage.CRAFTED;
            }
            case CRAFTED -> {
                var cursor = screen.getScreenHandler().getCursorStack();
                if (!cursor.isOf(Items.IRON_NUGGET)) {
                    return;
                }
                require(cursor.getCount() == 9, "原版合成结果数量错误");
                require(
                        screen.getScreenHandler().getSlot(1).getStack().getCount() == 1,
                        "原版合成没有正确扣料");
                clickBody(addon, screen, 40);
                stage = Stage.OFFHAND;
            }
            case OFFHAND -> {
                if (addon.busy() || !client.player.getOffHandStack().isOf(Items.IRON_NUGGET)) {
                    return;
                }
                require(client.player.getOffHandStack().getCount() == 9, "合成物没有放入副手");
                nativeClick(client, screen, 1, SlotActionType.QUICK_MOVE);
                settle = 0;
                stage = Stage.BAG_SETTLE;
            }
            case BAG_SETTLE -> {
                if (++settle < SETTLE_TICKS) {
                    return;
                }
                screenshot(client, "crafting.png");
                dragBag(screen, 1, bag);
                stage = Stage.CANCELLING;
            }
            case CANCELLING -> {
                if (addon.state().getStatus() != Status.RUNNING) {
                    return;
                }
                require(volume.state().getRevision() == 1, "计时尚未完成就改了背包");
                require(screen.getScreenHandler().getCursorStack().isEmpty(), "计时尚未完成就改了光标");
                screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0);
                stage = Stage.REOPEN;
            }
            case REOPEN -> {
                if (addon.state().getStatus() != Status.CLOSED) {
                    return;
                }
                require(volume.state().getRevision() == 1, "ESC 取消改变了库存");
                command(client, "craft");
                settle = 0;
                stage = Stage.REOPEN_SETTLE;
            }
            case REOPEN_SETTLE -> {
                if (!ready) {
                    return;
                }
                if (settle++ == 0) {
                    panel(screen).open(bag);
                }
                if (settle < SETTLE_TICKS) {
                    return;
                }
                dragBag(screen, 1, bag);
                stage = Stage.LOGS_PLACED;
            }
            case LOGS_PLACED -> {
                if (addon.busy() || volume.state().getRevision() != 2) {
                    return;
                }
                var logs = screen.getScreenHandler().getSlot(1).getStack();
                if (!logs.isOf(Items.OAK_LOG)) {
                    return;
                }
                if (!screen.getScreenHandler().getSlot(0).getStack().isOf(Items.OAK_PLANKS)) {
                    return;
                }
                require(logs.getCount() == 4, "背包拖放丢失物品");
                nativeClick(client, screen, 0, SlotActionType.PICKUP);
                stage = Stage.PLANKS_CURSOR;
            }
            case PLANKS_CURSOR -> {
                if (!screen.getScreenHandler().getCursorStack().isOf(Items.OAK_PLANKS)) {
                    return;
                }
                require(screen.getScreenHandler().getCursorStack().getCount() == 4, "原版木板配方结果错误");
                addon.action(
                        screen,
                        request -> {
                            request.setStoreCursor(destination(bag, 0));
                            storedRequest = request.build();
                        });
                stage = Stage.STORED;
            }
            case STORED -> {
                if (addon.busy() || volume.state().getRevision() != 3) {
                    return;
                }
                require(screen.getScreenHandler().getCursorStack().isEmpty(), "存入没有清空光标");
                replay =
                        InventoryUiClient.runtime()
                                .protocol()
                                .request(
                                        VanillaMessages.ACTION,
                                        storedRequest,
                                        System.currentTimeMillis())
                                .thenApply(result -> result.accepted());
                stage = Stage.REPLAYED;
            }
            case REPLAYED -> {
                if (!replay.isDone()) {
                    return;
                }
                require(replay.join() && volume.state().getRevision() == 3, "重放重复提交了库存");
                addon.action(
                        screen,
                        request -> request.setMenu(UUID.randomUUID().toString()).setTakeItem(bag));
                stage = Stage.FORGED_MENU;
            }
            case FORGED_MENU -> {
                if (addon.busy()) {
                    return;
                }
                require(addon.state().getStatus() == Status.REJECTED, "接受了伪造页面身份");
                addon.action(
                        screen,
                        request ->
                                request.setCursorFingerprint(ByteString.copyFrom(new byte[32]))
                                        .setTakeItem(bag));
                stage = Stage.FORGED_CURSOR;
            }
            case FORGED_CURSOR -> {
                if (addon.busy()) {
                    return;
                }
                require(addon.state().getStatus() == Status.REJECTED, "接受了伪造光标指纹");
                clickBody(addon, screen, 15);
                stage = Stage.FORGED_SLOT;
            }
            case FORGED_SLOT -> {
                if (addon.busy()) {
                    return;
                }
                require(addon.state().getStatus() == Status.REJECTED, "接受了非身体槽位");
                addon.action(
                        screen,
                        request ->
                                request.setTakeItem(bag)
                                        .setUnknownFields(
                                                UnknownFieldSet.newBuilder()
                                                        .addField(
                                                                100,
                                                                UnknownFieldSet.Field.newBuilder()
                                                                        .addVarint(999)
                                                                        .build())
                                                        .build()));
                stage = Stage.FORGED_FIELDS;
            }
            case FORGED_FIELDS -> {
                if (addon.busy()) {
                    return;
                }
                require(addon.state().getStatus() == Status.REJECTED, "接受了未知业务字段");
                require(volume.state().getRevision() == 3, "非法请求改变了背包");
                screen.close();
                command(client, "furnace");
                stage = Stage.FURNACE;
            }
            case FURNACE -> {
                if (!ready
                        || !screen.getScreenHandler()
                                .getSlot(2)
                                .getStack()
                                .isOf(Items.IRON_INGOT)) {
                    return;
                }
                screenshot(client, "furnace.png");
                nativeClick(client, screen, 2, SlotActionType.PICKUP);
                stage = Stage.FURNACE_CURSOR;
            }
            case FURNACE_CURSOR -> {
                if (!screen.getScreenHandler().getCursorStack().isOf(Items.IRON_INGOT)) {
                    return;
                }
                clickBody(addon, screen, client.player.getInventory().selectedSlot);
                stage = Stage.FURNACE_HAND;
            }
            case FURNACE_HAND -> {
                if (addon.busy()) {
                    return;
                }
                require(client.player.getMainHandStack().isOf(Items.IRON_INGOT), "熔炉产出未进入主手");
                // 原版左键会交换已占用的主手；把换出的木头放回真实空槽，再继续开箱。
                if (!screen.getScreenHandler().getCursorStack().isEmpty()) {
                    var empty =
                            screen.getScreenHandler().slots.stream()
                                    .filter(
                                            slot ->
                                                    slot.inventory == client.player.getInventory()
                                                            && !slot.hasStack())
                                    .findFirst()
                                    .orElseThrow();
                    nativeClick(client, screen, empty.id, SlotActionType.PICKUP);
                    return;
                }
                screen.close();
                command(client, "chest");
                stage = Stage.CHEST;
            }
            case CHEST -> {
                if (!ready || !screen.getScreenHandler().getSlot(0).getStack().isOf(Items.STONE)) {
                    return;
                }
                panel(screen).open(bag);
                nativeClick(client, screen, 0, SlotActionType.PICKUP);
                stage = Stage.CHEST_CURSOR;
            }
            case CHEST_CURSOR -> {
                if (!screen.getScreenHandler().getCursorStack().isOf(Items.STONE)) {
                    return;
                }
                addon.action(screen, request -> request.setStoreCursor(destination(bag, 1)));
                stage = Stage.CHEST_STORED;
            }
            case CHEST_STORED -> {
                if (addon.busy() || volume.state().getRevision() != 4) {
                    return;
                }
                require(screen.getScreenHandler().getSlot(0).getStack().isEmpty(), "箱子来源未清空");
                require(screen.getScreenHandler().getCursorStack().isEmpty(), "箱子物品未存回背包");
                requireStored(volume);
                screenshot(client, "chest.png");
                screen.close();
                settle = 0;
                stage = Stage.SAVING;
            }
            case SAVING -> {
                if (++settle < SETTLE_TICKS) {
                    return;
                }
                if (integrated) {
                    stage = Stage.RESTART;
                    disconnect(client);
                } else {
                    finish(
                            client,
                            "dedicated: model drag, vanilla craft consumption, offhand, timed"
                                    + " cancel/commit, replay, forged menu/cursor/slot/fields"
                                    + " rejection, furnace, chest, protobuf passed");
                }
            }
            case RESTORED -> {
                if (volume.state().getRevision() != 4) {
                    return;
                }
                requireStored(volume);
                finish(
                        client,
                        "integrated: all dedicated checks plus same-world restart and saved"
                                + " backpack contents passed");
            }
            default -> {}
        }
    }

    private static BagDestination destination(String bag, int y) {
        return BagDestination.newBuilder().setContainer(bag).setY(y).build();
    }

    private static void requireStored(VolumeModule volume) {
        // 确认真实背包里恰好有四块木板和五块石头，不靠界面文字判断成功。
        int planks = 0;
        int stone = 0;
        for (var item : volume.state().getItemsList()) {
            var stack = volume.stack(item.getId());
            if (stack.isOf(Items.OAK_PLANKS)) {
                planks += stack.getCount();
            }
            if (stack.isOf(Items.STONE)) {
                stone += stack.getCount();
            }
        }
        require(planks == 4 && stone == 5, "背包最终内容错误");
    }

    private static void clickBody(VanillaClient addon, HandledScreen<?> screen, int slot) {
        var stack = MinecraftClient.getInstance().player.getInventory().getStack(slot);
        addon.action(
                screen,
                request ->
                        request.setPlayerSlot(
                                PlayerSlot.newBuilder()
                                        .setSlot(slot)
                                        .setFingerprint(StackCodec.fingerprint(stack))));
    }

    private static void dragHand(HandledScreen<?> screen, int playerSlot, int target) {
        WornPreview preview = readField(panel(screen), "player");
        var box = preview.slotBounds(playerSlot);
        require(box != null, "玩家部位没有投影");
        for (int y = (int) box[1]; y <= box[3]; y++) {
            for (int x = (int) box[0]; x <= box[2]; x++) {
                if (preview.slotAt(x, y) == playerSlot && preview.pick(x, y).isEmpty()) {
                    drag(screen, x, y, target);
                    return;
                }
            }
        }
        throw new IllegalStateException("没有可命中的手部像素");
    }

    private static void dragBag(HandledScreen<?> screen, int target, String bag) {
        Map<String, InteriorRenderer> interiors = readField(panel(screen), "interiors");
        var interior = interiors.get(bag);
        require(interior != null, "内景尚未渲染");
        var host = (StationHost) screen;
        var area =
                StationLayout.beside(
                                screen.width,
                                screen.height,
                                host.stationLeft(),
                                host.stationWidth())
                        .interior();
        for (int y = area.y(); y < area.bottom(); y++) {
            for (int x = area.x(); x < area.right(); x++) {
                if (!interior.itemAt(x, y).isEmpty()) {
                    drag(screen, x, y, target);
                    return;
                }
            }
        }
        throw new IllegalStateException("内景物品没有可命中像素");
    }

    private static void drag(HandledScreen<?> screen, double x, double y, int target) {
        // 经过真实 Screen 输入入口，验证 Mixin 捕获和异步完成后的落点投递。
        var host = (StationHost) screen;
        var slot = screen.getScreenHandler().getSlot(target);
        double endX = host.stationLeft() + slot.x + 8;
        double endY = host.stationTop() + slot.y + 8;
        screen.mouseClicked(x, y, 0);
        screen.mouseDragged(endX, endY, 0, endX - x, endY - y);
        screen.mouseReleased(endX, endY, 0);
    }

    @SuppressWarnings("unchecked")
    private static <T> T readField(Object object, String name) {
        // 只在夹具中读取投影，不向生产公开专用测试方法。
        try {
            var field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(object);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static StationPanel panel(HandledScreen<?> screen) {
        return ((StationHost) screen).stationPanel();
    }

    private static void nativeClick(
            MinecraftClient client, HandledScreen<?> screen, int slot, SlotActionType type) {
        client.interactionManager.clickSlot(
                screen.getScreenHandler().syncId, slot, 0, type, client.player);
    }

    private static void command(MinecraftClient client, String name) {
        client.getNetworkHandler().sendChatCommand("vanilla-fixture " + name);
    }

    private static void screenshot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(
                client.runDirectory, name, client.getFramebuffer(), message -> {});
    }

    private void finish(MinecraftClient client, String result) {
        try {
            Files.writeString(client.runDirectory.toPath().resolve("vanilla-ok.txt"), result);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        stage = Stage.FINISHED;
        disconnect(client);
        client.scheduleStop();
    }

    private static void disconnect(MinecraftClient client) {
        if (client.world != null) {
            client.world.disconnect();
        }
        client.disconnect(new TitleScreen());
    }

    private static void require(boolean condition, String reason) {
        if (!condition) {
            throw new IllegalStateException(reason);
        }
    }
}
