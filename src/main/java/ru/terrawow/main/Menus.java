package ru.terrawow.main;

import static ru.terrawow.main.Database.*;

import java.util.*;
import java.util.function.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

public final class Menus implements Listener {
  private final TerraWoWmain p;
  public final Map<String, Long> localAccess = new HashMap<>();

  public Menus(TerraWoWmain p) {
    this.p = p;
  }

  public static final class Page implements InventoryHolder {
    final UUID owner;
    final Inventory inventory;
    final Map<Integer, Runnable> buttons = new HashMap<>();
    boolean active = true;

    Page(Player p, String title) {
      owner = p.getUniqueId();
      inventory = Bukkit.createInventory(this, 54, title);
    }

    public Inventory getInventory() {
      return inventory;
    }
  }

  /**
   * Standard bottom row of every window: 45 main menu, 48 back (inside the economy), 49 help for this
   * window; the window's own actions use the other bottom slots.
   */
  private Page page(Player player, String title, Runnable back, String... help) {
    Page m = new Page(player, "§6" + title);
    put(m, 45, "main_menu", Material.NETHER_STAR, "◂ Главное меню", List.of("Вернуться в главное меню (/menu)."),
        () -> MainMenu.open(p, player));
    if (back != null) put(m, 48, "back", Material.ARROW, "◂ " + p.text("back"), List.of(), back);
    if (help.length > 0) put(m, 49, "help", Material.BOOK, "❓ Как пользоваться", List.of(help), null);
    return m;
  }

  private void show(Player player, Page page) {
    ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
    ItemMeta meta = pane.getItemMeta();
    meta.setHideTooltip(true);
    pane.setItemMeta(meta);
    if (!ru.terrawow.main.ui.UiBackgrounds.active()) // drawn background, no glass
      for (int s = 45; s < 54; s++) if (page.inventory.getItem(s) == null) page.inventory.setItem(s, pane);
    if (player.isOnline()) player.openInventory(page.inventory);
  }

  public void put(
      Page m, int slot, Material icon, String title, List<String> lore, Runnable action) {
    put(m, slot, null, icon, title, lore, action);
  }

  /** key = WoW icon (UiIcons) or null; nexo-icons.<MATERIAL> in config.yml still wins. */
  public void put(
      Page m, int slot, String key, Material icon, String title, List<String> lore, Runnable action) {
    ItemStack i = key == null ? new ItemStack(icon) : ru.terrawow.main.ui.UiIcons.stack(key, icon);
    String custom = p.getConfig().getString("nexo-icons." + icon.name(), "");
    if (!custom.isEmpty())
      try {
        i = Icons.create("nexo:" + custom);
      } catch (Exception ignored) {
      }
    ItemMeta meta = i.getItemMeta();
    meta.setDisplayName("§e" + title);
    meta.setLore(lore.stream().map(s -> "§7" + s).toList());
    i.setItemMeta(meta);
    m.inventory.setItem(slot, i);
    if (action != null) m.buttons.put(slot, action);
  }

  private void item(Page m, int slot, Map<String, Object> row, Runnable action) {
    ItemStack item = ItemStack.deserializeBytes((byte[]) row.get("data"));
    m.inventory.setItem(slot, item);
    m.buttons.put(slot, action);
  }

  private void pages(Page m, Player pl, int page, IntConsumer next) {
    if (page > 0)
      put(m, 47, "page_prev", Material.ARROW, p.text("previous"), List.of(), () -> next.accept(page - 1));
    put(
        m,
        51,
        "page_next", Material.ARROW,
        p.text("next"),
        List.of(p.label("t_225e004e10d0") + (page + 1)),
        () -> next.accept(page + 1));
  }

  public void confirm(Player player, String description, Runnable action, Runnable back) {
    Page m = page(player, p.text("confirmation"), back, "Подтвердите действие кнопкой", "в центре или вернитесь назад.");
    put(
        m,
        22,
        "confirm", Material.LIME_CONCRETE,
        p.text("confirm"),
        List.of(description),
        () -> {
          player.closeInventory();
          action.run();
        });
    put(m, 24, "close", Material.RED_CONCRETE, p.text("cancel"), List.of(), back);
    show(player, m);
  }

