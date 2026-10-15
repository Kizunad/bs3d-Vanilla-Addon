package dev.kizuna.bs3d.vanilla.smoke;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.*;
import dev.kizuna.backpack3d.server.PlayerAccount;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.GameMode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;

/** 隔离世界的真实原版方块和物品夹具，不打入生产 JAR。 */
public final class VanillaFixture implements ModInitializer {
    @Override
    public void onInitialize() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> seed(handler.player));
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registries, environment) -> {
                    var command =
                            CommandManager.literal("vanilla-fixture")
                                    .requires(source -> source.hasPermissionLevel(2));
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
                });
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> {
                    if (server.isDedicated()) {
                        server.execute(() -> server.stop(false));
                    }
                });
    }

    private static void seed(ServerPlayerEntity player) {
        // 固定平台防止生成世界时落地运动打断计时；只为首次进入的玩家放入测试物品。
        var server = player.getServer();
        server.getPlayerManager().addToOperators(player.getGameProfile());
        var world = player.getServerWorld();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                world.setBlockState(
                        new net.minecraft.util.math.BlockPos(x, 64, z),
                        Blocks.STONE.getDefaultState());
            }
        }
        player.teleport(world, 0.5, 65, 0.5, 0, 0);
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
                        .setStackNbt(StackCodec.encode(new ItemStack(Items.OAK_LOG, 4)))
                        .build();
        ContainerItems.setContents(pouch, List.of(logs));
        var slots = account.inventory();
        for (int i = 0; i < slots.size(); i++) {
            slots.set(i, ItemStack.EMPTY);
        }
        slots.set(0, new ItemStack(Items.IRON_INGOT, 2));
        var data =
                account.data().toBuilder()
                        .setRevision(1)
                        .clearWorn()
                        .addWorn(
                                WornContainer.newBuilder()
                                        .setMount(Mount.WAIST_RIGHT_FRONT)
                                        .setStackNbt(StackCodec.encode(pouch)))
                        .build();
        try {
            account.commit(data, slots);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static int open(ServerPlayerEntity player, Block block) {
        // 使用真实方块的 NamedScreenHandlerFactory，距离与燃烧均按原版实现验证。
        var pos = player.getBlockPos().east(2);
        var world = player.getServerWorld();
        world.setBlockState(pos, Blocks.AIR.getDefaultState());
        var state = block.getDefaultState();
        world.setBlockState(pos, state);
        if (world.getBlockEntity(pos) instanceof Inventory inventory) {
            if (block == Blocks.FURNACE) {
                inventory.setStack(0, new ItemStack(Items.RAW_IRON));
                inventory.setStack(1, new ItemStack(Items.COAL));
            } else {
                inventory.setStack(0, new ItemStack(Items.STONE, 5));
            }
            inventory.markDirty();
        }
        player.openHandledScreen(state.createScreenHandlerFactory(world, pos));
        return 1;
    }
}
