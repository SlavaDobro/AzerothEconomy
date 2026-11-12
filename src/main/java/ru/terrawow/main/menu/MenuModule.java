package ru.terrawow.main.menu;

import java.io.File;
import java.util.*;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import ru.terrawow.main.Icons;
import ru.terrawow.main.collection.Text;
import ru.terrawow.main.menu.MenuConfig.*;

/**
 * Server menus (the old CommandPanels panels): /menu (/wow) opens the main menu, /twm menu &lt;id&gt;
 * [ник] any menu. Files: plugins/TerraWoWmain/menus/*.yml — format in {@link MenuConfig}.
 */
public final class MenuModule implements Listener, CommandExecutor, TabCompleter {
  public static final String MAIN = "main";
  final JavaPlugin plugin;
  MenuConfig config;
  private final File folder;
  private static final Pattern PERM = Pattern.compile("(!?)perm:([A-Za-z0-9_.\\-*]+)");

  public MenuModule(JavaPlugin plugin) {
    this.plugin = plugin;
    this.folder = new File(plugin.getDataFolder(), "menus");
  }

  public void enable() {
    reload();
    Bukkit.getPluginManager().registerEvents(this, plugin);
    PluginCommand c = Objects.requireNonNull(plugin.getCommand("menu"), "menu");
    c.setExecutor(this);
    c.setTabCompleter(this);
    plugin.getLogger().info("Меню: " + config.menus.size() + " (" + String.join(", ", config.menus.keySet()) + ").");
  }

  public void reload() {
    if (!folder.isDirectory()) {
      folder.mkdirs();
      plugin.saveResource("menus/example.yml", false);
    }
    Map<String, YamlConfiguration> files = new TreeMap<>();
    File[] list = folder.listFiles((d, n) -> n.endsWith(".yml"));
    if (list != null)
      for (File f : list) {
        YamlConfiguration y = new YamlConfiguration();
        try {
          y.load(f);
        } catch (Exception e) {
          throw new IllegalArgumentException("menus/" + f.getName() + ": " + e.getMessage(), e);
        }
        files.put(f.getName(), y);
      }
    config = new MenuConfig(files);
  }

  // ---------------------------------------------------------------- open
  static final class Page implements InventoryHolder {
    final UUID owner;
    final Menu menu;
    final Inventory inv;
    final Map<Integer, Item> slots = new HashMap<>();

    Page(Player p, Menu menu, Component title) {
      this.owner = p.getUniqueId();
      this.menu = menu;
      this.inv = Bukkit.createInventory(this, menu.rows() * 9, title);
    }

    public Inventory getInventory() {
      return inv;
    }
  }

  public void open(Player p, String id) {
    Menu menu = config.menus.get(id);
    if (menu == null) throw new IllegalArgumentException("Нет меню " + id + ". Есть: " + String.join(", ", config.menus.keySet()));
    Page g = new Page(p, menu, text(p, menu.title()));
    for (Item i : menu.items())
      for (int slot : i.slots())
        if (!g.slots.containsKey(slot) && visible(p, i)) place(p, g, slot, i);
    Item fill = menu.fill() == null ? null : menu.item(menu.fill());
    if (fill != null)
      for (int s = 0; s < g.inv.getSize(); s++) if (!g.slots.containsKey(s)) place(p, g, s, fill);
    p.openInventory(g.inv);
  }

  private boolean visible(Player p, Item i) {
    return i.showIf().isBlank() || MenuConfig.test(fill(p, perms(p, i.showIf())));
  }

  private void place(Player p, Page g, int slot, Item i) {
    g.slots.put(slot, i);
    g.inv.setItem(slot, stack(p, i));
  }