  public void home(Player player) {
    p.check(player, "use");
    Page m = page(player, p.text("home"), null, "Деньги и вещи вашего персонажа.", "", "Нажмите на раздел: кошелёк, банк,", "почта, торговля, аукцион, история,", "получение вещей.");
    put(
        m,
        10,
        "economy", Material.GOLD_INGOT,
        p.text("wallet"),
        List.of(
            p.ledger
                .currency("gold")
                .format(p.ledger.cached(player.getUniqueId().toString(), "gold"))),
        () -> wallet(player));
    if (p.getConfig().getBoolean("modules.bank"))
      put(
          m,
          12,
          "bank", Material.CHEST,
          p.text("bank"),
          List.of(p.label("t_ba41dd7312bf")),
          () -> bank(player));
    if (p.getConfig().getBoolean("modules.mail"))
      put(
          m,
          14,
          "mail", Material.WRITABLE_BOOK,
          p.text("mail"),
          List.of(p.label("t_d3a93df3e0b8")),
          () -> mailbox(player, 0));
    if (p.getConfig().getBoolean("modules.trade"))
      put(
          m,
          16,
          "trade", Material.EMERALD,
          p.text("trade"),
          List.of(p.label("t_bcc0b9609a87")),
          () -> {
            try {
              trade(player);
            } catch (Exception e) {
              p.tell(player, "trade-help");
            }
          });
    if (p.getConfig().getBoolean("modules.auction"))
      put(
          m,
          29,
          "auction", Material.GOLDEN_HOE,
          p.text("auction"),
          List.of(p.label("t_b84cf9fbbed7")),
          () -> auction(player, "", false, 0));
    put(m, 31, "history", Material.PAPER, p.text("history"), List.of(), () -> history(player, 0, false));
    put(
        m,
        33,
        "inbox", Material.ENDER_CHEST,
        p.text("inbox"),
        List.of(p.label("t_b9bbe3b02d7d")),
        () -> inbox(player, 0));
    show(player, m);
  }

  public void wallet(Player player) {
    wallet(player, 0);
  }

  private void wallet(Player player, int page) {
    p.check(player, "balance");
    Page m = page(player, p.text("wallet"), () -> home(player), "Ваши деньги и другие валюты.", "", "Перевести золото игроку:", "/twm economy pay <ник> gold <сумма>", "Например: 1g 20s — 1 золотая 20 серебряных.");
    int i = 0, visible = 0;
    for (var c : p.ledger.currencies.values()) {
      try {
        p.currencyPermission(player, c.id());
      } catch (Exception ignored) {
        continue;
      }
      if (visible++ < Ledger.offset(page)) continue;
      if (i >= 45) break;
      Material icon = Material.matchMaterial(c.icon());
      put(
          m,
          i++,
          icon == null ? Material.GOLD_NUGGET : icon,
          c.name(),
          List.of(
              c.format(p.ledger.cached(player.getUniqueId().toString(), c.id())),
              "ID: " + c.id(),
              p.label("t_61473e0f3e9d") + c.id() + p.label("t_cce207cd2a48"),
              "/twm economy exchange " + c.id() + p.label("t_187c07efbc49")),
          null);
      if (c.icon().startsWith("nexo:") || c.icon().startsWith("mmo:"))
        try {
          ItemStack custom = Icons.create(c.icon());
          ItemMeta customMeta = custom.getItemMeta();
          ItemMeta display = m.inventory.getItem(i - 1).getItemMeta();
          customMeta.setDisplayName(display.getDisplayName());
          customMeta.setLore(display.getLore());
          custom.setItemMeta(customMeta);
          m.inventory.setItem(i - 1, custom);
        } catch (Exception ignored) {
          /* Text-labelled vanilla icon remains usable without the integration. */
        }
    }
    pages(m, player, page, n -> wallet(player, n));
    show(player, m);
  }

