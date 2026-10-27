package com.taho.dialogcoinflips;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogAction;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogBody;
import io.papermc.paper.registry.data.dialog.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import net.milkbowl.vault.economy.Economy;

import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class DialogCoinflips extends JavaPlugin implements Listener {
    private Economy economy;
    private final Map<UUID, Coinflip> flips = new LinkedHashMap<>();
    private final Map<UUID, Long> pendingCreate = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> inventoryFlip = new ConcurrentHashMap<>();
    private final Set<UUID> resolving = ConcurrentHashMap.newKeySet();
    private int nextId = 1;

    @Override public void onEnable() {
        saveDefaultConfig();
        if (!setupEconomy()) {
            getLogger().severe("Vault economy provider not found. Install Vault and an economy plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("cf")).setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("This command is for players.");
                return true;
            }
            if (args.length == 0) { openList(player); return true; }
            if (args[0].equalsIgnoreCase("create")) {
                if (args.length != 2) { player.sendMessage(Component.text("Usage: /cf create <amount>", NamedTextColor.RED)); return true; }
                double amount;
                try { amount = Double.parseDouble(args[1].replace(",", "")); }
                catch (NumberFormatException ex) { player.sendMessage(Component.text("Enter a valid amount.", NamedTextColor.RED)); return true; }
                if (!Double.isFinite(amount) || amount <= 0 || amount > getConfig().getDouble("max-wager", 1000000000D)) {
                    player.sendMessage(Component.text("That wager is outside the allowed range.", NamedTextColor.RED)); return true;
                }
                if (economy.getBalance(player) < amount) { player.sendMessage(Component.text("You don't have enough money.", NamedTextColor.RED)); return true; }
                pendingCreate.put(player.getUniqueId(), System.currentTimeMillis() + 60000);
                if (isBedrock(player)) openBedrockConfirm(player, amount); else openJavaConfirm(player, amount);
                return true;
            }
            if (args[0].equalsIgnoreCase("cancel")) {
                Coinflip own = flips.values().stream().filter(f -> f.creator.equals(player.getUniqueId())).findFirst().orElse(null);
                if (own == null) player.sendMessage(Component.text("You don't have an active coinflip.", NamedTextColor.RED));
                else { flips.remove(own.id); economy.depositPlayer(player, own.amount); player.sendMessage(Component.text("Coinflip cancelled and wager refunded.", NamedTextColor.GREEN)); openList(player); }
                return true;
            }
            player.sendMessage(Component.text("Usage: /cf [create <amount>|cancel]", NamedTextColor.YELLOW));
            return true;
        });
        getLogger().info("DialogCoinflips enabled.");
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    private boolean isBedrock(Player player) {
        try {
            Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            Method method = api.getMethod("isFloodgatePlayer", UUID.class);
            return Boolean.TRUE.equals(method.invoke(instance, player.getUniqueId()));
        } catch (ReflectiveOperationException ignored) { return false; }
    }

    private String money(double value) {
        return new DecimalFormat("#,##0.##").format(value);
    }

    private ItemStack head(UUID owner, String name, String lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        OfflinePlayer offline = Bukkit.getOfflinePlayer(owner);
        meta.setOwningPlayer(offline);
        meta.displayName(Component.text(name, NamedTextColor.GOLD));
        meta.lore(List.of(Component.text(lore, NamedTextColor.GRAY)));
        item.setItemMeta(meta);
        return item;
    }

    private void openJavaConfirm(Player player, double amount) {
        Dialog dialog = Dialog.create(builder -> builder.empty()
            .base(DialogBase.builder(Component.text("Confirm Coinflip", NamedTextColor.GOLD))
                .body(List.of(DialogBody.plainMessage(Component.text("Create a coinflip for $" + money(amount) + "? Your wager will be held until someone joins or you cancel.", NamedTextColor.WHITE))))
                .build())
            .type(DialogType.confirmation(
                ActionButton.builder(Component.text("Confirm", NamedTextColor.GREEN))
                    .action(DialogAction.customClick((view, audience) -> {
                        if (audience instanceof Player p) { createConfirmed(p, amount); openList(p); }
                    }, ClickCallback.Options.builder().uses(1).build())).build(),
                ActionButton.builder(Component.text("Cancel", NamedTextColor.RED))
                    .action(DialogAction.customClick((view, audience) -> {
                        if (audience instanceof Player p) { pendingCreate.remove(p.getUniqueId()); p.sendMessage(Component.text("Coinflip creation cancelled.", NamedTextColor.YELLOW)); }
                    }, ClickCallback.Options.builder().uses(1).build())).build()
            )));
        player.showDialog(dialog);
    }

    private void openBedrockConfirm(Player player, double amount) {
        Inventory inv = Bukkit.createInventory(null, 27, Component.text("Confirm Coinflip"));
        fill(inv);
        inv.setItem(11, named(Material.LIME_WOOL, "§aConfirm", "§7Create a coinflip for §f$" + money(amount)));
        inv.setItem(15, named(Material.RED_WOOL, "§cCancel", "§7Return without creating"));
        inventoryFlip.put(player.getUniqueId(), new UUID(0, (long) Math.round(amount * 100)));
        player.openInventory(inv);
    }

    private ItemStack named(Material material, String name, String lore) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.displayName(Component.text(name.replace("§a", "").replace("§c", "").replace("§7", "").replace("§f", "")));
        meta.lore(List.of(Component.text(lore.replace("§a", "").replace("§c", "").replace("§7", "").replace("§f", ""))));
        item.setItemMeta(meta);
        return item;
    }

    private void createConfirmed(Player player, double amount) {
        Long expiry = pendingCreate.remove(player.getUniqueId());
        if (expiry == null || expiry < System.currentTimeMillis()) {
            player.sendMessage(Component.text("Confirmation expired. Please try again.", NamedTextColor.RED)); return;
        }
        if (economy.getBalance(player) < amount) {
            player.sendMessage(Component.text("You no longer have enough money.", NamedTextColor.RED)); return;
        }
        if (!economy.withdrawPlayer(player, amount).transactionSuccess()) {
            player.sendMessage(Component.text("Your wager could not be withdrawn.", NamedTextColor.RED)); return;
        }
        Coinflip flip = new Coinflip(UUID.randomUUID(), nextId++, player.getUniqueId(), amount);
        flips.put(flip.id, flip);
        player.sendMessage(Component.text("Coinflip #" + flip.number + " created for $" + money(amount) + ".", NamedTextColor.GREEN));
    }

    private void openList(Player player) {
        if (isBedrock(player)) { openBedrockList(player); return; }
        List<Coinflip> available = flips.values().stream().filter(f -> !f.creator.equals(player.getUniqueId())).limit(20).toList();
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text("Choose a player's coinflip. Each player wagers the listed amount.", NamedTextColor.GRAY)));
        if (available.isEmpty()) body.add(DialogBody.plainMessage(Component.text("No coinflips are available right now.", NamedTextColor.YELLOW)));
        else for (Coinflip flip : available) body.add(DialogBody.plainMessage(Component.text("Head: " + Bukkit.getOfflinePlayer(flip.creator).getName() + "  •  Wager: $" + money(flip.amount), NamedTextColor.GOLD)));
        List<ActionButton> buttons = new ArrayList<>();
        for (Coinflip flip : available) {
            String name = Optional.ofNullable(Bukkit.getOfflinePlayer(flip.creator).getName()).orElse("Player");
            buttons.add(ActionButton.builder(Component.text(name + " — $" + money(flip.amount), NamedTextColor.YELLOW))
                .action(DialogAction.customClick((view, audience) -> { if (audience instanceof Player p) joinFlip(p, flip.id); }, ClickCallback.Options.builder().uses(1).build())).build());
        }
        buttons.add(ActionButton.builder(Component.text("Close", NamedTextColor.RED)).build());
        Dialog dialog = Dialog.create(builder -> builder.empty()
            .base(DialogBase.builder(Component.text("Coinflips", NamedTextColor.GOLD)).body(body).build())
            .type(DialogType.multiAction(buttons).columns(1).exitAction(ActionButton.builder(Component.text("Close")).build())));
        player.showDialog(dialog);
    }

    private void openBedrockList(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, Component.text("Coinflips"));
        fill(inv);
        int slot = 10;
        for (Coinflip flip : flips.values()) {
            if (flip.creator.equals(player.getUniqueId())) continue;
            if (slot >= 44) break;
            String name = Optional.ofNullable(Bukkit.getOfflinePlayer(flip.creator).getName()).orElse("Player");
            inv.setItem(slot, head(flip.creator, name, "Wager: $" + money(flip.amount)));
            slot++;
            if (slot % 9 == 8) slot += 2;
        }
        player.openInventory(inv);
    }

    private void joinFlip(Player player, UUID id) {
        Coinflip flip = flips.get(id);
        if (flip == null) { player.sendMessage(Component.text("That coinflip is no longer available.", NamedTextColor.RED)); openList(player); return; }
        if (flip.creator.equals(player.getUniqueId())) { player.sendMessage(Component.text("You cannot join your own coinflip.", NamedTextColor.RED)); return; }
        if (!resolving.add(id)) return;
        if (economy.getBalance(player) < flip.amount || !economy.withdrawPlayer(player, flip.amount).transactionSuccess()) {
            resolving.remove(id); player.sendMessage(Component.text("You don't have enough money for this wager.", NamedTextColor.RED)); return;
        }
        flips.remove(id);
        Player creator = Bukkit.getPlayer(flip.creator);
        if (creator == null) {
            economy.depositPlayer(player, flip.amount);
            resolving.remove(id);
            player.sendMessage(Component.text("The creator is offline; your wager was refunded.", NamedTextColor.RED));
            openList(player); return;
        }
        runRoulette(flip, player);
    }

    private void runRoulette(Coinflip flip, Player challenger) {
        List<UUID> candidates = List.of(flip.creator, challenger.getUniqueId());
        int ticks = 0;
        int total = getConfig().getInt("roulette-ticks", 40);
        Bukkit.getScheduler().runTaskTimer(this, task -> {
            int elapsed = rouletteProgress.getOrDefault(flip.id, 0);
            UUID shown = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
            for (Player viewer : List.of(challenger, Bukkit.getPlayer(flip.creator))) {
                if (viewer != null && viewer.isOnline()) {
                    viewer.sendTitle(Optional.ofNullable(Bukkit.getOfflinePlayer(shown).getName()).orElse("Player"),
                        "§7Coinflip roulette...", 0, 8, 0);
                    viewer.playSound(viewer.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                }
            }
            elapsed++;
            rouletteProgress.put(flip.id, elapsed);
            if (elapsed >= total) {
                task.cancel(); rouletteProgress.remove(flip.id); resolving.remove(flip.id);
                UUID winnerId = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
                Player winner = Bukkit.getPlayer(winnerId);
                double pot = flip.amount * 2.0;
                if (winner != null) economy.depositPlayer(winner, pot);
                String winnerName = Optional.ofNullable(Bukkit.getOfflinePlayer(winnerId).getName()).orElse("Player");
                for (Player viewer : List.of(challenger, Bukkit.getPlayer(flip.creator))) if (viewer != null && viewer.isOnline()) {
                    viewer.sendTitle("§6§l" + winnerName, "§aWon $" + money(pot), 5, 60, 10);
                    viewer.sendMessage(Component.text("Coinflip winner: " + winnerName + " — payout $" + money(pot), NamedTextColor.GOLD));
                }
            }
        }, 0L, 2L);
    }
    private final Map<UUID, Integer> rouletteProgress = new ConcurrentHashMap<>();

    @EventHandler public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = event.getView().title().toString();
        if (!title.contains("Coinflips") && !title.contains("Confirm Coinflip")) return;
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;
        if (title.contains("Confirm Coinflip")) {
            UUID encoded = inventoryFlip.remove(player.getUniqueId());
            if (clicked.getType() == Material.LIME_WOOL && encoded != null) {
                double amount = encoded.getLeastSignificantBits() / 100.0;
                createConfirmed(player, amount); openBedrockList(player);
            } else if (clicked.getType() == Material.RED_WOOL) {
                pendingCreate.remove(player.getUniqueId()); player.closeInventory(); player.sendMessage(Component.text("Coinflip creation cancelled.", NamedTextColor.YELLOW));
            }
            return;
        }
        if (clicked.getType() == Material.PLAYER_HEAD) {
            SkullMeta meta = (SkullMeta) clicked.getItemMeta();
            if (meta.getOwningPlayer() == null) return;
            UUID creator = meta.getOwningPlayer().getUniqueId();
            Coinflip flip = flips.values().stream().filter(f -> f.creator.equals(creator)).findFirst().orElse(null);
            if (flip != null) { player.closeInventory(); joinFlip(player, flip.id); }
        }
    }

    @EventHandler public void onInventoryClose(InventoryCloseEvent event) {
        String title = event.getView().title().toString();
        if (title.contains("Confirm Coinflip")) inventoryFlip.remove(event.getPlayer().getUniqueId());
    }

    private void fill(Inventory inv) {
        ItemStack pane = named(Material.BLACK_STAINED_GLASS_PANE, " ", " ");
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);
    }

    @Override public void onDisable() {
        if (economy != null) for (Coinflip flip : flips.values()) {
            OfflinePlayer owner = Bukkit.getOfflinePlayer(flip.creator);
            economy.depositPlayer(owner, flip.amount);
        }
        flips.clear();
    }

    private record Coinflip(UUID id, int number, UUID creator, double amount) {}
}