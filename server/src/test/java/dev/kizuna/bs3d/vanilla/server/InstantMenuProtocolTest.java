package dev.kizuna.bs3d.vanilla.server;

import com.google.protobuf.UnknownFieldSet;
import dev.kizuna.bs3d.vanilla.protocol.VanillaMessages;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.MenuState;
import dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.Status;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InstantMenuProtocolTest {
    private static final int ELAPSED_FIELD = 5;
    private static final int DURATION_FIELD = 6;

    @Test
    void decodedLegacyTimingRejectsNonzeroAndAllowsExplicitZero() throws Exception {
        // 用已保留字段号编码旧载荷，确认新解码器不会默默接受非零计时。
        for (int number : new int[] {ELAPSED_FIELD, DURATION_FIELD}) {
            MenuState nonzero = decoded(number, 1);
            assertThrows(IllegalArgumentException.class, () -> VanillaMessages.validatePayload(nonzero));
            MenuState zero = decoded(number, 0);
            assertEquals(zero, VanillaMessages.validatePayload(zero));
        }
    }

    private static MenuState decoded(int number, long value) throws Exception {
        // 消息分层构造后再进行真实字节往返，覆盖 reserved 字段的未知字段路径。
        var field = UnknownFieldSet.Field.newBuilder()
                .addVarint(value)
                .build();
        var unknown = UnknownFieldSet.newBuilder()
                .addField(number, field)
                .build();
        var state = MenuState.newBuilder()
                .setStatus(Status.COMPLETED)
                .setUnknownFields(unknown)
                .build();
        return MenuState.parseFrom(state.toByteArray());
    }
}