  private ItemStack stack(Player p, Item i) {
    String icon = fill(p, i.icon());
    ItemStack it;
    if (icon.regionMatches(true, 0, "head:", 0, 5)) {
      it = new ItemStack(Material.PLAYER_HEAD);
      SkullMeta sm = (SkullMeta) it.getItemMeta();
      String who = icon.substring(5).trim();
      @SuppressWarnings("deprecation")
      OfflinePlayer owner = who.equalsIgnoreCase(p.getName()) ? p : Bukkit.getOfflinePlayer(who);
      sm.setOwningPlayer(owner);
      it.setItemMeta(sm);
    } else {
      try {
        it = Icons.create(icon);
      } catch (Exception e) {
        it = new ItemStack(Material.BARRIER);
      }
    }
    ItemMeta meta = it.getItemMeta();
    if (meta == null) return it;
    if (i.name().isBlank()) meta.setHideTooltip(true); // frame panes
    else meta.displayName(item(p, i.name()));
    if (!i.lore().isEmpty()) meta.lore(i.lore().stream().map(l -> item(p, l)).toList());
    if (i.modelData() != 0) setModelData(meta, i.modelData());
    if (i.glint()) meta.setEnchantmentGlintOverride(true);
    meta.addItemFlags(ItemFlag.values());
    it.setItemMeta(meta);
    return it;
  }

  @SuppressWarnings("deprecation")
  private static void setModelData(ItemMeta meta, int value) {
    meta.setCustomModelData(value);
  }

