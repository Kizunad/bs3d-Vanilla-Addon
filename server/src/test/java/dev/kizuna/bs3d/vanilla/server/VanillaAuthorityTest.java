package dev.kizuna.bs3d.vanilla.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.kizuna.bs3d.vanilla.protocol.VanillaMessages;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.MenuState;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.Status;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.SubscribeRequest;
import dev.kizuna.inventoryui.protocol.pb.WireProtocol.Envelope;
import dev.kizuna.inventoryui.protocol.pb.WireProtocol.Kind;
import dev.kizuna.inventoryui.server.ServerModuleRegistry;
import dev.kizuna.inventoryui.server.ServerProtocol;
import dev.kizuna.inventoryui.server.ServerRouter;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

class VanillaAuthorityTest {
    @Test
    void forgedClientMenuIsRejectedBeforeBusinessDelivery() {
        // 绕过客户端发送检查，把伪造的完成状态直接送进真实服务端路由。
        var handled = new AtomicInteger();
        var replies = new ArrayList<Envelope>();
        var registry = new ServerModuleRegistry<String>();
        registry.register(
                VanillaMessages.MODULE,
                VanillaMessages.capabilities(),
                connection ->
                        request -> {
                            handled.incrementAndGet();
                            return true;
                        });
        registry.freeze();
        var router =
                new ServerRouter<>(
                        registry,
                        () -> "authenticated-player",
                        replies::add,
                        (message, failure) -> {
                            throw new AssertionError(message, failure);
                        });
        router.receive(
                Envelope.newBuilder()
                        .setProtocol(ServerProtocol.VERSION)
                        .setKind(Kind.HELLO)
                        .putAllCapabilities(VanillaMessages.capabilities())
                        .build()
                        .toByteArray());
        var forged = MenuState.newBuilder().setStatus(Status.READY).build();
        var request =
                Envelope.newBuilder()
                        .setProtocol(ServerProtocol.VERSION)
                        .setKind(Kind.REQUEST)
                        .setModule(VanillaMessages.MODULE)
                        .setMessage(VanillaMessages.STATE)
                        .setVersion(VanillaMessages.VERSION)
                        .setRequest(UUID.randomUUID().toString())
                        .setData(forged.toByteString());
        router.receive(request.build().toByteArray());
        assertEquals("INVALID_DIRECTION", replies.get(replies.size() - 1).getStatus());
        assertEquals(0, handled.get());

        // 拒绝伪造后，合法订阅仍可交付，连接没有被错误请求破坏。
        router.receive(
                request.setMessage(VanillaMessages.SUBSCRIBE)
                        .setRequest(UUID.randomUUID().toString())
                        .setData(SubscribeRequest.getDefaultInstance().toByteString())
                        .build()
                        .toByteArray());
        assertEquals("ACCEPTED", replies.get(replies.size() - 1).getStatus());
        assertEquals(1, handled.get());
        router.close();
    }
}
