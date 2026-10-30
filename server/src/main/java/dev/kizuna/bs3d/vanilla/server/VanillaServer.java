package dev.kizuna.bs3d.vanilla.server;

import dev.kizuna.bs3d.vanilla.protocol.VanillaMessages;
import dev.kizuna.inventoryui.server.ServerModuleRegistry;
import dev.kizuna.inventoryui.server.fabric.FabricServerModule;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 单机和专服共用会话与即时库存验证。 */
public final class VanillaServer implements ModInitializer, FabricServerModule {
    private static final Set<MenuSession> SESSIONS = new HashSet<>();

    @Override
    public void onInitialize() {
        // 会话只在所属服务器主线程推进，单机重开不能沿用上一世界的请求。
        ServerTickEvents.END_SERVER_TICK.register(
                server -> {
                    for (var session : List.copyOf(SESSIONS)) {
                        if (session.server() == server) {
                            session.tick();
                        }
                    }
                });
    }

    @Override
    public void register(
            MinecraftServer server, ServerModuleRegistry<ServerPlayerEntity> registry) {
        // 每个 Framework 连接创建独立会话，不能共享玩家的菜单或光标状态。
        registry.register(
                VanillaMessages.MODULE,
                VanillaMessages.capabilities(),
                connection -> {
                    var session = new MenuSession(server, connection);
                    SESSIONS.add(session);
                    return session;
                });
    }

    static void forget(MenuSession session) {
        SESSIONS.remove(session);
    }
}