  public void bank(Player player) {
    p.access(player, "bank");
    String owner = player.getUniqueId().toString();
    p.async(
        () -> p.ledger.tabs(owner),
        tabs -> {
          Page m = page(player, p.text("bank"), () -> home(player), "Ваш банк: вкладки для вещей.", "", "1. Нажмите вкладку, чтобы открыть.", "2. Купить новую вкладку — кнопка ниже.");
          int i = 0;
          for (var tab : tabs) {
            int index = (int) num(tab, "tab");
            Material icon = Material.matchMaterial(str(tab, "icon"));
            put(
                m,
                i++,
                icon == null ? Material.CHEST : icon,
                str(tab, "name"),
                List.of(p.label("t_c7d29f6d6629") + num(tab, "slots")),
                () -> bankTab(player, index));
          }
          long price = p.getConfig().getLong("bank.tab-price");
          put(
              m,
              40,
              "buy", Material.EMERALD,
              p.label("t_50051e468d60"),
              List.of(p.ledger.currency("gold").format(price)),
              () ->
                  confirm(
                      player,
                      p.label("t_e39116985fec") + p.ledger.currency("gold").format(price),
                      () -> {
                        p.check(player, "bank.buy");
                        p.access(player, "bank");
                        p.action(
                            player,
                            () ->
                                p.ledger.buyTab(
                                    UUID.randomUUID().toString(),
                                    owner,
                                    price,
                                    p.getConfig().getInt("bank.max-tabs")),
                            () -> bank(player));
                      },
                      () -> bank(player)));
          put(
              m,
              42,
              "history", Material.BOOK,
              p.label("t_151eb56c76d5"),
              List.of(),
              () -> history(player, 0, true));
          show(player, m);
        },
        e -> p.error(player, e));
  }

  public void bankTab(Player player, int tab) {
    p.access(player, "bank");
    String owner = player.getUniqueId().toString();
    p.async(
        () -> p.ledger.assets(owner, "BANK:" + tab),
        rows -> {
          Page m = page(player, p.text("bank") + " • " + (tab + 1), () -> bank(player), "Положить вещь: возьмите её в руку", "и нажмите воронку «Внести предмет».", "Забрать: нажмите на вещь в банке.", "", "Мало места — купите 9 ячеек кнопкой ниже.");
          int i = 0;
          for (var row : rows)
            if (i < 45)
              item(
                  m,
                  i++,
                  row,
                  () -> {
                    p.access(player, "bank");
                    p.custody.withdraw(player, str(row, "id"), () -> bankTab(player, tab));
                  });
          put(
              m,
              46,
              "deposit", Material.HOPPER,
              p.label("t_7b599d0a7fbd"),
              List.of(p.label("t_fc0d7a127414")),
              () -> {
                p.access(player, "bank");
                player.closeInventory();
                p.custody.deposit(
                    player,
                    false,
                    id ->
                        p.action(
                            player,
                            () -> p.ledger.storeBank(owner, id, tab),
                            () -> bankTab(player, tab)));
              });
          long price = p.getConfig().getLong("bank.slots-price");
          put(
              m,
              52,
              "buy", Material.EMERALD,
              p.label("t_43019b6c5594"),
              List.of(p.ledger.currency("gold").format(price)),
              () ->
                  confirm(
                      player,
                      p.label("t_2d51ff410b3b") + p.ledger.currency("gold").format(price),
                      () -> {
                        p.access(player, "bank");
                        p.check(player, "bank.buy");
                        p.action(
                            player,
                            () -> p.ledger.expand(UUID.randomUUID().toString(), owner, tab, price),
                            () -> bankTab(player, tab));
                      },
                      () -> bankTab(player, tab)));
          put(
              m,
              53,
              "rename", Material.NAME_TAG,
              p.label("t_963a4c0955c3"),
              List.of("/twm economy bank rename " + tab + p.label("t_dc45a828c0b3")),
              () -> p.tell(player, "bank-rename-help"));
          show(player, m);
        },
        e -> p.error(player, e));
  }

