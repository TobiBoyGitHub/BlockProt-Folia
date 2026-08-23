/*
 * Copyright (C) 2021-2025 spnda
 * This file is part of BlockProt <https://github.com/spnda/BlockProt>.
 *
 * BlockProt is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BlockProt is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with BlockProt.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.sean.blockprot.bukkit;

import de.sean.blockprot.bukkit.events.BlockAccessMenuEvent;
import de.sean.blockprot.bukkit.integrations.PluginIntegration;
import de.sean.blockprot.bukkit.inventories.BlockLockInventory;
import de.sean.blockprot.bukkit.inventories.InventoryState;
import de.sean.blockprot.bukkit.nbt.BlockNBTHandler;
import de.sean.blockprot.bukkit.nbt.FriendHandler;
import de.sean.blockprot.bukkit.nbt.PlayerSettingsHandler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * BlockProt's class for external API methods.
 * <p>
 * <b>Threading (Folia):</b> BlockProt only supports Folia. Every method that
 * touches a {@link Block} or a {@link Player} must run on the region thread
 * that owns that block or player. Methods named {@code *Sync} enforce this at
 * runtime and throw {@link IllegalStateException} if called from the wrong
 * thread. If you're calling this API from a thread that doesn't already own
 * the relevant region (e.g. from your own async task, or from a differently
 * scheduled callback), use the non-{@code Sync} callback-based variants
 * instead — they schedule the work on the correct region/entity scheduler
 * for you and hand you the result via a {@link Consumer}.
 *
 * @author spnda
 * @since 0.4.7
 */
public final class BlockProtAPI {
    @Nullable
    static BlockProtAPI instance;

    private final BlockProt blockProt;

    BlockProtAPI(BlockProt blockProt) {
        this.blockProt = blockProt;
        instance = this;
    }

    /**
     * Get the current instance of this API. Remember to
     * softdepend this plugin, otherwise this API will not be
     * initialized and this will return null.
     *
     * @return The instance or null if not initialized
     * @since 0.4.7
     */
    @Nullable
    public static BlockProtAPI getInstance() {
        return instance;
    }

    /**
     * Registers a integration. This automatically calls {@link PluginIntegration#enable()}
     * to load the plugin and also adds them to a internal list (accessible through
     * {@link #getIntegrations()}) which is used for friend handling. This does not ensure
     * that there are no duplicates of a integration, so please beware to only register your
     * integration once.
     * <p>
     * This method itself touches no world state and may be called from any thread,
     * but {@link PluginIntegration#enable()} may not — check the integration's own docs.
     *
     * @param integration The integration to register.
     * @since 0.4.7
     */
    public void registerIntegration(@NotNull final PluginIntegration integration) {
        this.blockProt.registerIntegration(integration);
    }

    /**
     * Get a list of all integrations that have been registered using
     * {@link #registerIntegration(PluginIntegration)}. There might possibly
     * be duplicates of some integrations, if the author of those registered them
     * more than once.
     *
     * @return A unmodifiable list of all registered integrations.
     * @since 0.4.7
     */
    @NotNull
    public List<PluginIntegration> getIntegrations() {
        return this.blockProt.getIntegrations();
    }

    /**
     * Get the handler for given block. This is used to get information
     * about the owner, the friends and the redstone protection.
     * <p>
     * Must be called from the region thread that owns {@code block}. If you're
     * not certain of that, use {@link #getBlockHandler(Block, Consumer)} instead.
     *
     * @param block The block to get the handler for.
     * @return The {@link BlockNBTHandler} for the given block.
     * @throws IllegalStateException if called from a thread that does not own
     *                                {@code block}'s region.
     * @since 2.0.0
     */
    @NotNull
    public BlockNBTHandler getBlockHandlerSync(@NotNull final Block block) {
        requireRegionThread(block.getLocation());
        return new BlockNBTHandler(block);
    }

    /**
     * Get the handler for given block, safe to call from any thread. The
     * {@code callback} is invoked on the region thread that owns {@code block}
     * once scheduled.
     *
     * @param block    The block to get the handler for.
     * @param callback Invoked with the {@link BlockNBTHandler} once we're
     *                 running on the correct region thread. Not invoked at all
     *                 if the region has since been unloaded.
     * @since 2.0.0
     */
    public void getBlockHandler(@NotNull final Block block, @NotNull final Consumer<BlockNBTHandler> callback) {
        Bukkit.getRegionScheduler().run(
            this.blockProt,
            block.getLocation(),
            task -> callback.accept(new BlockNBTHandler(block)));
    }

    /**
     * Get the player settings handler for given player. This is used
     * to retrieve default friends or other globally applicable settings
     * for the player.
     * <p>
     * Must be called from the region thread that currently owns {@code player}.
     * If you're not certain of that, use {@link #getPlayerSettings(Player, Consumer)}
     * instead.
     *
     * @param player The player.
     * @return The {@link PlayerSettingsHandler} for the given player.
     * @throws IllegalStateException if called from a thread that does not own
     *                                {@code player}'s current region.
     * @since 2.0.0
     */
    @NotNull
    public PlayerSettingsHandler getPlayerSettingsSync(@NotNull final Player player) {
        requireRegionThread(player.getLocation());
        return new PlayerSettingsHandler(player);
    }

