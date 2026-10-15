package dev.kizuna.bs3d.vanilla.protocol;

import com.google.protobuf.Message;

import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.*;
import dev.kizuna.inventoryui.protocol.pb.WireProtocol;

import java.util.Map;

/** 附属契约复用 Framework wire v2；业务字段的唯一来源为 vanilla.proto。 */
public final class VanillaMessages {
    public static final String MODULE = "bs3d_vanilla:menus";
    public static final String SUBSCRIBE = "bs3d_vanilla:subscribe";
    public static final String ACTION = "bs3d_vanilla:action";
    public static final String STATE = "bs3d_vanilla:state";
    public static final int VERSION = 1;

    private VanillaMessages() {}

    public static Map<String, WireProtocol.Capability> capabilities() {
        return Map.of(
                SUBSCRIBE,
                        capability(
                                SubscribeRequest.getDefaultInstance(),
                                WireProtocol.Direction.CLIENT_TO_SERVER),
                ACTION,
                        capability(
                                ActionRequest.getDefaultInstance(),
                                WireProtocol.Direction.CLIENT_TO_SERVER),
                STATE,
                        capability(
                                MenuState.getDefaultInstance(),
                                WireProtocol.Direction.SERVER_TO_CLIENT));
    }

    private static WireProtocol.Capability capability(
            Message sample, WireProtocol.Direction direction) {
        // 可选附属缺失时保留原版页面，不阻断基础背包协商。
        return WireProtocol.Capability.newBuilder()
                .setModule(MODULE)
                .setVersion(VERSION)
                .setRequired(false)
                .setDirection(direction)
                .setPayloadType(sample.getDescriptorForType().getFullName())
                .build();
    }
}
