package ru.terrawow.main.race;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import ru.terrawow.main.Icons;
import ru.terrawow.main.collection.Text;
import ru.terrawow.main.race.RaceConfig.*;

/** Character creation: faction → race → gender → confirm; and the race card (/race). */
final class RaceMenus implements Listener {
  private static final int[] RACE_SLOTS = {20, 22, 24, 29, 31, 33};
  private final RaceModule m;
  private final Set<UUID> navigating = new HashSet<>();

  RaceMenus(RaceModule m) {
    this.m = m;
  }

  static final class Page implements InventoryHolder {
    final UUID owner;
    final Inventory inv;
    final boolean choosing; // part of character creation: closing re-opens it while no race is chosen
    final Map<Integer, Runnable> click = new HashMap<>();

    Page(Player p, String title, boolean choosing) {
      owner = p.getUniqueId();
      this.choosing = choosing;
      inv = Bukkit.createInventory(this, 54, Text.c(title));
    }

    public Inventory getInventory() {
      return inv;
    }
  }

  // ---------------------------------------------------------------- 1. faction
  void factions(Player p) {
    Page g = page(p, "<#8A5A12>✦ <#3A2410>Выбор фракции <#8A5A12>✦", true);
    item(g, 4, "NETHER_STAR", "<#FFD100>Создание персонажа", List.of(
        "&7Выберите фракцию, затем расу и пол.", "&7Раса выбирается &fодин раз&7:", "&7сменить её сможет только администратор."), null);
    int[] slots = {20, 24};
    int i = 0;
    for (Faction f : m.config.factions.values()) {
      if (i >= slots.length) break;
      List<String> lore = new ArrayList<>();
      f.lore().forEach(l -> lore.add("&7" + l));
      lore.add("");
      lore.add("<#FFD100>Расы:");
      for (Race r : m.config.of(f.id())) lore.add("&8• &r" + r.name());
      lore.add("");
      lore.add("<#FFE88A>▸ Нажмите, чтобы выбрать");
      item(g, slots[i++], f.icon(), f.name(), lore, () -> races(p, f));
    }
    open(p, g);
  }

  // ---------------------------------------------------------------- 2. race
  void races(Player p, Faction f) {
    Page g = page(p, "<#8A5A12>✦ <#3A2410>Расы • " + strip(f.name()) + " <#8A5A12>✦", true);
    item(g, 4, f.icon(), f.name(), f.lore().stream().map(l -> "&7" + l).toList(), null);
    List<Race> list = m.config.of(f.id());
    for (int i = 0; i < list.size() && i < RACE_SLOTS.length; i++) {
      Race r = list.get(i);
      List<String> lore = card(r, Gender.MALE);
      lore.add("");
      lore.add("<#FFE88A>▸ Нажмите, чтобы выбрать");
      item(g, RACE_SLOTS[i], r.iconMale(), r.name(), lore, () -> genders(p, r));
    }
    item(g, 45, "ARROW", "<#FFD100>◂ К фракциям", List.of(), () -> factions(p));
    open(p, g);
  }

  // ---------------------------------------------------------------- 3. gender
  void genders(Player p, Race r) {
    Page g = page(p, "<#8A5A12>✦ <#3A2410>" + strip(r.name()) + " • пол <#8A5A12>✦", true);
    item(g, 4, r.iconMale(), r.name(), card(r, Gender.MALE), null);
    item(g, 20, r.iconMale(), "<#FFD100>" + Gender.MALE.ru, List.of(
        "&7Рост: &f" + RaceConfig.height(m.config.scale(r, Gender.MALE)), "", "<#FFE88A>▸ Нажмите, чтобы выбрать"),
        () -> confirm(p, r, Gender.MALE));
    item(g, 24, r.iconFemale(), "<#FFD100>" + Gender.FEMALE.ru, List.of(
        "&7Рост: &f" + RaceConfig.height(m.config.scale(r, Gender.FEMALE)), "", "<#FFE88A>▸ Нажмите, чтобы выбрать"),
        () -> confirm(p, r, Gender.FEMALE));
    item(g, 45, "ARROW", "<#FFD100>◂ К расам", List.of(), () -> races(p, m.config.factions.get(r.faction())));
    open(p, g);
  }

  // ---------------------------------------------------------------- 4. confirm
  void confirm(Player p, Race r, Gender gender) {
    Page g = page(p, "<#8A5A12>✦ <#3A2410>Подтверждение <#8A5A12>✦", true);
    List<String> lore = card(r, gender);
    lore.add(0, "&7Пол: &f" + gender.ru);
    item(g, 13, r.icon(gender), r.name(), lore, null);
    item(g, 29, "LIME_STAINED_GLASS", "&a✔ Создать персонажа", List.of(
        "&7Раса: &r" + r.name(), "&7Пол: &f" + gender.ru, "", "&cСменить расу потом сможет", "&cтолько администратор."), () -> {
          p.closeInventory();
          m.choose(p, r, gender);
        });
    item(g, 33, "RED_STAINED_GLASS", "&c✘ Назад", List.of("&7Выбрать другой пол или расу."), () -> genders(p, r));
    open(p, g);
  }