  public void inbox(Player player, int page) {
    p.check(player, "withdraw");
    String owner = player.getUniqueId().toString();
    p.async(
        () -> p.ledger.assets(owner, "INBOX"),
        rows -> {
          Page m = page(player, p.text("inbox"), () -> home(player), "Вещи, которые ждут вас: из почты,", "торговли, аукциона и наград.", "", "Нажмите на вещь, чтобы забрать её.");
          int start = Ledger.offset(page);
          for (int i = start; i < Math.min(start + 45, rows.size()); i++) {
            var row = rows.get(i);
            item(
                m,
                i - start,
                row,
                () -> p.custody.withdraw(player, str(row, "id"), () -> inbox(player, page)));
          }
          pages(m, player, page, n -> inbox(player, n));
          put(
              m,
              53,
              "take_all", Material.HOPPER,
              p.text("take-all"),
              List.of(p.label("t_92a6108e4021")),
              () -> {
                player.closeInventory();
                p.custody.all(player);
              });
          show(player, m);
        },
        e -> p.error(player, e));
  }

  public void mailbox(Player player, int page) {
    p.access(player, "mail");
    p.async(
        () -> p.ledger.mails(player.getUniqueId().toString(), page),
        rows -> {
          Page m = page(player, p.text("mail"), () -> home(player), "Ваши письма.", "", "Нажмите письмо, чтобы прочитать", "и забрать вложения.", "Написать — кнопка справа внизу.");
          int i = 0;
          for (var row : rows)
            put(
                m,
                i++,
                "letter", Material.PAPER,
                str(row, "subject"),
                List.of(
                    p.label("t_8c12119492bd") + str(row, "sender"),
                    p.label("t_47e1988b9a2f")
                        + p.ledger.currency(str(row, "currency")).format(num(row, "money")),
                    p.label("t_b6ea7a08a4a0")
                        + p.ledger.currency(str(row, "currency")).format(num(row, "cod"))),
                () -> letter(player, str(row, "id")));
          pages(m, player, page, n -> mailbox(player, n));
          put(
              m,
              53,
              "mail_write", Material.WRITABLE_BOOK,
              p.label("t_41151de7d143"),
              List.of(
                  p.label("t_c6b70281fe4b"),
                  p.label("t_df68f2dddeec"),
                  p.label("t_9f52978b7368"),
                  p.label("t_5920a43b41ff")),
              () -> p.tell(player, "mail-help"));
          show(player, m);
        },
        e -> p.error(player, e));
  }

  public void letter(Player player, String id) {
    p.access(player, "mail");
    String owner = player.getUniqueId().toString();
    p.async(
        () -> Map.entry(p.ledger.mail(owner, id), p.ledger.assets(owner, "MAIL:" + id)),
        result -> {
          var row = result.getKey();
          Page m = page(player, str(row, "subject"), () -> mailbox(player, 0), "Письмо: текст, деньги и вещи.", "", "Нажмите вложение, чтобы забрать.", "Ответить или вернуть отправителю —", "кнопки внизу.");
          int i = 0;
          Runnable claimAll = () -> claim(player, id, "all");
          for (var asset : result.getValue()) {
            if (i >= 27) break;
            item(m, i++, asset, () -> claim(player, id, str(asset, "id")));
          }
          put(
              m,
              30,
              "letter", Material.WRITTEN_BOOK,
              p.label("t_8393c450bf66"),
              wrap(str(row, "body"), 45),
              () -> player.sendMessage(str(row, "body")));
          put(
              m,
              32,
              "economy", Material.GOLD_NUGGET,
              p.ledger.currency(str(row, "currency")).format(num(row, "money")),
              List.of(p.label("t_3cdb3dea5221")),
              () -> claim(player, id, "money"));
          put(
              m,
              40,
              "take_all", Material.HOPPER,
              p.text("take-all"),
              List.of(
                  p.label("t_b6ea7a08a4a0")
                      + p.ledger.currency(str(row, "currency")).format(num(row, "cod")),
                  p.label("t_1762410725b4")),
              claimAll);
          put(
              m,
              44,
              "archive", Material.BARRIER,
              p.text("archive-mail"),
              List.of(),
              () ->
                  p.action(
                      player, () -> p.ledger.archiveMail(id, owner), () -> mailbox(player, 0)));
          if (Ledger.isUuid(str(row, "sender")) && num(row, "returned") == 0)
            put(
                m,
                42,
                "mail_return", Material.ARROW,
                p.label("t_77d9e7901c71"),
                List.of(),
                () ->
                    confirm(
                        player,
                        p.label("t_5e0d3ee034f2"),
                        () ->
                            p.action(
                                player,
                                () -> p.ledger.returnMail(id, owner),
                                () -> mailbox(player, 0)),
                        () -> letter(player, id)));
          put(
              m,
              53,
              "mail_write", Material.WRITABLE_BOOK,
              p.label("t_b9af23eeff5d"),
              List.of(p.label("t_9684e155df45") + str(row, "sender")),
              () -> {
                player.closeInventory();
                player.sendMessage(
                    "/twm economy mail send "
                        + str(row, "sender")
                        + " gold 0 0 Re:"
                        + str(row, "subject")
                        + p.label("t_ea40e1153e9b"));
              });
          show(player, m);
        },
        e -> p.error(player, e));
  }

