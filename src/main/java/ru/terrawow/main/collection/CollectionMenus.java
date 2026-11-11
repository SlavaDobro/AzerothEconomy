package ru.terrawow.main.collection;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import ru.terrawow.main.Icons;
import ru.terrawow.main.collection.CollectionConfig.*;

/** Mounts / pets journal and the skins page. Left click = action, right click = skins. */
final class CollectionMenus implements Listener {
  private static final int[] GRID = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
  private final CollectionModule m;

  CollectionMenus(CollectionModule m) {
    this.m = m;
  }

  static final class Page implements InventoryHolder {
    final UUID owner;
    final Inventory inv;
    final Map<Integer, Runnable> left = new HashMap<>();
    final Map<Integer, Runnable> right = new HashMap<>();

    Page(Player p, String title) {
      owner = p.getUniqueId();
      inv = Bukkit.createInventory(this, 54, Text.c(title));
    }

    public Inventory getInventory() {
      return inv;
    }
  }

  // ---------------------------------------------------------------- journal
  void list(Player p, Kind kind, int page) {
    PlayerCollection c = m.of(p.getUniqueId());
    List<Entry> all = new ArrayList<>(m.config.all(kind));
    all.sort(Comparator.comparing((Entry e) -> !c.owns(e))); // collected first, like the WoW journal
    long owned = all.stream().filter(c::owns).count();
    int pages = Math.max(1, (all.size() + GRID.length - 1) / GRID.length);
    page = Math.max(0, Math.min(page, pages - 1));
    String title = kind == Kind.MOUNT ? "Маунты" : "Питомцы";
    Page g = new Page(p, "<#8A5A12>✦ <#3A2410>" + title + " <#8A5A12>✦");
    frame(g);
    String active = m.activeMob(p, kind);
    item(g, 4, kind == Kind.MOUNT ? "SADDLE" : "LEAD", "<#FFD100>" + title, List.of(
        "&7Собрано: &f" + owned + " &8/ &f" + all.size(), "",
        kind == Kind.MOUNT ? "&7ЛКМ — призвать (3 сек., не двигайтесь)" : "&7ЛКМ — призвать или отозвать",
        "&7ПКМ — выбрать облик"), false);
    int from = page * GRID.length;
    for (int i = 0; i < GRID.length && from + i < all.size(); i++) {
      Entry e = all.get(from + i);
      boolean has = c.owns(e);
      Skin skin = c.skinOf(e);
      boolean isActive = e.id().equals(active);
      List<String> lore = new ArrayList<>();
      lore.add("&8" + (kind == Kind.MOUNT ? (e.mode().equals("flying") ? "Летающий маунт" : "Наземный маунт") : "Питомец"));
      if (has && e.skins().size() > 1) lore.add("&7Облик: &f" + Text.c(skin.name()));
      if (!e.lore().isEmpty()) {
        lore.add("");
        e.lore().forEach(l -> lore.add("<#FFE88A>" + l));
      }
      if (!e.source().isBlank()) lore.add("&7Где получить: &f" + e.source());
      if (kind == Kind.MOUNT && has)
        lore.addAll(e.mode().equals("flying")
            ? List.of("", "&7Пробел — вверх, Shift — вниз,", "&7Shift на земле — спешиться.")
            : List.of("", "&7Пробел — прыжок, Shift — спешиться."));
      lore.add("");
      if (!has) lore.add("&c✘ Не получено");
      else if (isActive) lore.add(kind == Kind.MOUNT ? "&a✔ Вы верхом" : "&a✔ Рядом с вами — ЛКМ, чтобы отозвать");
      else lore.add("<#FFE88A>▸ ЛКМ — призвать");
      if (has && e.skins().size() > 1) lore.add("<#FFE88A>▸ ПКМ — облики (" + ownedSkins(c, e) + "/" + e.skins().size() + ")");
      int slot = GRID[i];
      item(g, slot, has ? skin.icon() : "GRAY_DYE", (has ? "" : "&8") + e.name(), lore, isActive);
      if (has) {
        g.left.put(slot, () -> {
          p.closeInventory();
          m.summon(p, e);
        });
        g.right.put(slot, () -> skins(p, e));
      }
    }
    final int prev = page - 1, next = page + 1;
    if (page > 0) item(g, 47, "ARROW", "<#FFD100>◂ Назад", List.of(), false, () -> list(p, kind, prev));
    if (page < pages - 1) item(g, 51, "ARROW", "<#FFD100>Вперёд ▸", List.of(), false, () -> list(p, kind, next));
    if (kind == Kind.MOUNT)
      item(g, 48, "LEAD", "<#FF4040>Спешиться", List.of("&7Сойти с маунта.", "&7В воздухе — медленное падение."), false, () -> {
        p.closeInventory();
        m.dismiss(p, Kind.MOUNT, true);
      });
    else
      item(g, 48, "BARRIER", "<#FF4040>Отозвать питомца", List.of("&7Питомец вернётся домой."), false, () -> {
        p.closeInventory();
        m.dismiss(p, Kind.PET, true);
      });
    item(g, 45, "NETHER_STAR", "<#FFD100>◂ Главное меню", List.of("&7/menu"), false, () -> {
      p.closeInventory();
      p.performCommand("menu");
    });
    Kind other = kind == Kind.MOUNT ? Kind.PET : Kind.MOUNT;
    item(g, 53, other == Kind.PET ? "LEAD" : "SADDLE", "<#FFD100>" + (other == Kind.PET ? "Питомцы ▸" : "Маунты ▸"), List.of(), false,
        () -> list(p, other, 0));
    item(g, 49, "BARRIER", "<#FF4040>Закрыть", List.of(), false, p::closeInventory);
    p.openInventory(g.inv);
  }

