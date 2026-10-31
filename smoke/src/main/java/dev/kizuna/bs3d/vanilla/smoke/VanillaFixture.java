package dev.kizuna.bs3d.vanilla.smoke;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Item;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WornContainer;
import dev.kizuna.backpack3d.server.PlayerAccount;
import dev.kizuna.backpack3d.server.GroundInventory;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 隔离世界的真实原版方块和物品夹具，不打入生产 JAR。 */
public final class VanillaFixture implements ModInitializer {
    private static final int FIXTURE_SLOT = 8;
    private static final int DISTANCE_BLOCKS = 2;
    private static final double BLOCK_CENTER = 0.5;
    private static final int PLATFORM_RADIUS = 3;
    private static final int PLATFORM_Y = 64;
    private static final int LOG_COUNT = 4;
    private static final int INGOT_COUNT = 2;
    private static final int STONE_COUNT = 5;

    @Override
    public void onInitialize() {
        // 仅在隔离测试世界登记夹具命令，不改变生产模组的初始化。
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> seed(handler.player));
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registries, environment) -> {
                    var command =
                            Commands.literal("vanilla-fixture")
                                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
                    command.then(
                            Commands.literal("craft")
                                    .executes(
                                            context ->
                                                    open(
                                                            context.getSource().getPlayerOrException(),
                                                            Blocks.CRAFTING_TABLE)));
                    command.then(
                            Commands.literal("furnace")
                                    .executes(
                                            context ->
                                                    open(
                                                            context.getSource().getPlayerOrException(),
                                                            Blocks.FURNACE)));
                    command.then(
                            Commands.literal("chest")
                                    .executes(
                                            context ->
                                                    open(
                                                            context.getSource().getPlayerOrException(),
                                                            Blocks.CHEST)));
                    dispatcher.register(command);
                    dispatcher.register(Commands.literal("vanilla-fixture-ground")
                            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                            .executes(context -> ground(context.getSource().getPlayerOrException())));
                });
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> {
                    if (server.isDedicatedServer()) {
                        server.execute(() -> server.halt(false /* waitForShutdown */));
                    }
                });
    }

    private static void seed(ServerPlayer player) {
        // 固定平台保证地面交互距离稳定，只为首次进入的玩家放入测试物品。
        var world = player.level();
        world.getServer().getPlayerList().op(player.nameAndId());
        for (int x = -PLATFORM_RADIUS; x <= PLATFORM_RADIUS; x++) {
            for (int z = -PLATFORM_RADIUS; z <= PLATFORM_RADIUS; z++) {
                world.setBlockAndUpdate(
                        new BlockPos(x, PLATFORM_Y, z),
                        Blocks.STONE.defaultBlockState());
            }
        }
        player.teleportTo(world, BLOCK_CENTER, PLATFORM_Y + 1, BLOCK_CENTER,
                Set.of() /* 绝对坐标 */, 0, 0, true /* 镜头回到自身 */);
        player.setGameMode(GameType.SURVIVAL);
        var account = new PlayerAccount(player);
        if (account.data().getRevision() > 0) {
            return;
        }
        var pouch = ContainerItems.create(ContainerItems.POUCH);
        String bag = ContainerItems.identity(pouch);
        var logs =
                Item.newBuilder()
                        .setId(UUID.randomUUID().toString())
                        .setContainer(bag)
                        .setSize(Volumes.UNIT)
                        .setBaseSize(Volumes.UNIT)
                        .setStackNbt(StackCodec.encode(new ItemStack(Items.OAK_LOG, LOG_COUNT), player.registryAccess()))
                        .build();
        ContainerItems.setContents(pouch, List.of(logs));
        var slots = account.inventory();
        for (int i = 0; i < slots.size(); i++) {
            slots.set(i, ItemStack.EMPTY);
        }
        slots.set(0, new ItemStack(Items.IRON_INGOT, INGOT_COUNT));
        var worn = WornContainer.newBuilder()
                .setMount(Mount.WAIST_RIGHT_FRONT)
                .setStackNbt(StackCodec.encode(pouch, player.registryAccess()))
                .build();
        var data =
                account.data().toBuilder()
                        .setRevision(1)
                        .clearWorn()
                        .addWorn(worn)
                        .build();
        try {
            account.commit(data, slots);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static int open(ServerPlayer player, Block block) {
        // 使用真实方块的 MenuProvider，距离与燃烧均按原版实现验证。
        var pos = player.blockPosition().east(DISTANCE_BLOCKS);
        var world = player.level();
        world.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        var state = block.defaultBlockState();
        world.setBlockAndUpdate(pos, state);
        if (world.getBlockEntity(pos) instanceof Container inventory) {
            if (block == Blocks.FURNACE) {
                inventory.setItem(0, new ItemStack(Items.RAW_IRON));
                inventory.setItem(1, new ItemStack(Items.COAL));
            } else {
                inventory.setItem(0, new ItemStack(Items.STONE, STONE_COUNT));
            }
            inventory.setChanged();
        }
        player.openMenu(state.getMenuProvider(world, pos));
        return 1;
    }

    private static int ground(ServerPlayer player) {
        // 先用真实放置事务把空背包落地，再打开箱子验证地面模型与原版槽位互通。
        player.closeContainer();
        int selected = player.getInventory().getSelectedSlot();
        player.getInventory().setSelectedSlot(FIXTURE_SLOT);
        player.getInventory().setItem(FIXTURE_SLOT, ContainerItems.create(ContainerItems.POUCH));
        var support = player.blockPosition().south(DISTANCE_BLOCKS).below();
        var hit = new BlockHitResult(Vec3.atCenterOf(support).add(0, BLOCK_CENTER, 0),
                Direction.UP, support, false /* insideBlock */);
        boolean sneaking = player.isShiftKeyDown();
        player.setShiftKeyDown(true /* sneaking */);
        try {
            var result = GroundInventory.get(player.level().getServer()).place(
                    new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
            if (!result.consumesAction()) {
                throw new IllegalStateException("地面背包夹具放置失败");
            }
        } finally {
            player.setShiftKeyDown(sneaking);
            player.getInventory().setSelectedSlot(selected);
        }
        open(player, Blocks.CHEST);
        player.containerMenu.getSlot(0).setByPlayer(new ItemStack(Items.IRON_INGOT));
        player.containerMenu.sendAllDataToRemote();
        return 1;
    }
}
