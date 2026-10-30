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
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.GameMode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;

/** 隔离世界的真实原版方块和物品夹具，不打入生产 JAR。 */
public final class VanillaFixture implements ModInitializer {
    private static final int FIXTURE_SLOT = 8;
    private static final int DISTANCE_BLOCKS = 2;
    private static final double BLOCK_CENTER = 0.5;
    private static final int PLATFORM_RADIUS = 3;
    private static final int PLATFORM_Y = 64;
    private static final int OPERATOR_LEVEL = 2;
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
                            CommandManager.literal("vanilla-fixture")
                                    .requires(source -> source.hasPermissionLevel(OPERATOR_LEVEL));
                    command.then(
                            CommandManager.literal("craft")
                                    .executes(
                                            context ->
                                                    open(
                                                            context.getSource().getPlayerOrThrow(),
                                                            Blocks.CRAFTING_TABLE)));
                    command.then(
                            CommandManager.literal("furnace")
                                    .executes(
                                            context ->
                                                    open(
                                                            context.getSource().getPlayerOrThrow(),
                                                            Blocks.FURNACE)));
                    command.then(
                            CommandManager.literal("chest")
                                    .executes(
                                            context ->
                                                    open(
                                                            context.getSource().getPlayerOrThrow(),
                                                            Blocks.CHEST)));
                    dispatcher.register(command);
                    dispatcher.register(CommandManager.literal("vanilla-fixture-ground")
                            .requires(source -> source.hasPermissionLevel(OPERATOR_LEVEL))
                            .executes(context -> ground(context.getSource().getPlayerOrThrow())));
                });
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> {
                    if (server.isDedicated()) {
                        server.execute(() -> server.stop(false /* waitForShutdown */));
                    }
                });
    }

    private static void seed(ServerPlayerEntity player) {
        // 固定平台保证地面交互距离稳定，只为首次进入的玩家放入测试物品。
        var server = player.getServer();
        server.getPlayerManager().addToOperators(player.getGameProfile());
        var world = player.getServerWorld();
        for (int x = -PLATFORM_RADIUS; x <= PLATFORM_RADIUS; x++) {
            for (int z = -PLATFORM_RADIUS; z <= PLATFORM_RADIUS; z++) {
                world.setBlockState(
                        new BlockPos(x, PLATFORM_Y, z),
                        Blocks.STONE.getDefaultState());
            }
        }
        player.teleport(world, BLOCK_CENTER, PLATFORM_Y + 1, BLOCK_CENTER, 0, 0);
        player.changeGameMode(GameMode.SURVIVAL);
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
                        .setStackNbt(StackCodec.encode(new ItemStack(Items.OAK_LOG, LOG_COUNT)))
                        .build();
        ContainerItems.setContents(pouch, List.of(logs));
        var slots = account.inventory();
        for (int i = 0; i < slots.size(); i++) {
            slots.set(i, ItemStack.EMPTY);
        }
        slots.set(0, new ItemStack(Items.IRON_INGOT, INGOT_COUNT));
        var worn = WornContainer.newBuilder()
                .setMount(Mount.WAIST_RIGHT_FRONT)
                .setStackNbt(StackCodec.encode(pouch))
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

    private static int open(ServerPlayerEntity player, Block block) {
        // 使用真实方块的 NamedScreenHandlerFactory，距离与燃烧均按原版实现验证。
        var pos = player.getBlockPos().east(DISTANCE_BLOCKS);
        var world = player.getServerWorld();
        world.setBlockState(pos, Blocks.AIR.getDefaultState());
        var state = block.getDefaultState();
        world.setBlockState(pos, state);
        if (world.getBlockEntity(pos) instanceof Inventory inventory) {
            if (block == Blocks.FURNACE) {
                inventory.setStack(0, new ItemStack(Items.RAW_IRON));
                inventory.setStack(1, new ItemStack(Items.COAL));
            } else {
                inventory.setStack(0, new ItemStack(Items.STONE, STONE_COUNT));
            }
            inventory.markDirty();
        }
        player.openHandledScreen(state.createScreenHandlerFactory(world, pos));
        return 1;
    }

    private static int ground(ServerPlayerEntity player) {
        // 先用真实放置事务把空背包落地，再打开箱子验证地面模型与原版槽位互通。
        player.closeHandledScreen();
        int selected = player.getInventory().selectedSlot;
        player.getInventory().selectedSlot = FIXTURE_SLOT;
        player.getInventory().setStack(FIXTURE_SLOT, ContainerItems.create(ContainerItems.POUCH));
        var support = player.getBlockPos().south(DISTANCE_BLOCKS).down();
        var hit = new BlockHitResult(Vec3d.ofCenter(support).add(0, BLOCK_CENTER, 0),
                Direction.UP, support, false /* insideBlock */);
        boolean sneaking = player.isSneaking();
        player.setSneaking(true /* sneaking */);
        try {
            var result = GroundInventory.get(player.getServer()).place(
                    new ItemUsageContext(player, Hand.MAIN_HAND, hit));
            if (!result.isAccepted()) {
                throw new IllegalStateException("地面背包夹具放置失败");
            }
        } finally {
            player.setSneaking(sneaking);
            player.getInventory().selectedSlot = selected;
        }
        open(player, Blocks.CHEST);
        player.currentScreenHandler.getSlot(0).setStack(new ItemStack(Items.IRON_INGOT));
        player.currentScreenHandler.syncState();
        return 1;
    }
}