  // ---------------------------------------------------------------- race card (/race)
  void info(Player p) {
    Race r = m.raceOf(p).orElse(null);
    if (r == null) {
      factions(p);
      return;
    }
    Gender gender = m.of(p.getUniqueId()).gender;
    Page g = page(p, "<#8A5A12>✦ <#3A2410>Ваша раса <#8A5A12>✦", false);
    Faction f = m.config.factions.get(r.faction());
    item(g, 4, f.icon(), f.name(), f.lore().stream().map(l -> "&7" + l).toList(), null);
    List<String> lore = card(r, gender);
    lore.add(0, "&7Пол: &f" + gender.ru);
    item(g, 22, r.icon(gender), r.name(), lore, null);
    if (r.racial() != null) {
      long left = m.racials.left(p);
      List<String> rl = new ArrayList<>();
      r.racial().text().forEach(t -> rl.add("&7" + t));
      rl.add("&7Перезарядка: &f" + r.racial().cooldown() + " сек.");
      rl.add("");
      rl.add(left > 0 ? "&cЕщё " + (left + 999) / 1000 + " сек." : "<#FFE88A>▸ Нажмите, чтобы применить (/racial)");
      item(g, 31, "BLAZE_POWDER", "<#FFD100>" + r.racial().name(), rl, () -> {
        p.closeInventory();
        m.racials.cast(p, r);
      });
    }
    item(g, 45, "NETHER_STAR", "<#FFD100>◂ Главное меню", List.of("&7/menu"), () -> ru.terrawow.main.MainMenu.open(m.plugin, p));
    item(g, 49, "BARRIER", "<#FF4040>Закрыть", List.of(), p::closeInventory);
    open(p, g);
  }

  /** Description of a race for item lore. */
  private List<String> card(Race r, Gender gender) {
    List<String> lore = new ArrayList<>();
    r.lore().forEach(l -> lore.add("<#FFE88A>" + l));
    lore.add("");
    lore.add("&7Фракция: &r" + m.config.factions.get(r.faction()).name());
    lore.add("&7Рост: &f" + RaceConfig.height(m.config.scale(r, gender)));
    if (!r.passives().isEmpty()) {
      lore.add("");
      lore.add("<#FFD100>Расовые особенности:");
      for (Passive ps : r.passives()) lore.add("&8• &f" + ps.name() + (ps.text().isBlank() ? "" : " &7— " + ps.text()));
    }
    if (r.racial() != null) {
      lore.add("");
      lore.add("<#FFD100>Способность: &f" + r.racial().name() + " &8(" + r.racial().cooldown() + " сек.)");
      r.racial().text().forEach(t -> lore.add("&7" + t));
    }
    if (!r.classes().isEmpty()) {
      lore.add("");
      lore.add("<#FFD100>Классы:");
      List<String> names = r.classes().stream().map(m.config::className).toList();
      for (int i = 0; i < names.size(); i += 3)
        lore.add("&8• " + String.join("&8, ", names.subList(i, Math.min(names.size(), i + 3))));
    }
    return lore;
  }

  // ---------------------------------------------------------------- helpers
  private static String strip(String s) {
    return ChatColor.stripColor(Text.c(s));
  }

  private Page page(Player p, String title, boolean choosing) {
    Page g = new Page(p, title, choosing);
    ItemStack black = pane(Material.BLACK_STAINED_GLASS_PANE), gold = pane(Material.ORANGE_STAINED_GLASS_PANE);
    for (int s = 0; s < 54; s++) g.inv.setItem(s, black);
    for (int s : new int[] {0, 8, 45, 53}) g.inv.setItem(s, gold);
    return g;
  }

  private void open(Player p, Page g) {
    navigating.add(p.getUniqueId()); // closing the previous page is not "closing the menu"
    try {
      p.openInventory(g.inv);
    } finally {
      navigating.remove(p.getUniqueId());
    }
  }

  private static ItemStack pane(Material mat) {
    ItemStack i = new ItemStack(mat);
    ItemMeta meta = i.getItemMeta();
    meta.setDisplayName(" ");
    meta.setHideTooltip(true);
    i.setItemMeta(meta);
    return i;
  }

  private void item(Page g, int slot, String icon, String name, List<String> lore, Runnable click) {
    ItemStack i;
    try {
      i = Icons.create(icon);
    } catch (Exception e) {
      i = new ItemStack(Material.PLAYER_HEAD);
    }
    ItemMeta meta = i.getItemMeta();
    meta.setDisplayName(Text.c("&r" + name));
    meta.setLore(lore.stream().map(l -> Text.c("&r" + l)).toList());
    meta.addItemFlags(ItemFlag.values());
    i.setItemMeta(meta);
    g.inv.setItem(slot, i);
    if (click != null) g.click.put(slot, click);
  }

  @EventHandler
  public void click(InventoryClickEvent e) {
    if (!(e.getView().getTopInventory().getHolder() instanceof Page g)) return;
    e.setCancelled(true);
    if (!(e.getWhoClicked() instanceof Player p) || !g.owner.equals(p.getUniqueId())) return;
    if (e.getRawSlot() < 0 || e.getRawSlot() >= 54) return;
    Runnable r = g.click.get(e.getRawSlot());
    if (r != null)
      try {
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.2f);
        r.run();
      } catch (RuntimeException ex) {
        RaceModule.msg(p, "&c" + ex.getMessage());
      }
  }

  @EventHandler
  public void drag(InventoryDragEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof Page) e.setCancelled(true);
  }

  /** Character creation can't be skipped: closing re-opens it (admins may close it). */
  @EventHandler
  public void close(InventoryCloseEvent e) {
    if (!(e.getInventory().getHolder() instanceof Page g) || !g.choosing || !(e.getPlayer() instanceof Player p)) return;
    if (navigating.contains(p.getUniqueId()) || e.getReason() == InventoryCloseEvent.Reason.DISCONNECT) return;
    if (!m.config.forceChoice || p.hasPermission("twm.admin") || m.of(p.getUniqueId()).chosen()) return;
    Bukkit.getScheduler().runTaskLater(m.plugin, () -> {
      if (p.isOnline() && !m.of(p.getUniqueId()).chosen() && !(p.getOpenInventory().getTopInventory().getHolder() instanceof Page))
        factions(p);
    }, 2);
  }
}
