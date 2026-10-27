package com.taho.dialogcoinflips;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
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

import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class DialogCoinflips extends JavaPlugin implements Listener {
    private Economy economy;
    private final Map<UUID, Coinflip> flips = new LinkedHashMap<>();
    private final Map<UUID, PendingCreate> pendingCreate = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Integer, UUID>> bedrockSlotFlips = new ConcurrentHashMap<>();
    private final Set<UUID> resolving = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> rouletteProgress = new ConcurrentHashMap<>();
    private int nextId = 1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!setupEconomy()) {
            getLogger().severe("Vault economy provider not found. Install Vault and a Vault-compatible economy plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("cf"), "cf command missing").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("This command is for players.");
                return true;
            }
            if (!player.hasPermission("dialogcoinflips.use")) {
                player.sendMessage(Component.text("You do not have permission to use coinflips.", NamedTextColor.RED));
                return true;
            }
            if (args.length == 0) {
                openList(player);
                return true;
            }
            if (args[0].equalsIgnoreCase("create")) {
                if (args.length != 2) {
                    player.sendMessage(Component.text("Usage: /cf create <amount>", NamedTextColor.RED));
                    return true;
                }
                double amount = parseAmount(args[1]);
                if (!Double.isFinite(amount) || amount <= 0 || amount > getConfig().getDouble("max-wager", 1_000_000_000D)) {
                    player.sendMessage(Component.text("Enter a valid wager within the configured limit.", NamedTextColor.RED));
                    return true;
                }
                if (flips.values().stream().anyMatch(f -> f.creator().equals(player.getUniqueId()))) {
                    player.sendMessage(Component.text("You already have an open coinflip. Use /cf cancel first.", NamedTextColor.RED));
                    return true;
                }
                if (economy.getBalance(player) < amount) {
                    player.sendMessage(Component.text("You don't have enough money for that wager.", NamedTextColor.RED));
                    return true;
                }
                pendingCreate.put(player.getUniqueId(), new PendingCreate(amount, System.currentTimeMillis() + 60_000L));
                if (isBedrock(player)) openBedrockConfirm(player, amount);
                else openJavaConfirm(player, amount);
                return true;
            }
            if (args[0].equalsIgnoreCase("cancel")) {
                Coinflip own = flips.values().stream().filter(f -> f.creator().equals(player.getUniqueId())).findFirst().orElse(null);
                if (own == null) {
                    player.sendMessage(Component.text("You don't have an open coinflip.", NamedTextColor.RED));
                } else {
                    if (!economy.depositPlayer(Bukkit.getOfflinePlayer(player.getUniqueId()), own.amount()).transactionSuccess()) {
                        player.sendMessage(Component.text("Refund failed. Your coinflip remains open; contact an administrator.", NamedTextColor.RED));
                        return true;
                    }
                    flips.remove(own.id());
                    player.sendMessage(Component.text("Coinflip cancelled and wager refunded.", NamedTextColor.GREEN));
                    openList(player);
                }
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

    private double parseAmount(String input) {
        try {
            String normal = input.replace(",", "").trim().toLowerCase(Locale.ROOT);
            double multiplier = 1D;
            if (normal.endsWith("k")) { multiplier = 1_000D; normal = normal.substring(0, normal.length() - 1); }
            else if (normal.endsWith("m")) { multiplier = 1_000_000D; normal = normal.substring(0, normal.length() - 1); }
            else if (normal.endsWith("b")) { multiplier = 1_000_000_000D; normal = normal.substring(0, normal.length() - 1); }
            return Double.parseDouble(normal) * multiplier;
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }

    private boolean isBedrock(Player player) {
        try {
            Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            Method method = api.getMethod("isFloodgatePlayer", UUID.class);
            return Boolean.TRUE.equals(method.invoke(instance, player.getUniqueId()));
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private String money(double value) {
        return new DecimalFormat("#,##0.##").format(value);
    }

    private String playerName(UUID id) {
        return Optional.ofNullable(Bukkit.getOfflinePlayer(id).getName()).orElse("Unknown Player");
    }

    private ItemStack head(UUID owner, String name, String lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(owner));
        meta.displayName(Component.text(name, NamedTextColor.GOLD));
        meta.lore(List.of(Component.text(lore, NamedTextColor.GRAY), Component.text("Click to challenge", NamedTextColor.YELLOW)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack named(Material material, String name, String lore) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        meta.lore(List.of(Component.text(lore, NamedTextColor.GRAY)));
        item.setItemMeta(meta);
        return item;
    }

    private void openJavaConfirm(Player player, double amount) {
        Dialog dialog = Dialog.create(builder -> builder.empty()
            .base(DialogBase.builder(Component.text("Confirm Coinflip", NamedTextColor.GOLD))
                .body(List.of(DialogBody.plainMessage(Component.text(
                    "Create a coinflip for $" + money(amount) + "? Confirm will hold your wager until another player joins or you cancel.",
                    NamedTextColor.WHITE))))
                .build())
            .type(DialogType.confirmation(
                ActionButton.builder(Component.text("Confirm", NamedTextColor.GREEN))
                    .action(DialogAction.customClick((view, audience) -> {
                        if (audience instanceof Player p && createConfirmed(p, amount)) openList(p);
                    }, ClickCallback.Options.builder().uses(1).build())).build(),
                ActionButton.builder(Component.text("Cancel", NamedTextColor.RED))
                    .action(DialogAction.customClick((view, audience) -> {
                        if (audience instanceof Player p) cancelPending(p);
                    }, ClickCallback.Options.builder().uses(1).build())).build()
            )));
        player.showDialog(dialog);
    }

    private void openBedrockConfirm(Player player, double amount) {
        Inventory inv = Bukkit.createInventory(null, 27, Component.text("Confirm Coinflip"));
        fill(inv);
        inv.setItem(11, named(Material.LIME_WOOL, "Confirm", "Create a coinflip for $" + money(amount)));
        inv.setItem(15, named(Material.RED_WOOL, "Cancel", "Return without creating"));
        player.openInventory(inv);
    }

    private void cancelPending(Player player) {
        pendingCreate.remove(player.getUniqueId());
        player.sendMessage(Component.text("Coinflip creation cancelled.", NamedTextColor.YELLOW));
    }

    private boolean createConfirmed(Player player, double amount) {
        PendingCreate pending = pendingCreate.remove(player.getUniqueId());
        if (pending == null || pending.expiresAt() < System.currentTimeMillis() || Double.compare(pending.amount(), amount) != 0) {
            player.sendMessage(Component.text("Confirmation expired. Please use /cf create again.", NamedTextColor.RED));
            return false;
        }
        if (flips.values().stream().anyMatch(f -> f.creator().equals(player.getUniqueId()))) {
            player.sendMessage(Component.text("You already have an open coinflip.", NamedTextColor.RED));
            return false;
        }
        if (economy.getBalance(player) < amount) {
            player.sendMessage(Component.text("You no longer have enough money.", NamedTextColor.RED));
            return false;
        }
        if (!economy.withdrawPlayer(player, amount).transactionSuccess()) {
            player.sendMessage(Component.text("Your wager could not be withdrawn.", NamedTextColor.RED));
            return false;
        }
        Coinflip flip = new Coinflip(UUID.randomUUID(), nextId++, player.getUniqueId(), amount);
        flips.put(flip.id(), flip);
        player.sendMessage(Component.text("Coinflip #" + flip.number() + " created for $" + money(amount) + ".", NamedTextColor.GREEN));
        return true;
    }

    private void openList(Player player) {
        if (isBedrock(player)) {
            openBedrockList(player);
            return;
        }
        List<Coinflip> available = flips.values().stream()
            .filter(f -> !f.creator().equals(player.getUniqueId()))
            .limit(20)
            .toList();
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text("Select a player's head button to challenge their wager.", NamedTextColor.GRAY)));
        if (available.isEmpty()) {
            body.add(DialogBody.plainMessage(Component.text("No coinflips are available right now.", NamedTextColor.YELLOW)));
        } else {
            for (Coinflip flip : available) {
                body.add(DialogBody.item(head(flip.creator(), playerName(flip.creator()), "Wager: $" + money(flip.amount()))).build());
                body.add(DialogBody.plainMessage(Component.text(playerName(flip.creator()) + " — Wager: $" + money(flip.amount()), NamedTextColor.GOLD)));
            }
        }
        List<ActionButton> buttons = new ArrayList<>();
        for (Coinflip flip : available) {
            buttons.add(ActionButton.builder(Component.text(playerName(flip.creator()) + " — $" + money(flip.amount()), NamedTextColor.YELLOW))
                .action(DialogAction.customClick((view, audience) -> {
                    if (audience instanceof Player p) joinFlip(p, flip.id());
                }, ClickCallback.Options.builder().uses(1).build())).build());
        }
        ActionButton close = ActionButton.builder(Component.text("Close", NamedTextColor.RED)).build();
        Dialog dialog = Dialog.create(builder -> builder.empty()
            .base(DialogBase.builder(Component.text("Coinflips", NamedTextColor.GOLD)).body(body).build())
            .type(DialogType.multiAction(buttons.isEmpty() ? List.of(close) : buttons).columns(1).exitAction(close).build()));
        player.showDialog(dialog);
    }

    private void openBedrockList(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, Component.text("Coinflips"));
        fill(inv);
        Map<Integer, UUID> slotMap = new HashMap<>();
        int[] slots = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
        int index = 0;
        for (Coinflip flip : flips.values()) {
            if (flip.creator().equals(player.getUniqueId())) continue;
            if (index >= slots.length) break;
            int slot = slots[index++];
            inv.setItem(slot, head(flip.creator(), playerName(flip.creator()), "Wager: $" + money(flip.amount())));
            slotMap.put(slot, flip.id());
        }
        bedrockSlotFlips.put(player.getUniqueId(), slotMap);
        player.openInventory(inv);
    }

    private void joinFlip(Player player, UUID id) {
        Coinflip flip = flips.get(id);
        if (flip == null) {
            player.sendMessage(Component.text("That coinflip is no longer available.", NamedTextColor.RED));
            openList(player);
            return;
        }
        if (flip.creator().equals(player.getUniqueId())) {
            player.sendMessage(Component.text("You cannot join your own coinflip.", NamedTextColor.RED));
            return;
        }
        if (!resolving.add(id)) return;

        Player creator = Bukkit.getPlayer(flip.creator());
        if (creator == null || !creator.isOnline()) {
            resolving.remove(id);
            var refund = economy.depositPlayer(Bukkit.getOfflinePlayer(flip.creator()), flip.amount());
            if (!refund.transactionSuccess()) {
                player.sendMessage(Component.text("Creator is offline and the refund failed. Please contact an administrator.", NamedTextColor.RED));
            } else {
                flips.remove(id);
                player.sendMessage(Component.text("The creator is offline. Their wager was refunded.", NamedTextColor.RED));
            }
            openList(player);
            return;
        }
        if (economy.getBalance(player) < flip.amount() || !economy.withdrawPlayer(player, flip.amount()).transactionSuccess()) {
            resolving.remove(id);
            player.sendMessage(Component.text("You don't have enough money for this wager.", NamedTextColor.RED));
            return;
        }
        flips.remove(id);
        runRoulette(flip, player);
    }

    private void runRoulette(Coinflip flip, Player challenger) {
        List<UUID> candidates = List.of(flip.creator(), challenger.getUniqueId());
        int total = Math.max(10, getConfig().getInt("roulette-ticks", 40));
        rouletteProgress.put(flip.id(), 0);
        Bukkit.getScheduler().runTaskTimer(this, task -> {
            int elapsed = rouletteProgress.getOrDefault(flip.id(), 0) + 1;
            rouletteProgress.put(flip.id(), elapsed);
            UUID shown = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
            Player creator = Bukkit.getPlayer(flip.creator());
            for (Player viewer : new Player[]{challenger, creator}) {
                if (viewer != null && viewer.isOnline()) {
                    viewer.sendTitle(playerName(shown), "Coinflip roulette...", 0, 8, 0);
                    viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                }
            }
            if (elapsed >= total) {
                task.cancel();
                rouletteProgress.remove(flip.id());
                resolving.remove(flip.id());
                UUID winnerId = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
                double pot = flip.amount() * 2.0;
                var payout = economy.depositPlayer(Bukkit.getOfflinePlayer(winnerId), pot);
                String winnerName = playerName(winnerId);
                for (Player viewer : new Player[]{challenger, Bukkit.getPlayer(flip.creator())}) {
                    if (viewer != null && viewer.isOnline()) {
                        viewer.sendTitle("§6§l" + winnerName, "§aWon $" + money(pot), 5, 60, 10);
                        viewer.sendMessage(Component.text("Coinflip winner: " + winnerName + " — payout $" + money(pot), NamedTextColor.GOLD));
                    }
                }
                if (!payout.transactionSuccess()) {
                    getLogger().severe("Could not pay coinflip winner " + winnerName + " $" + money(pot) + ". Check the economy provider.");
                    if (challenger.isOnline()) challenger.sendMessage(Component.text("Payout failed. Please contact an administrator.", NamedTextColor.RED));
                }
            }
        }, 0L, 2L);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = PlainTextComponentSerializer.plainText().serialize(event.getView().title());
        if (!title.equals("Coinflips") && !title.equals("Confirm Coinflip")) return;
        event.setCancelled(true);
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;

        if (title.equals("Confirm Coinflip")) {
            PendingCreate pending = pendingCreate.get(player.getUniqueId());
            if (clicked.getType() == Material.LIME_WOOL && pending != null) {
                double amount = pending.amount();
                player.closeInventory();
                if (createConfirmed(player, amount)) openBedrockList(player);
            } else if (clicked.getType() == Material.RED_WOOL) {
                player.closeInventory();
                cancelPending(player);
            }
            return;
        }

        if (clicked.getType() == Material.PLAYER_HEAD) {
            UUID id = bedrockSlotFlips.getOrDefault(player.getUniqueId(), Map.of()).get(event.getSlot());
            if (id != null) {
                player.closeInventory();
                joinFlip(player, id);
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        String title = PlainTextComponentSerializer.plainText().serialize(event.getView().title());
        if (title.equals("Coinflips")) bedrockSlotFlips.remove(event.getPlayer().getUniqueId());
    }

    private void fill(Inventory inv) {
        ItemStack pane = named(Material.BLACK_STAINED_GLASS_PANE, " ", " ");
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);
    }

    @Override
    public void onDisable() {
        if (economy != null) {
            for (Coinflip flip : flips.values()) {
                var result = economy.depositPlayer(Bukkit.getOfflinePlayer(flip.creator()), flip.amount());
                if (!result.transactionSuccess()) getLogger().severe("Could not refund open coinflip #" + flip.number() + " to " + playerName(flip.creator()));
            }
        }
        flips.clear();
        pendingCreate.clear();
        bedrockSlotFlips.clear();
    }

    private record Coinflip(UUID id, int number, UUID creator, double amount) {}
    private record PendingCreate(double amount, long expiresAt) {}
}