    /**
     * Get the player settings handler for given player, safe to call from any
     * thread. The {@code callback} is invoked on {@code player}'s entity
     * scheduler once scheduled.
     *
     * @param player   The player.
     * @param callback Invoked with the {@link PlayerSettingsHandler} once we're
     *                 running on the player's entity scheduler. Not invoked at
     *                 all if the player has since left the server (retired).
     * @since 2.0.0
     */
    public void getPlayerSettings(@NotNull final Player player, @NotNull final Consumer<PlayerSettingsHandler> callback) {
        player.getScheduler().run(
            this.blockProt,
            task -> callback.accept(new PlayerSettingsHandler(player)),
            null);
    }

    /**
     * Get the lock inventory for given block and player. This call
     * triggers the {@link BlockAccessMenuEvent} event and checks
     * if it succeeded and what permissions the player has and bases the
     * inventory on that information.
     * <p>
     * Must be called from the region thread that owns {@code block}. Note that
     * this does <b>not</b> open the inventory for {@code player} for you — the
     * caller is responsible for doing so (e.g. via {@code player.openInventory(...)})
     * once back on a thread that owns {@code player}. If you're not certain
     * which thread you're on, use {@link #getLockInventoryForBlock(Block, Player, Consumer)}
     * instead, which handles this handoff for you.
     *
     * @param block  The block the {@code player} is trying to access.
     * @param player The player.
     * @return The inventory or null, if the request was denied, possibly
     * due to permissions.
     * @throws IllegalStateException if called from a thread that does not own
     *                                {@code block}'s region.
     * @since 2.0.0
     */
    @Nullable
    public Inventory getLockInventoryForBlockSync(@NotNull final Block block, @NotNull final Player player) {
        requireRegionThread(block.getLocation());
        return buildLockInventory(block, player);
    }

    /**
     * Get the lock inventory for given block and player, safe to call from any
     * thread. This first computes permissions on the region thread that owns
     * {@code block}, then hands the resulting inventory to {@code callback} on
     * {@code player}'s entity scheduler — opening a {@link Inventory} is a
     * player-owned operation, so it must happen there rather than on the
     * block's region thread.
     *
     * @param block    The block the {@code player} is trying to access.
     * @param player   The player.
     * @param callback Invoked with the inventory, or {@code null} if the
     *                 request was denied (e.g. due to permissions). Not invoked
     *                 at all if the block's region or the player disappear
     *                 before scheduling completes.
     * @since 2.0.0
     */
    public void getLockInventoryForBlock(
        @NotNull final Block block,
        @NotNull final Player player,
        @NotNull final Consumer<Inventory> callback
    ) {
        Bukkit.getRegionScheduler().run(this.blockProt, block.getLocation(), blockTask -> {
            final Inventory inventory = buildLockInventory(block, player);
            player.getScheduler().run(
                this.blockProt,
                playerTask -> callback.accept(inventory),
                null);
        });
    }

    /**
     * Shared implementation for building the lock inventory. Must be called
     * from the region thread that owns {@code block}; does not touch
     * {@code player}-owned state beyond reading permissions, so it is safe to
     * call from the block's region thread even when {@code player} is not
     * currently owned by that same region.
     */
    @Nullable
    private Inventory buildLockInventory(@NotNull final Block block, @NotNull final Player player) {
        final BlockAccessMenuEvent event = new BlockAccessMenuEvent(block, player);
        final String playerUuid = player.getUniqueId().toString();

        final BlockNBTHandler handler = new BlockNBTHandler(block);
        if (player.isOp() || player.hasPermission(Permissions.ADMIN.key())) {
            event.addPermissions(
                    BlockAccessMenuEvent.MenuPermission.LOCK,
                    BlockAccessMenuEvent.MenuPermission.INFO);
        } else if (player.hasPermission(Permissions.INFO.key())) {
            event.addPermission(BlockAccessMenuEvent.MenuPermission.INFO);
        }

        Optional<FriendHandler> friend;
        if (handler.isOwner(playerUuid)) {
            event.addPermissions(
                    BlockAccessMenuEvent.MenuPermission.LOCK,
                    BlockAccessMenuEvent.MenuPermission.INFO,
                    BlockAccessMenuEvent.MenuPermission.MANAGER);
        } else if (handler.isNotProtected()) {
            event.addPermission(BlockAccessMenuEvent.MenuPermission.LOCK);
        } else if ((friend = handler.getFriend(playerUuid)).isPresent() && friend.get().isManager()) {
            event.addPermission(BlockAccessMenuEvent.MenuPermission.MANAGER);
        }

        // Call the event and let the listeners remove/add more permissions.
        Bukkit.getPluginManager().callEvent(event);

        if (event.isCancelled() || event.getPermissions().isEmpty()) {
            return null;
        }

        InventoryState state = new InventoryState(block);
        state.menuPermissions = event.getPermissions();
        state.friendSearchState = InventoryState.FriendSearchState.FRIEND_SEARCH;
        InventoryState.set(player.getUniqueId(), state);

        return new BlockLockInventory().fill(player, block.getType(), handler);
    }

    /**
     * Throws if the calling thread does not own the region that {@code location}
     * belongs to. Used by all {@code *Sync} methods to fail fast instead of
     * silently corrupting state across region threads.
     */
    private static void requireRegionThread(@NotNull final Location location) {
        if (!Bukkit.getServer().isOwnedByCurrentRegion(location)) {
            throw new IllegalStateException(
                "BlockProtAPI: this *Sync method must be called from the region " +
                "thread that owns " + location + ", but the calling thread does " +
                "not own it. Use the corresponding async/callback variant instead, " +
                "or schedule your call yourself via Bukkit.getRegionScheduler() / " +
                "Player#getScheduler().");
        }
    }
}