  private void claim(Player player, String id, String selected) {
    p.access(player, "mail");
    p.check(player, "mail.claim");
    String owner = player.getUniqueId().toString();
    p.async(
        () -> p.ledger.mail(owner, id),
        m -> {
          Runnable action =
              () ->
                  p.action(
                      player,
                      () -> p.ledger.claimMail(UUID.randomUUID().toString(), owner, id, selected),
                      () -> {
                        player.closeInventory();
                        p.custody.all(player);
                      });
          if (num(m, "cod") > 0 && num(m, "paid") == 0)
            confirm(
                player,
                p.label("t_dde55a426f36")
                    + p.ledger.currency(str(m, "currency")).format(num(m, "cod"))
                    + "?",
                action,
                () -> letter(player, id));
          else action.run();
        },
        e -> p.error(player, e));
  }

  public void auction(Player player, String query, boolean mine, int page) {
    p.check(player, "auction");
    p.module("auction");
    String owner = player.getUniqueId().toString();
    p.async(
        () -> p.ledger.lots(query, mine ? owner : "", page),
        rows -> {
          Page m = page(player, p.text("auction"), () -> home(player), "Лоты других игроков.", "", "Нажмите лот, чтобы купить:", "вещь придёт почтой.", "Продать: возьмите вещь в руку и", "/twm economy auction sell <цена>");
          int i = 0;
          for (var row : rows) {
            String id = str(row, "id"), price = p.ledger.currency("gold").format(num(row, "price"));
            ItemStack item = ItemStack.deserializeBytes((byte[]) row.get("data"));
            ItemMeta meta = item.getItemMeta();
            List<String> lore = new ArrayList<>(meta.hasLore() ? meta.getLore() : List.of());
            lore.add(p.label("t_7962c873a565") + price);
            lore.add(p.label("t_5198a658d226") + str(row, "seller"));
            meta.setLore(lore);
            item.setItemMeta(meta);
            m.inventory.setItem(i, item);
            m.buttons.put(
                i++,
                () ->
                    confirm(
                        player,
                        mine ? p.label("t_b5b91075745a") : p.label("t_8caa8abe6322") + price + "?",
                        () -> {
                          p.check(player, mine ? "auction.sell" : "auction.buy");
                          p.action(
                              player,
                              () -> {
                                if (mine) p.ledger.cancelLot(id, owner, false);
                                else
                                  p.ledger.buyLot(
                                      UUID.randomUUID().toString(),
                                      owner,
                                      id,
                                      p.getConfig().getInt("auction.fee-bps"));
                              },
                              () -> auction(player, query, mine, page));
                        },
                        () -> auction(player, query, mine, page)));
          }
          pages(m, player, page, n -> auction(player, query, mine, n));
          put(
              m,
              46,
              "auction_lots", Material.CHEST,
              mine ? p.label("t_5211cfddf9bc") : p.label("t_33890521090d"),
              List.of(),
              () -> auction(player, query, !mine, 0));
          put(
              m,
              52,
              "search", Material.COMPASS,
              p.label("t_323424efa7ff"),
              List.of(p.label("t_921a828b76f5"), "/twm economy auction mine"),
              () -> p.tell(player, "auction-help"));
          put(
              m,
              53,
              "auction_sell", Material.GOLD_INGOT,
              p.label("t_2db4318c9ff4"),
              List.of(p.label("t_fc321d282b69"), p.label("t_d92c7542fb65")),
              () -> p.tell(player, "auction-help"));
          show(player, m);
        },
        e -> p.error(player, e));
  }