  // ---------------------------------------------------------------- click
  @EventHandler
  public void click(InventoryClickEvent e) {
    if (!(e.getView().getTopInventory().getHolder() instanceof Page g)) return;
    e.setCancelled(true);
    if (!(e.getWhoClicked() instanceof Player p) || !g.owner.equals(p.getUniqueId())) return;
    if (e.getRawSlot() < 0 || e.getRawSlot() >= g.inv.getSize()) return;
    Item i = g.slots.get(e.getRawSlot());
    if (i == null) return;
    List<String> actions = e.isRightClick() ? i.right() : i.left();
    if (actions.isEmpty()) return;
    if (!i.require().isBlank() && !MenuConfig.test(fill(p, perms(p, i.require())))) {
      run(p, i.deny(), 0);
      return;
    }
    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.2f);
    run(p, actions, 0);
  }

  @EventHandler
  public void drag(InventoryDragEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof Page) e.setCancelled(true);
  }

  /** Runs actions in order; "delay N" continues the rest N ticks later. */
  void run(Player p, List<String> actions, int from) {
    for (int k = from; k < actions.size(); k++) {
      if (!p.isOnline()) return;
      String a = actions.get(k).trim();
      String[] parts = a.split("\\s+", 2);
      String head = parts[0].toLowerCase(Locale.ROOT), arg = parts.length > 1 ? parts[1] : "";
      try {
        switch (head) {
          case "delay" -> {
            final int next = k + 1;
            Bukkit.getScheduler().runTaskLater(plugin, () -> run(p, actions, next), Math.max(1, Integer.parseInt(arg.trim())));
            return;
          }
          case "close" -> p.closeInventory();
          case "open" -> open(p, arg.trim());
          case "console" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), strip(fill(p, arg)));
          case "player" -> p.performCommand(strip(fill(p, arg)));
          case "chat" -> p.chat(fill(p, arg)); // "/cmd" goes through PlayerCommandPreprocessEvent like a typed command
          case "message", "msg" -> p.sendMessage(text(p, arg));
          case "sound" -> {
            String[] s = arg.trim().split("\\s+");
            float vol = s.length > 1 ? Float.parseFloat(s[1]) : 1f, pitch = s.length > 2 ? Float.parseFloat(s[2]) : 1f;
            p.playSound(p.getLocation(), s[0], vol, pitch);
          }
          default -> {}
        }
      } catch (RuntimeException ex) {
        plugin.getLogger().log(Level.WARNING, "Меню: действие '" + a + "' для " + p.getName(), ex);
        p.sendMessage(Text.c("&c" + ex.getMessage()));
      }
    }
  }

  private static String strip(String cmd) {
    String c = cmd.trim();
    return c.startsWith("/") ? c.substring(1) : c;
  }

  // ---------------------------------------------------------------- text
  private static final MiniMessage MM = MiniMessage.miniMessage();

  /** {player} and PlaceholderAPI. */
  String fill(Player p, String s) {
    if (s == null) return "";
    String out = s.replace("{player}", p.getName());
    if (out.indexOf('%') >= 0 && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI"))
      out = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(p, out);
    return out;
  }

  private static String perms(Player p, String condition) {
    Matcher m = PERM.matcher(condition);
    StringBuilder b = new StringBuilder();
    while (m.find()) {
      boolean has = p.hasPermission(m.group(2));
      m.appendReplacement(b, String.valueOf(m.group(1).isEmpty() == has));
    }
    m.appendTail(b);
    return b.toString();
  }

  private Component text(Player p, String s) {
    return MM.deserialize(MenuConfig.toMini(fill(p, s)));
  }

  /** Item names and lore: no default italic. */
  private Component item(Player p, String s) {
    return text(p, s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
  }

  // ---------------------------------------------------------------- commands
  /** /menu (/wow): the main menu. */
  @Override
  public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
    command(s, a.length == 0 ? new String[0] : a);
    return true;
  }

  /**
   * /twm menu [id] [ник]. Без аргументов — главное меню. Открыть другому игроку — консоль или
   * twm.admin (так кнопки MMOCore открывают меню: console "twm menu main &lt;caster.name&gt;").
   * /twm menu list | reload — для twm.admin.
   */
  public void command(CommandSender s, String[] a) {
    try {
      if (a.length == 0) {
        open(player(s), MAIN);
        return;
      }
      String sub = a[0].toLowerCase(Locale.ROOT);
      if (sub.equals("list") || sub.equals("reload")) {
        if (!s.hasPermission("twm.admin")) throw new IllegalArgumentException("Нет права twm.admin");
        if (sub.equals("reload")) reload();
        s.sendMessage(Text.c("&6Меню (" + config.menus.size() + "): &f" + String.join(", ", config.menus.keySet())
            + (sub.equals("reload") ? " &a— перечитаны" : "")));
        return;
      }
      if (a.length >= 2) {
        if (s instanceof Player pl && !pl.getName().equalsIgnoreCase(a[1]) && !s.hasPermission("twm.admin"))
          throw new IllegalArgumentException("Открыть меню другому игроку можно только из консоли или с twm.admin");
        Player target = Bukkit.getPlayerExact(a[1]);
        if (target == null) throw new IllegalArgumentException("Игрок не в сети: " + a[1]);
        open(target, sub);
        return;
      }
      open(player(s), sub);
    } catch (IllegalArgumentException | IllegalStateException e) {
      s.sendMessage(Text.c("&c" + e.getMessage()));
    }
  }

  private static Player player(CommandSender s) {
    if (!(s instanceof Player p)) throw new IllegalArgumentException("Из консоли: twm menu <id> <ник>");
    return p;
  }

  @Override
  public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
    return tab(s, a);
  }

  public List<String> tab(CommandSender s, String[] a) {
    if (a.length == 0) return List.of();
    List<String> out = new ArrayList<>();
    if (a.length == 1) {
      out.addAll(config.menus.keySet());
      if (s.hasPermission("twm.admin")) out.addAll(List.of("list", "reload"));
    } else if (a.length == 2 && s.hasPermission("twm.admin")) Bukkit.getOnlinePlayers().forEach(pl -> out.add(pl.getName()));
    String last = a[a.length - 1].toLowerCase(Locale.ROOT);
    return out.stream().filter(x -> x.toLowerCase(Locale.ROOT).startsWith(last)).toList();
  }

  public boolean has(String id) {
    return config.menus.containsKey(id);
  }
}
