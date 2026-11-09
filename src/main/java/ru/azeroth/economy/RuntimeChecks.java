package ru.azeroth.economy;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** Isolated runtime checks; proxy inventories, real Paper serialization and plugin APIs. */
final class RuntimeChecks {
  static void run(AzerothEconomy p) {
    List<String> results = new ArrayList<>();
    Map<String, byte[]> originals = new LinkedHashMap<>();
    try {
      ItemStack item = new ItemStack(Material.DIAMOND_SWORD);
      ItemMeta meta = item.getItemMeta();
      meta.setDisplayName("Проверка компонентов");
      meta.setLore(List.of("Тестовый предмет"));
      meta.getPersistentDataContainer()
          .set(new NamespacedKey(p, "test"), PersistentDataType.STRING, "round-trip");
      item.setItemMeta(meta);
      item.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.SHARPNESS, 3);
      originals.put("Paper-PDC-enchant-lore", item.serializeAsBytes());
      var nexo = Bukkit.getPluginManager().getPlugin("Nexo");
      if (nexo != null && nexo.isEnabled()) {
        Class<?> api = nexo.getClass().getClassLoader().loadClass("com.nexomc.nexo.api.NexoItems");
        Object builder =
            api.getMethod("itemFromId", String.class).invoke(null, "awakened_shaman_lifescepter");
        ItemStack custom = (ItemStack) builder.getClass().getMethod("build").invoke(builder);
        originals.put("Nexo-awakened_shaman_lifescepter", custom.serializeAsBytes());
      }
      var mi = Bukkit.getPluginManager().getPlugin("MMOItems");
      if (mi != null && mi.isEnabled()) {
        ItemStack custom =
            (ItemStack)
                mi.getClass()
                    .getMethod("getItem", String.class, String.class)
                    .invoke(mi, "SWORD", "LONG_SWORD");
        if (custom == null) throw new AssertionError("MMOItems item missing");
        originals.put("MMOItems-LONG_SWORD", custom.serializeAsBytes());
        ItemRules.check(custom, UUID.randomUUID(), true);
        results.add("PASS MMOItems public API: ordinary item allowed");
        ClassLoader cl = mi.getClass().getClassLoader();
        Class<?> live = cl.loadClass("net.Indyuce.mmoitems.api.item.mmoitem.LiveMMOItem"),
            stat = cl.loadClass("net.Indyuce.mmoitems.stat.type.ItemStat"),
            data = cl.loadClass("net.Indyuce.mmoitems.stat.data.type.StatData"),
            soul = cl.loadClass("net.Indyuce.mmoitems.stat.data.SoulboundData");
        Object read = live.getConstructor(ItemStack.class).newInstance(custom);
        Object bound =
            soul.getConstructor(UUID.class, String.class, int.class)
                .newInstance(UUID.randomUUID(), "test", 1);
        Object field =
            cl.loadClass("net.Indyuce.mmoitems.ItemStats").getField("SOULBOUND").get(null);
        live.getMethod("setData", stat, data).invoke(read, field, bound);
        Object builder = live.getMethod("newBuilder").invoke(read);
        ItemStack boundItem = (ItemStack) builder.getClass().getMethod("build").invoke(builder);
        boolean rejected = false;
        try {
          ItemRules.check(boundItem, UUID.randomUUID(), true);
        } catch (IllegalArgumentException expected) {
          rejected = true;
        }
        if (!rejected) throw new AssertionError("bound transfer allowed");
        results.add("PASS MMOItems bound item transfer rejected");
        originals.put("MMOItems-soulbound", boundItem.serializeAsBytes());
      }
      for (var e : originals.entrySet())
        if (!ItemStack.deserializeBytes(e.getValue())
            .equals(
                ItemStack.deserializeBytes(
                    ItemStack.deserializeBytes(e.getValue()).serializeAsBytes())))
          throw new AssertionError("codec " + e.getKey());
      results.add("PASS Paper binary roundtrip: " + originals.keySet());
      var vault = Bukkit.getServicesManager().load(net.milkbowl.vault.economy.Economy.class);
      if (vault == null || !vault.getName().equals("AzerothEconomy"))
        throw new AssertionError("Vault provider");
      results.add("PASS single Vault provider " + vault.getName());
      var test = Bukkit.getOfflinePlayer(UUID.fromString("11111111-1111-1111-1111-111111111111"));
      for (double n : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -1, 1.5})
        if (vault.depositPlayer(test, n).transactionSuccess())
          throw new AssertionError("bad Vault amount");
      results.add("PASS Vault rejects NaN/Infinity/negative/fractions");
      if (!vault.depositPlayer(test, 12345).transactionSuccess()
          || p.ledger.cached(test.getUniqueId().toString(), "gold") != 12345
          || !vault.withdrawPlayer(test, 12345).transactionSuccess())
        throw new AssertionError("Vault shared balance");
      results.add("PASS Vault deposit/withdraw uses the same copper balance");
      if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
        String expanded =
            me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(test, "%aeconomy_gold%");
        if (!expanded.equals(p.ledger.currency("gold").format(0)))
          throw new AssertionError("PlaceholderAPI expansion");
        results.add("PASS PlaceholderAPI registered expansion and formatted balance");
      }
      testClicks(p, results);
    } catch (Throwable e) {
      results.add("FAIL " + e);
      p.getLogger().log(java.util.logging.Level.SEVERE, "Runtime test failed", e);
      finish(p, results);
      return;
    }
    p.async(
        () -> {
          try (Database db =
              new Database(
                  p.getDataFolder()
                      .toPath()
                      .resolve("runtime-domain-" + System.currentTimeMillis() + ".sqlite"))) {
            Ledger l = new Ledger(db, p.ledger.currencies);
            String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
            l.register(UUID.fromString(a), "test-a");
            l.register(UUID.fromString(b), "test-b");
            l.admin("seed", b, "gold", 100000, "give");
            Map<String, byte[]> after = new LinkedHashMap<>();
            for (var e : originals.entrySet())
              for (String route : List.of("bank", "mail", "trade", "auction")) {
                String id = UUID.randomUUID().toString();
                l.prepareIn(id, a, e.getValue(), e.getKey());
                l.confirmIn(id, a);
                String owner = a;
                switch (route) {
                  case "bank" -> l.storeBank(a, id, 0);
                  case "mail" -> {
                    l.sendMail(
                        "mail:" + id,
                        a,
                        b,
                        "test",
                        "body",
                        "gold",
                        0,
                        0,
                        List.of(id),
                        0,
                        0,
                        100000,
                        false);
                    l.claimMail("claim:" + id, b, "mail:" + id, "all");
                    owner = b;
                  }
                  case "trade" -> {
                    l.requestTrade("trade:" + id, a, b, 100000);
                    l.acceptTrade("trade:" + id, b);
                    l.offerItem("trade:" + id, a, id);
                    long rev = l.confirmTrade("trade:" + id, a);
                    l.confirmTrade("trade:" + id, b);
                    l.completeTrade("trade:" + id, rev);
                    l.claimMail("claim:" + id, b, "trade:trade:" + id + ":b", "all");
                    owner = b;
                  }
                  case "auction" -> {
                    l.listLot("lot:" + id, a, id, 100, 0, 100000);
                    l.buyLot("buy:" + id, b, "lot:" + id, 0);
                    l.claimMail("claim:" + id, b, "purchase:lot:" + id, "all");
                    owner = b;
                  }
                }
                byte[] bytes = (byte[]) l.asset(owner, id).get("data");
                if (!Arrays.equals(bytes, e.getValue()))
                  throw new AssertionError("bytes " + e.getKey() + "/" + route);
                after.put(e.getKey() + "/" + route, bytes);
              }
            return after;
          }
        },
        after -> {
          for (var e : after.entrySet()) {
            String source = e.getKey().substring(0, e.getKey().lastIndexOf('/'));
            if (!ItemStack.deserializeBytes(originals.get(source))
                .equals(ItemStack.deserializeBytes(e.getValue())))
              throw new AssertionError(e.getKey());
            results.add("PASS exact item data through " + e.getKey());
          }
          finish(p, results);
        },
        e -> {
          results.add("FAIL domain " + e);
          finish(p, results);
        });
  }

  private static void testClicks(AzerothEconomy p, List<String> results) {
    UUID uuid = UUID.randomUUID();
    Player pl =
        (Player)
            Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class[] {Player.class},
                (o, m, a) ->
                    switch (m.getName()) {
                      case "getUniqueId" -> uuid;
                      case "isOnline", "hasPermission" -> true;
                      case "getName" -> "test-ui";
                      default -> null;
                    });
    Menus.Page page = new Menus.Page(pl, "Тест");
    final int[] calls = {0};
    page.buttons.put(0, () -> calls[0]++);
    InventoryView view =
        (InventoryView)
            Proxy.newProxyInstance(
                InventoryView.class.getClassLoader(),
                new Class[] {InventoryView.class},
                (o, m, a) ->
                    switch (m.getName()) {
                      case "getTopInventory", "getInventory" -> page.inventory;
                      case "getPlayer" -> pl;
                      case "convertSlot" -> a[0];
                      case "getType" -> InventoryType.CHEST;
                      default -> null;
                    });
    for (ClickType type :
        List.of(
            ClickType.SHIFT_LEFT,
            ClickType.NUMBER_KEY,
            ClickType.DOUBLE_CLICK,
            ClickType.DROP,
            ClickType.SWAP_OFFHAND)) {
      InventoryClickEvent event =
          new InventoryClickEvent(
              view,
              InventoryType.SlotType.CONTAINER,
              0,
              type,
              InventoryAction.MOVE_TO_OTHER_INVENTORY);
      p.menus.click(event);
      if (!event.isCancelled() || calls[0] != 0) throw new AssertionError("unsafe click " + type);
    }
    InventoryDragEvent drag =
        new InventoryDragEvent(
            view,
            new ItemStack(Material.STONE),
            new ItemStack(Material.STONE),
            false,
            Map.of(0, new ItemStack(Material.STONE)));
    p.menus.drag(drag);
    if (!drag.isCancelled()) throw new AssertionError("drag");
    for (int i = 0; i < 2; i++)
      p.menus.click(
          new InventoryClickEvent(
              view,
              InventoryType.SlotType.CONTAINER,
              0,
              ClickType.LEFT,
              InventoryAction.PICKUP_ALL));
    if (calls[0] != 1) throw new AssertionError("double action");
    results.add(
        "PASS GUI handlers: shift-click, number key, double-click, drop, offhand, drag, repeated"
            + " left click (proxy player)");
  }

  private static void finish(AzerothEconomy p, List<String> results) {
    try {
      Files.write(p.getDataFolder().toPath().resolve("runtime-tests.txt"), results);
    } catch (Exception e) {
      p.getLogger().severe(e.toString());
    }
    results.forEach(p.getLogger()::info);
    if (p.getConfig().getBoolean("testing.stop-after-tests"))
      Bukkit.getScheduler().runTaskLater(p, Bukkit::shutdown, 20);
  }
}