  private static int ownedSkins(PlayerCollection c, Entry e) {
    return (int) e.skins().values().stream().filter(s -> c.ownsSkin(e, s)).count();
  }

  // ---------------------------------------------------------------- skins
  void skins(Player p, Entry e) {
    PlayerCollection c = m.of(p.getUniqueId());
    Page g = new Page(p, "<#8A5A12>✦ <#3A2410>Облики • " + strip(e.name()) + " <#8A5A12>✦");
    frame(g);
    Skin current = c.skinOf(e);
    item(g, 4, e.icon(), e.name(), List.of("&7Выберите облик.", "&7Выбор сохраняется."), false);
    int i = 0;
    for (Skin s : e.skins().values()) {
      if (i >= GRID.length) break;
      boolean has = c.ownsSkin(e, s);
      boolean sel = s.id().equals(current.id());
      List<String> lore = new ArrayList<>();
      s.lore().forEach(l -> lore.add("<#FFE88A>" + l));
      if (!s.lore().isEmpty()) lore.add("");
      lore.add(sel ? "&a✔ Выбран" : has ? "<#FFE88A>▸ ЛКМ — выбрать" : "&c✘ Не получено");
      int slot = GRID[i++];
      item(g, slot, has ? s.icon() : "GRAY_DYE", (has ? "" : "&8") + s.name(), lore, sel);
      if (has && !sel) g.left.put(slot, () -> {
        m.select(p, e, s);
        skins(p, e);
      });
    }
    item(g, 45, "ARROW", "<#FFD100>◂ Назад", List.of(), false, () -> list(p, e.kind(), 0));
    item(g, 49, "BARRIER", "<#FF4040>Закрыть", List.of(), false, p::closeInventory);
    p.openInventory(g.inv);
  }

  // ---------------------------------------------------------------- helpers
  private static String strip(String s) {
    return ChatColor.stripColor(Text.c(s));
  }

  private void frame(Page g) {
    ItemStack black = pane(Material.BLACK_STAINED_GLASS_PANE), gold = pane(Material.ORANGE_STAINED_GLASS_PANE);
    for (int s = 0; s < 54; s++) g.inv.setItem(s, black);
    for (int s : new int[] {0, 8, 45, 53}) g.inv.setItem(s, gold);
  }

  private static ItemStack pane(Material m) {
    ItemStack i = new ItemStack(m);
    ItemMeta meta = i.getItemMeta();
    meta.setDisplayName(" ");
    meta.setHideTooltip(true);
    i.setItemMeta(meta);
    return i;
  }

  private void item(Page g, int slot, String icon, String name, List<String> lore, boolean glow) {
    item(g, slot, icon, name, lore, glow, null);
  }

  private void item(Page g, int slot, String icon, String name, List<String> lore, boolean glow, Runnable click) {
    ItemStack i;
    try {
      i = Icons.create(icon);
    } catch (Exception e) {
      i = new ItemStack(Material.PAPER);
    }
    ItemMeta meta = i.getItemMeta();
    meta.setDisplayName(Text.c("&r" + name));
    meta.setLore(lore.stream().map(l -> Text.c("&r" + l)).toList());
    meta.addItemFlags(ItemFlag.values());
    if (glow) meta.setEnchantmentGlintOverride(true);
    i.setItemMeta(meta);
    g.inv.setItem(slot, i);
    if (click != null) g.left.put(slot, click);
  }

  @EventHandler
  public void click(InventoryClickEvent e) {
    if (!(e.getView().getTopInventory().getHolder() instanceof Page g)) return;
    e.setCancelled(true);
    if (!(e.getWhoClicked() instanceof Player p) || !g.owner.equals(p.getUniqueId())) return;
    if (e.getRawSlot() < 0 || e.getRawSlot() >= 54) return;
    Runnable r = (e.isRightClick() ? g.right : g.left).get(e.getRawSlot());
    if (r == null && e.isRightClick()) r = g.left.get(e.getRawSlot());
    if (r != null)
      try {
        r.run();
      } catch (RuntimeException ex) {
        CollectionModule.msg(p, "&c" + ex.getMessage());
      }
  }

  @EventHandler
  public void drag(InventoryDragEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof Page) e.setCancelled(true);
  }
}
