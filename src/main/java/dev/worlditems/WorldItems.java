package dev.worlditems;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class WorldItems extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private NamespacedKey key;
    private final Map<String, ItemDef> defs = new LinkedHashMap<>();
    private final Map<String, MenuDef> menus = new HashMap<>();
    private final Map<String, Long> cooldowns = new HashMap<>();

    // ---------- data classes ----------

    private record ItemDef(String id, Material material, String name, List<String> lore, int slot,
                           List<String> worlds, String click, int cooldown, boolean glow,
                           String permission, List<String> actions) {
        boolean appliesTo(String world) {
            return worlds.contains("*") || worlds.stream().anyMatch(w -> w.equalsIgnoreCase(world));
        }
    }

    private record MenuItem(int slot, Material material, String name, List<String> lore, List<String> actions) {}

    private record MenuDef(String id, String title, int rows, Material filler, List<MenuItem> items) {}

    private record MenuHolder(String menuId) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    // ---------- lifecycle ----------

    @Override
    public void onEnable() {
        key = new NamespacedKey(this, "item_id");
        saveDefaultConfig();
        load();
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getOnlinePlayers().forEach(this::sync);
    }

    private void load() {
        reloadConfig();
        defs.clear();
        menus.clear();

        ConfigurationSection sec = getConfig().getConfigurationSection("items");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                ConfigurationSection s = sec.getConfigurationSection(id);
                if (s == null || !s.getBoolean("enabled", true)) continue;
                Material mat = Material.matchMaterial(s.getString("material", "COMPASS"));
                if (mat == null) { getLogger().warning("Invalid material for item " + id); continue; }
                defs.put(id, new ItemDef(id, mat, s.getString("name", id), s.getStringList("lore"),
                        s.getInt("slot", 0), s.getStringList("worlds"),
                        s.getString("click", "right").toLowerCase(), s.getInt("cooldown", 0),
                        s.getBoolean("glow", false), s.getString("permission", ""),
                        s.getStringList("actions")));
            }
        }

        ConfigurationSection msec = getConfig().getConfigurationSection("menus");
        if (msec != null) {
            for (String id : msec.getKeys(false)) {
                ConfigurationSection m = msec.getConfigurationSection(id);
                if (m == null || !m.getBoolean("enabled", true)) continue;
                int rows = Math.max(1, Math.min(6, m.getInt("rows", 3)));
                Material filler = m.contains("filler") ? Material.matchMaterial(m.getString("filler", "")) : null;
                List<MenuItem> items = new ArrayList<>();
                ConfigurationSection isec = m.getConfigurationSection("items");
                if (isec != null) {
                    for (String iid : isec.getKeys(false)) {
                        ConfigurationSection is = isec.getConfigurationSection(iid);
                        if (is == null) continue;
                        Material mat = Material.matchMaterial(is.getString("material", "STONE"));
                        if (mat == null) { getLogger().warning("Invalid material in menu " + id + "/" + iid); continue; }
                        items.add(new MenuItem(is.getInt("slot", 0), mat, is.getString("name", iid),
                                is.getStringList("lore"), is.getStringList("actions")));
                    }
                }
                menus.put(id, new MenuDef(id, m.getString("title", id), rows, filler, items));
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) return usage(sender);
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                load();
                Bukkit.getOnlinePlayers().forEach(this::sync);
                sender.sendMessage(Component.text("WorldItems reloaded."));
            }
            case "list" -> {
                ConfigurationSection sec = getConfig().getConfigurationSection("items");
                if (sec == null) { sender.sendMessage(Component.text("No items configured.")); return true; }
                for (String id : sec.getKeys(false)) {
                    boolean on = sec.getBoolean(id + ".enabled", true);
                    sender.sendMessage(Component.text(id + ": " + (on ? "ON" : "OFF")));
                }
            }
            case "on", "off", "toggle" -> {
                if (args.length < 2) return usage(sender);
                String id = args[1];
                String base = "items." + id;
                if (!getConfig().isConfigurationSection(base)) {
                    sender.sendMessage(Component.text("Unknown item: " + id));
                    return true;
                }
                boolean now = switch (args[0].toLowerCase()) {
                    case "on" -> true;
                    case "off" -> false;
                    default -> !getConfig().getBoolean(base + ".enabled", true);
                };
                getConfig().set(base + ".enabled", now);
                saveConfig();
                load();
                Bukkit.getOnlinePlayers().forEach(this::sync);
                sender.sendMessage(Component.text("Item " + id + " is now " + (now ? "ON" : "OFF") + "."));
            }
            default -> { return usage(sender); }
        }
        return true;
    }

    private boolean usage(CommandSender sender) {
        sender.sendMessage(Component.text("Usage: /worlditems <reload|list|on <id>|off <id>|toggle <id>>"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) return List.of("reload", "list", "on", "off", "toggle");
        if (args.length == 2 && List.of("on", "off", "toggle").contains(args[0].toLowerCase())) {
            ConfigurationSection sec = getConfig().getConfigurationSection("items");
            return sec == null ? List.of() : new ArrayList<>(sec.getKeys(false));
        }
        return List.of();
    }

    // ---------- helpers ----------

    private String ph(Player p, String s) {
        return s.replace("%player%", p.getName())
                .replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("%max%", String.valueOf(Bukkit.getMaxPlayers()))
                .replace("%world%", p.getWorld().getName())
                .replace("%world_online%", String.valueOf(p.getWorld().getPlayers().size()))
                .replace("%ping%", String.valueOf(p.getPing()));
    }

    private Component text(Player p, String s) {
        return MM.deserialize(ph(p, s)).decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack build(Player p, ItemDef d) {
        ItemStack item = new ItemStack(d.material());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(text(p, d.name()));
        if (!d.lore().isEmpty()) meta.lore(d.lore().stream().map(l -> text(p, l)).toList());
        if (d.glow()) meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, d.id());
        item.setItemMeta(meta);
        return item;
    }

    private String idOf(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    private boolean isOurs(ItemStack item) { return idOf(item) != null; }

    /** Remove our items that don't belong in this world, give those that do. */
    private void sync(Player p) {
        PlayerInventory inv = p.getInventory();
        String world = p.getWorld().getName();

        for (int i = 0; i < inv.getSize(); i++) {
            String id = idOf(inv.getItem(i));
            if (id == null) continue;
            ItemDef d = defs.get(id);
            if (d == null || !d.appliesTo(world)) inv.setItem(i, null);
        }
        if (isOurs(inv.getItemInOffHand())) inv.setItemInOffHand(null);

        for (ItemDef d : defs.values()) {
            if (!d.appliesTo(world)) continue;
            for (int i = 0; i < inv.getSize(); i++) {
                if (d.id().equals(idOf(inv.getItem(i)))) inv.setItem(i, null);
            }
            ItemStack existing = inv.getItem(d.slot());
            if (existing != null && !existing.getType().isAir()) {
                inv.setItem(d.slot(), null);
                inv.addItem(existing).values()
                        .forEach(rest -> p.getWorld().dropItemNaturally(p.getLocation(), rest));
            }
            inv.setItem(d.slot(), build(p, d));
        }
    }

    private void syncLater(Player p) {
        Bukkit.getScheduler().runTask(this, () -> { if (p.isOnline()) sync(p); });
    }

    private void openMenu(Player p, String id) {
        MenuDef m = menus.get(id);
        if (m == null) { getLogger().warning("Unknown menu: " + id); return; }
        Inventory inv = Bukkit.createInventory(new MenuHolder(id), m.rows() * 9, text(p, m.title()));
        if (m.filler() != null) {
            ItemStack f = new ItemStack(m.filler());
            ItemMeta fm = f.getItemMeta();
            fm.displayName(Component.empty());
            f.setItemMeta(fm);
            for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, f);
        }
        for (MenuItem mi : m.items()) {
            if (mi.slot() < 0 || mi.slot() >= inv.getSize()) continue;
            ItemStack it = new ItemStack(mi.material());
            ItemMeta meta = it.getItemMeta();
            meta.displayName(text(p, mi.name()));
            if (!mi.lore().isEmpty()) meta.lore(mi.lore().stream().map(l -> text(p, l)).toList());
            it.setItemMeta(meta);
            inv.setItem(mi.slot(), it);
        }
        p.openInventory(inv);
    }

    // ---------- actions ----------

    private void run(Player p, List<String> actions) {
        for (String a : actions) run(p, a);
    }

    private void run(Player p, String raw) {
        int i = raw.indexOf(':');
        if (i < 0) return;
        String type = raw.substring(0, i).trim().toLowerCase();
        String value = ph(p, raw.substring(i + 1).trim());

        switch (type) {
            case "transfer" -> {
                int c = value.lastIndexOf(':');
                String host = c > 0 ? value.substring(0, c) : value;
                int port = 25565;
                if (c > 0) {
                    try { port = Integer.parseInt(value.substring(c + 1)); }
                    catch (NumberFormatException ex) { getLogger().warning("Bad port in action: " + raw); return; }
                }
                p.transfer(host, port);
            }
            case "menu" -> Bukkit.getScheduler().runTask(this, () -> openMenu(p, value));
            case "close" -> Bukkit.getScheduler().runTask(this, () -> p.closeInventory());
            case "message" -> p.sendMessage(MM.deserialize(value));
            case "broadcast" -> Bukkit.broadcast(MM.deserialize(value));
            case "actionbar" -> p.sendActionBar(MM.deserialize(value));
            case "title" -> {
                String[] parts = value.split(";", 2);
                p.showTitle(Title.title(MM.deserialize(parts[0]),
                        parts.length > 1 ? MM.deserialize(parts[1]) : Component.empty()));
            }
            case "sound" -> {
                try { p.playSound(p.getLocation(), Sound.valueOf(value.toUpperCase()), 1f, 1f); }
                catch (IllegalArgumentException ex) { getLogger().warning("Unknown sound: " + value); }
            }
            case "console" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), value);
            case "player" -> p.performCommand(value.startsWith("/") ? value.substring(1) : value);
            case "teleport" -> {
                String[] t = value.split(",");
                try {
                    World w = Bukkit.getWorld(t[0].trim());
                    if (w == null) { getLogger().warning("Unknown world in action: " + raw); return; }
                    Location loc = new Location(w, Double.parseDouble(t[1].trim()),
                            Double.parseDouble(t[2].trim()), Double.parseDouble(t[3].trim()),
                            t.length > 4 ? Float.parseFloat(t[4].trim()) : 0f,
                            t.length > 5 ? Float.parseFloat(t[5].trim()) : 0f);
                    p.teleportAsync(loc);
                } catch (RuntimeException ex) { getLogger().warning("Bad teleport action: " + raw); }
            }
            default -> getLogger().warning("Unknown action type: " + type);
        }
    }

    // ---------- giving ----------

    @EventHandler public void onJoin(PlayerJoinEvent e) { syncLater(e.getPlayer()); }
    @EventHandler public void onRespawn(PlayerRespawnEvent e) { syncLater(e.getPlayer()); }
    @EventHandler public void onWorld(PlayerChangedWorldEvent e) { syncLater(e.getPlayer()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        String prefix = e.getPlayer().getUniqueId() + ":";
        cooldowns.keySet().removeIf(k -> k.startsWith(prefix));
    }

    // ---------- locking ----------

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (isOurs(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (isOurs(e.getMainHandItem()) || isOurs(e.getOffHandItem())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        // our menus: block everything, run the clicked item's actions
        if (e.getView().getTopInventory().getHolder() instanceof MenuHolder holder) {
            e.setCancelled(true);
            if (e.getClickedInventory() != e.getView().getTopInventory()) return;
            if (!(e.getWhoClicked() instanceof Player p)) return;
            MenuDef m = menus.get(holder.menuId());
            if (m == null) return;
            for (MenuItem mi : m.items()) {
                if (mi.slot() == e.getSlot()) { run(p, mi.actions()); break; }
            }
            return;
        }

        boolean block = isOurs(e.getCurrentItem()) || isOurs(e.getCursor());
        if (!block && e.getClick() == ClickType.NUMBER_KEY && e.getWhoClicked() instanceof Player p) {
            block = isOurs(p.getInventory().getItem(e.getHotbarButton()));
        }
        if (!block && e.getClick() == ClickType.SWAP_OFFHAND && e.getWhoClicked() instanceof Player p) {
            block = isOurs(p.getInventory().getItemInOffHand());
        }
        if (block) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof MenuHolder || isOurs(e.getOldCursor())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(this::isOurs);
        e.getItemsToKeep().removeIf(this::isOurs);
    }

    // ---------- using ----------

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        boolean right = a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK;
        boolean left = a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK;
        if (!right && !left) return;

        String id = idOf(e.getItem());
        if (id == null) return;
        e.setCancelled(true);

        ItemDef d = defs.get(id);
        if (d == null) return;
        if (d.click().equals("right") && !right) return;
        if (d.click().equals("left") && !left) return;

        Player p = e.getPlayer();
        if (!d.permission().isEmpty() && !p.hasPermission(d.permission())) {
            p.sendMessage(MM.deserialize(getConfig().getString("messages.no-permission", "<red>No permission.")));
            return;
        }

        String ck = p.getUniqueId() + ":" + id;
        long now = System.currentTimeMillis();
        Long until = cooldowns.get(ck);
        if (until != null && now < until) {
            p.sendMessage(MM.deserialize(getConfig().getString("messages.cooldown", "<red>Please wait.")));
            return;
        }
        if (d.cooldown() > 0) cooldowns.put(ck, now + d.cooldown() * 1000L);

        run(p, d.actions());
    }
}
