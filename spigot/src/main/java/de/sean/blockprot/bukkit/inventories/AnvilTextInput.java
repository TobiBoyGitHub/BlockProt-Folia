/*
 * Copyright (C) 2021-2026 spnda
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
package de.sean.blockprot.bukkit.inventories;

import de.sean.blockprot.bukkit.BlockProt;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.view.AnvilView;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Text input through a vanilla anvil menu (Paper's {@link MenuType#ANVIL}); replaces AnvilGUI,
 * which needs NMS. Must be registered as a listener once.
 */
public final class AnvilTextInput implements Listener {
    private static final class Session {
        final Inventory top;
        final String defaultText;
        final Function<String, Inventory> onComplete;
        boolean done = false;

        Session(Inventory top, String defaultText, Function<String, Inventory> onComplete) {
            this.top = top;
            this.defaultText = defaultText;
            this.onComplete = onComplete;
        }
    }

    private static final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    /**
     * Opens an anvil for the player. {@code onComplete} gets the entered text and returns the
     * inventory to open next, or null to just close. Runs on the player's region thread.
     */
    public static void open(
        @NotNull final Player player,
        @NotNull final String title,
        @NotNull final String defaultText,
        @NotNull final Function<String, Inventory> onComplete
    ) {
        final AnvilView view = MenuType.ANVIL.builder()
            .checkReachable(false)
            .title(Component.text(title))
            .build(player);
        if (view == null) return;

        view.getTopInventory().setItem(0, namedPaper(defaultText));
        // Opening closes any previous menu first; that close event can't touch this session,
        // as it is compared by inventory.
        sessions.put(player.getUniqueId(), new Session(view.getTopInventory(), defaultText, onComplete));
        player.openInventory(view);
    }

    private static ItemStack namedPaper(@NotNull final String name) {
        final var item = new ItemStack(Material.PAPER);
        item.editMeta(meta -> meta.displayName(Component.text(name)));
        return item;
    }

    private static Session sessionOf(@NotNull final Inventory top, @NotNull final UUID player) {
        final var session = sessions.get(player);
        return session != null && session.top == top ? session : null;
    }

    @EventHandler
    public void onPrepare(@NotNull final PrepareAnvilEvent event) {
        final var session = sessionOf(event.getInventory(), event.getView().getPlayer().getUniqueId());
        if (session == null) return;

        var text = event.getView().getRenameText();
        if (text == null || text.isEmpty()) text = session.defaultText;
        event.setResult(namedPaper(text));
        // Never actually charged: every click in the menu is cancelled below.
        event.getView().setRepairCost(1);
    }

    @EventHandler
    public void onClick(@NotNull final InventoryClickEvent event) {
        final var player = (Player) event.getWhoClicked();
        final var session = sessionOf(event.getView().getTopInventory(), player.getUniqueId());
        if (session == null) return;

        event.setCancelled(true);
        if (event.getRawSlot() != 2 || session.done) return;

        session.done = true;
        var text = ((AnvilView) event.getView()).getRenameText();
        if (text == null || text.isEmpty()) text = session.defaultText;
        final var entered = text;

        // Swapping menus inside the click event is unreliable, so do it on the next tick.
        player.getScheduler().run(BlockProt.getInstance(), task -> {
            final var next = session.onComplete.apply(entered);
            if (next != null) {
                player.openInventory(next);
            } else {
                player.closeInventory();
                InventoryState.remove(player.getUniqueId());
            }
        }, null);
    }

    @EventHandler
    public void onDrag(@NotNull final InventoryDragEvent event) {
        if (sessionOf(event.getView().getTopInventory(), event.getWhoClicked().getUniqueId()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(@NotNull final InventoryCloseEvent event) {
        final var id = event.getPlayer().getUniqueId();
        final var session = sessionOf(event.getView().getTopInventory(), id);
        if (session == null) return;

        // Fires before vanilla hands the anvil contents back to the player; clear them or the
        // placeholder paper becomes a real item.
        session.top.clear();
        sessions.remove(id, session);
    }
}