  public void trade(Player player) {
    p.check(player, "trade");
    p.module("trade");
    String owner = player.getUniqueId().toString();
    p.async(
        () -> p.ledger.trade(owner),
        t -> {
          String id = str(t, "id"), a = str(t, "a"), b = str(t, "b");
          if (str(t, "state").equals("REQUEST")) {
            Page m = page(player, p.text("trade"), () -> home(player), "Обмен с другим игроком.", "", "1. Положите вещь из руки и деньги.", "2. Оба нажимают «Подтвердить».", "Любое изменение снимает подтверждения.");
            if (b.equals(owner))
              put(
                  m,
                  20,
                  "confirm", Material.LIME_CONCRETE,
                  p.label("t_f03c7e4857ec"),
                  List.of(p.label("t_8c12119492bd") + a),
                  () -> {
                    if (!p.near(a, b))
                      throw new IllegalArgumentException(p.label("t_d0ac7639d856"));
                    p.action(player, () -> p.ledger.acceptTrade(id, owner), () -> trade(player));
                  });
            put(
                m,
                24,
                "close", Material.RED_CONCRETE,
                p.label("t_5729190a2794"),
                List.of(),
                () -> p.action(player, () -> p.ledger.cancelTrade(id), () -> home(player)));
            show(player, m);
            return;
          }
          p.async(
              () ->
                  List.of(
                      p.ledger.assets(a, "TRADE:" + id),
                      p.ledger.assets(b, "TRADE:" + id),
                      p.ledger.offers(id)),
              lists -> {
                Page m = page(player, p.text("trade"), () -> home(player), "Обмен с другим игроком.", "", "1. Положите вещь из руки и деньги.", "2. Оба нажимают «Подтвердить».", "Любое изменение снимает подтверждения.");
                for (int side = 0; side < 2; side++) {
                  int slot = side == 0 ? 0 : 18;
                  for (var item : lists.get(side)) {
                    String itemOwner = side == 0 ? a : b;
                    item(
                        m,
                        slot++,
                        item,
                        () -> {
                          if (owner.equals(itemOwner))
                            p.action(
                                player,
                                () -> p.ledger.removeOfferItem(id, owner, str(item, "id")),
                                () -> trade(player));
                        });
                  }
                }
                List<String> money = new ArrayList<>();
                for (var o : lists.get(2))
                  money.add(
                      (str(o, "owner").equals(owner)
                              ? p.label("t_a2b421b7688f")
                              : p.label("t_db07c32ed395"))
                          + p.ledger.currency(str(o, "currency")).format(num(o, "amount")));
                money.add(p.label("t_d842f5047177"));
                put(
                    m,
                    31,
                    "economy", Material.GOLD_INGOT,
                    p.label("t_cbedd94fece8"),
                    money,
                    () -> p.tell(player, "trade-money-help"));
                put(
                    m,
                    10,
                    "trade_side", Material.PAPER,
                    p.label("t_24515e463433"),
                    List.of(
                        a,
                        p.label("t_fc633f5336a1")
                            + (num(t, "confirm_a") == num(t, "revision")
                                ? p.label("t_cdc5ed90cb4b")
                                : p.label("t_dc9337ff5b42"))),
                    null);
                put(
                    m,
                    28,
                    "trade_side", Material.PAPER,
                    p.label("t_5abb1f58a118"),
                    List.of(
                        b,
                        p.label("t_fc633f5336a1")
                            + (num(t, "confirm_b") == num(t, "revision")
                                ? p.label("t_cdc5ed90cb4b")
                                : p.label("t_dc9337ff5b42"))),
                    null);
                put(
                    m,
                    38,
                    "deposit", Material.HOPPER,
                    p.label("t_aad5555e132c"),
                    List.of(),
                    () -> {
                      player.closeInventory();
                      p.custody.deposit(
                          player,
                          true,
                          asset ->
                              p.action(
                                  player,
                                  () -> p.ledger.offerItem(id, owner, asset),
                                  () -> trade(player)));
                    });
                put(
                    m,
                    40,
                    "confirm", Material.LIME_CONCRETE,
                    p.label("t_6bc730d76f2f"),
                    List.of(p.label("t_92d87b801a6c")),
                    () -> {
                      if (!p.near(a, b))
                        throw new IllegalArgumentException(p.label("t_d0ac7639d856"));
                      p.action(
                          player,
                          () -> p.ledger.confirmTrade(id, owner, num(t, "revision")),
                          () -> trade(player));
                    });
                put(
                    m,
                    42,
                    "close", Material.RED_CONCRETE,
                    p.label("t_cb78c0469932"),
                    List.of(p.label("t_c5e7de6610f1")),
                    () -> p.action(player, () -> p.ledger.cancelTrade(id), () -> home(player)));
                put(
                    m,
                    53,
                    "refresh", Material.CLOCK,
                    p.label("t_f66fe5595fee"),
                    List.of(),
                    () -> trade(player));
                show(player, m);
              },
              e -> p.error(player, e));
        },
        e -> p.error(player, e));
  }

