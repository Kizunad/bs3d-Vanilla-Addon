package dev.kizuna.bs3d.vanilla.protocol;

import com.google.protobuf.Message;
import dev.kizuna.backpack3d.protocol.RetiredTiming;

import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.ActionRequest;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.MenuState;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.Status;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.SubscribeRequest;
import dev.kizuna.inventoryui.protocol.pb.WireProtocol;

import java.util.Map;

/** 附属契约复用 Framework wire v2；业务字段的唯一来源为 vanilla.proto。 */
public final class VanillaMessages {
    public static final String MODULE = "bs3d_vanilla:menus";
    public static final String SUBSCRIBE = "bs3d_vanilla:subscribe";
    public static final String ACTION = "bs3d_vanilla:action";
    public static final String STATE = "bs3d_vanilla:state";
    public static final int VERSION = 2;
    private static final int RETIRED_ELAPSED_FIELD = 5;
    private static final int RETIRED_DURATION_FIELD = 6;

    public static <T extends Message> T validatePayload(T value) {
        // 保留的旧计时字段必须在客户端分派之前校验，不能静默忽略非零值。
        if (value instanceof MenuState state) {
            RetiredTiming.validate(
                    state, RETIRED_ELAPSED_FIELD, RETIRED_DURATION_FIELD);
            if (state.getStatus() == Status.UNRECOGNIZED) {
                throw new IllegalArgumentException("转移阶段无效");
            }
        }
        return value;
    }

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