  public void history(Player player, int page, boolean items) {
    p.check(player, "history");
    String owner = player.getUniqueId().toString();
    p.async(
        () -> items ? p.ledger.auditItems(owner, page) : p.ledger.history(owner, page),
        rows -> {
          Page m = page(player, p.text("history"), () -> home(player), "Последние операции с деньгами:", "переводы, покупки, почта.");
          int i = 0;
          for (var row : rows)
            put(
                m,
                i++,
                "history_row", Material.PAPER,
                items ? str(row, "action") : str(row, "reason"),
                items
                    ? List.of(str(row, "asset"), new Date(num(row, "created")).toString())
                    : List.of(
                        (num(row, "delta") < 0 ? "−" : "+")
                            + p.ledger
                                .currency(str(row, "currency"))
                                .format(Math.abs(num(row, "delta"))),
                        p.label("t_dc0f493acbca")
                            + p.ledger.currency(str(row, "currency")).format(num(row, "balance")),
                        p.label("t_98d3eccf6858") + str(row, "op")),
                null);
          pages(m, player, page, n -> history(player, n, items));
          show(player, m);
        },
        e -> p.error(player, e));
  }

  private static List<String> wrap(String s, int n) {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < s.length(); i += n) out.add(s.substring(i, Math.min(s.length(), i + n)));
    return out;
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void click(InventoryClickEvent e) {
    if (!(e.getView().getTopInventory().getHolder() instanceof Page m)) return;
    e.setCancelled(true);
    if (!(e.getWhoClicked() instanceof Player player)
        || !m.owner.equals(player.getUniqueId())
        || p.custody.busy.contains(player.getUniqueId())) return;
    if (!m.active || e.getClick() != ClickType.LEFT || e.getRawSlot() < 0 || e.getRawSlot() >= 54)
      return;
    Runnable r = m.buttons.get(e.getRawSlot());
    if (r != null) {
      m.active = false;
      try {
        r.run();
      } catch (Throwable ex) {
        p.error(player, ex);
      }
      Bukkit.getScheduler().runTaskLater(p, () -> m.active = true, 5);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void drag(InventoryDragEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof Page) e.setCancelled(true);
  }
}
