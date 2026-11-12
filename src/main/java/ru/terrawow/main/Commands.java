package ru.terrawow.main;

import static ru.terrawow.main.Database.*;

import java.util.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public final class Commands implements CommandExecutor, TabCompleter {
  private final TerraWoWmain p;
  private final Map<UUID, List<String>> attachments = new HashMap<>();

  public Commands(TerraWoWmain p) {
    this.p = p;
  }

  private Player player(CommandSender s) {
    if (s instanceof Player pl) return pl;
    throw new IllegalArgumentException("Команда только для игрока");
  }

  private static String join(String[] a, int from) {
    return String.join(" ", Arrays.copyOfRange(a, from, a.length));
  }

  private static void need(String[] a, int n) {
    if (a.length < n) throw new IllegalArgumentException("Недостаточно аргументов. /twm economy help");
  }

  private void update(CommandSender s, Runnable r) {
    p.action(s, r, () -> {});
  }

  @Override
  public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
    try {
      if (a.length == 0) {
        p.menus.home(player(s));
        return true;
      }
      String sub = a[0].toLowerCase(Locale.ROOT);
      String op = UUID.randomUUID().toString();
      switch (sub) {
        case "help" -> {
          for (String line : p.getConfig().getStringList("help"))
            s.sendMessage(ChatColor.translateAlternateColorCodes('&', line));
        }
        case "balance", "wallet", "currencies" -> p.menus.wallet(player(s));
        case "pay" -> {
          p.check(s, "pay");
          need(a, 4);
          Player pl = player(s);
          String owner = pl.getUniqueId().toString(), c = a[2];
          long n = p.amount(s, c, join(a, 3));
          Ledger.positive(n);
          long fee = Money.fee(n, p.getConfig().getInt("transfer.fee-bps"));
          if (n < p.getConfig().getLong("transfer.minimum"))
            throw new IllegalArgumentException("Сумма ниже минимума");
          p.async(
              () -> p.ledger.resolve(a[1]),
              to ->
                  p.menus.confirm(
                      pl,
                      "Отправить "
                          + p.ledger.currency(c).format(n)
                          + "; комиссия "
                          + p.ledger.currency(c).format(fee),
                      () ->
                          p.action(
                              s,
                              () -> p.ledger.transfer(op, owner, to, c, n, fee),
                              () -> {
                                Player target = Bukkit.getPlayer(UUID.fromString(to));
                                if (target != null)
                                  target.sendMessage(
                                      p.text("received")
                                          .replace("{amount}", p.ledger.currency(c).format(n)));
                              }),
                      () -> p.menus.wallet(pl)),
              e -> p.error(s, e));
        }
        case "exchange" -> {
          p.check(s, "exchange");
          need(a, 4);
          Player pl = player(s);
          String owner = pl.getUniqueId().toString(), from = a[1], to = a[2];
          p.currencyPermission(s, to);
          long n = p.amount(s, from, join(a, 3));
          var r = p.getConfig().getConfigurationSection("exchange." + from + "." + to);
          if (r == null) throw new IllegalArgumentException("Обмен этой пары отключён");
          long num = r.getLong("numerator"), den = r.getLong("denominator");
          int fee = r.getInt("fee-bps");
          long out = Money.exchange(n, num, den, fee);
          p.menus.confirm(
              pl,
              "Обменять на " + p.ledger.currency(to).format(out),
              () -> update(s, () -> p.ledger.exchange(op, owner, from, to, n, num, den, fee)),
              () -> p.menus.wallet(pl));
        }
        case "history" ->
            p.menus.history(player(s), a.length > 1 ? Integer.parseInt(a[1]) - 1 : 0, false);
        case "xp" -> {
          p.module("xp");
          Player pl = player(s);
          p.access(pl, "bank");
          need(a, 2);
          if (a[1].equals("balance"))
            p.async(
                () -> p.ledger.xpBalance(pl.getUniqueId().toString()),
                n -> pl.sendMessage("Банк обычного XP: " + n),
                e -> p.error(pl, e));
          else {
            need(a, 3);
            if (!List.of("deposit", "withdraw").contains(a[1]))
              throw new IllegalArgumentException("xp balance/deposit/withdraw");
            new XpBank(p).move(pl, Integer.parseInt(a[2]), a[1].equals("deposit"));
          }
        }
        case "xp-recovery" -> {
          p.check(s, "admin.recovery");
          if (s instanceof Player) throw new IllegalArgumentException("Только консоль");
          if (a.length == 1)
            p.async(
                p.ledger::xpPending,
                rows -> rows.forEach(r -> s.sendMessage(r.toString())),
                e -> p.error(s, e));
          else {
            need(a, 3);
            if (!List.of("applied", "not-applied").contains(a[2]))
              throw new IllegalArgumentException("applied/not-applied");
            update(s, () -> p.ledger.xpFinish(a[1], a[2].equals("applied"), true));
          }
        }
        case "bank" -> bank(player(s), a, op);
        case "mail" -> mail(player(s), a, op);
        case "trade" -> trade(player(s), a);
        case "auction" -> auction(player(s), a, op);
        case "deposit" -> {
          Player pl = player(s);
          p.custody.deposit(
              pl,
              false,
              id -> {
                s.sendMessage("§aПредмет сохранён: " + id);
                p.menus.inbox(pl, 0);
              });
        }
        case "inbox" -> p.menus.inbox(player(s), 0);
        case "withdraw" -> {
          Player pl = player(s);
          need(a, 2);
          p.custody.withdraw(pl, a[1], () -> p.tell(s, "done"));
        }
        case "give", "take", "set" -> {
          p.check(s, "admin.money");
          need(a, 4);
          String c = a[2];
          long n = p.amount(s, c, join(a, 3));
          p.async(
              () -> p.ledger.resolve(a[1]),
              owner -> update(s, () -> p.ledger.admin(op, owner, c, n, sub)),
              e -> p.error(s, e));
        }
        case "reward-item" -> {
          p.check(s, "admin.reward");
          need(a, 5);
          UUID request = UUID.fromString(a[1]);
          ItemStack item = Icons.create(a[3]);
          int n = Integer.parseInt(a[4]);
          if (n < 1 || n > item.getMaxStackSize())
            throw new IllegalArgumentException("Неверный размер стопки");
          item.setAmount(n);
          byte[] bytes = item.serializeAsBytes();
          p.async(
              () -> p.ledger.resolve(a[2]),
              owner ->
                  update(
                      s,
                      () ->
                          p.ledger.rewardItems(
                              request.toString(), owner, "Награда", List.of(bytes))),
              e -> p.error(s, e));
        }
        case "reward" -> {
          p.check(s, "admin.reward");
          need(a, 6);
          UUID request = UUID.fromString(a[1]);
          String c = a[3];
          long n = p.amount(s, c, a[4]);
          p.async(
              () -> p.ledger.resolve(a[2]),
              owner ->
                  update(
                      s,
                      () ->
                          p.ledger.sendMail(
                              request.toString(),
                              "Система",
                              owner,
                              a[5],
                              join(a, 6),
                              c,
                              n,
                              0,
                              List.of(),
                              0,
                              0,
                              30L * 86400000,
                              true)),
              e -> p.error(s, e));
        }
        case "npc" -> {
          p.check(s, "admin.npc");
          need(a, 3);
          Player pl = Bukkit.getPlayerExact(a[2]);
          if (pl == null) throw new IllegalArgumentException("Игрок офлайн");
          if (a[1].equals("bank")) new EconomyApi(p).openBank(pl);
          else if (a[1].equals("mail")) new EconomyApi(p).openMail(pl);
          else throw new IllegalArgumentException("bank/mail");
        }
        case "diagnose" -> {
          p.check(s, "admin.diagnose");
          p.async(
              p.ledger::diagnostics,
              result -> {
                s.sendMessage(result);
                s.sendMessage(
                    "Vault: "
                        + Bukkit.getServicesManager()
                            .getRegistrations(net.milkbowl.vault.economy.Economy.class)
                            .stream()
                            .map(r -> r.getProvider().getName())
                            .toList());
              },
              e -> p.error(s, e));
        }
        case "backup" -> {
          p.check(s, "admin.backup");
          java.nio.file.Path path =
              p.getDataFolder()
                  .toPath()
                  .resolve(
                      "backup-"
                          + System.currentTimeMillis()
                          + (p.ledger.db.remote() ? ".sql" : ".sqlite"));
          update(s, () -> p.ledger.db.backup(path));
          s.sendMessage("Путь копии: " + path);
        }
        case "recovery" -> {
          p.check(s, "admin.recovery");
          if (s instanceof Player)
            throw new IllegalArgumentException("Восстановление выполняется только из консоли");
          if (a.length == 1)
            p.async(
                p.ledger::pending,
                rows -> rows.forEach(r -> s.sendMessage(r.toString())),
                e -> p.error(s, e));
          else {
            need(a, 3);
            if (!List.of("database", "inventory").contains(a[2]))
              throw new IllegalArgumentException(
                  "database / inventory — подтвердите фактическое местоположение предмета");
            update(s, () -> p.ledger.resolveAsset(a[1], a[2].equals("database"), s.getName()));
          }
        }
        case "cheque" -> cheque(player(s), a, op);
        case "guild" -> guild(player(s), a, op);
        default -> throw new IllegalArgumentException("Неизвестная команда. /twm economy help");
      }
    } catch (Throwable e) {
      p.error(s, e);
    }
    return true;
  }

  private void bank(Player pl, String[] a, String op) {
    p.access(pl, "bank");
    String owner = pl.getUniqueId().toString();
    if (a.length == 1) {
      p.menus.bank(pl);
      return;
    }
    switch (a[1]) {
      case "open" -> {
        need(a, 3);
        p.menus.bankTab(pl, Integer.parseInt(a[2]));
      }
      case "rename" -> {
        p.check(pl, "bank.rename");
        need(a, 5);
        Material icon = Material.matchMaterial(a[3]);
        if (icon == null || !icon.isItem() || icon.isAir())
          throw new IllegalArgumentException("Неверная иконка");
        String name = join(a, 4);
        update(pl, () -> p.ledger.renameTab(owner, Integer.parseInt(a[2]), name, icon.name()));
      }
      case "deposit", "withdraw" -> {
        p.module("bank-money");
        p.check(pl, "bank.money");
        need(a, 4);
        String c = a[2];
        long n = p.amount(pl, c, join(a, 3));
        p.menus.confirm(
            pl,
            (a[1].equals("deposit") ? "Внести: " : "Снять: ") + p.ledger.currency(c).format(n),
            () -> update(pl, () -> p.ledger.bankMoney(op, owner, c, n, a[1].equals("deposit"))),
            () -> p.menus.bank(pl));
      }
      case "balance" -> {
        p.module("bank-money");
        for (var c : p.ledger.currencies.values())
          pl.sendMessage(c.name() + ": " + c.format(p.ledger.cached("bank:" + owner, c.id())));
      }
      case "store" -> {
        need(a, 4);
        int tab = Integer.parseInt(a[2]);
        update(pl, () -> p.ledger.storeBank(owner, a[3], tab));
      }
      default ->
          throw new IllegalArgumentException("bank open/rename/deposit/withdraw/balance/store");
    }
  }

  private void mail(Player pl, String[] a, String op) {
    p.access(pl, "mail");
    String owner = pl.getUniqueId().toString();
    if (a.length == 1) {
      p.menus.mailbox(pl, 0);
      return;
    }
    switch (a[1]) {
      case "attach" -> {
        p.check(pl, "mail.send");
        need(a, 3);
        String id = a[2];
        p.async(
            () -> p.ledger.asset(owner, id),
            row -> {
              if (!str(row, "place").equals("INBOX"))
                throw new IllegalArgumentException("Предмет должен быть в получении");
              ItemRules.check(
                  ItemStack.deserializeBytes((byte[]) row.get("data")), pl.getUniqueId(), true);
              var list = attachments.computeIfAbsent(pl.getUniqueId(), k -> new ArrayList<>());
              if (list.size() >= 27 || list.contains(id))
                throw new IllegalArgumentException("Уже добавлено или больше 27 вложений");
              list.add(id);
              pl.sendMessage("Добавлено вложение " + id);
            },
            e -> p.error(pl, e));
      }
      case "clear" -> {
        attachments.remove(pl.getUniqueId());
        p.tell(pl, "done");
      }
      case "send" -> {
        p.check(pl, "mail.send");
        need(a, 7);
        String c = a[3];
        long money = p.amount(pl, c, a[4]), cod = p.amount(pl, c, a[5]);
        String[] text = join(a, 6).split("\\|", 2);
        String subject = text[0], body = text.length > 1 ? text[1] : "";
        List<String> items = List.copyOf(attachments.getOrDefault(pl.getUniqueId(), List.of()));
        long fee = p.getConfig().getLong("mail.fee"),
            delay = p.getConfig().getLong("mail.delay-seconds") * 1000,
            lifetime = p.getConfig().getLong("mail.lifetime-days") * 86400000;
        p.async(
            () -> p.ledger.resolve(a[2]),
            to ->
                p.menus.confirm(
                    pl,
                    "Отправить письмо; комиссия " + p.ledger.currency("gold").format(fee),
                    () ->
                        p.action(
                            pl,
                            () ->
                                p.ledger.sendMail(
                                    op, owner, to, subject, body, c, money, cod, items, fee, delay,
                                    lifetime, false),
                            () -> {
                              attachments.remove(pl.getUniqueId());
                              Player receiver = Bukkit.getPlayer(UUID.fromString(to));
                              if (receiver != null)
                                receiver.sendMessage(p.text("mail-sent-notice"));
                            }),
                    () -> p.menus.mailbox(pl, 0)),
            e -> p.error(pl, e));
      }
      case "read" -> {
        need(a, 3);
        p.menus.letter(pl, a[2]);
      }
      default -> throw new IllegalArgumentException("mail attach/clear/send/read");
    }
  }

  private void trade(Player pl, String[] a) {
    p.check(pl, "trade");
    p.module("trade");
    String owner = pl.getUniqueId().toString();
    if (a.length == 1) {
      p.menus.trade(pl);
      return;
    }
    if (List.of("money", "cancel", "accept").contains(a[1])) {
      p.async(
          () -> p.ledger.trade(owner),
          t -> {
            String id = str(t, "id");
            switch (a[1]) {
              case "cancel" -> update(pl, () -> p.ledger.cancelTrade(id));
              case "accept" -> {
                if (!p.near(str(t, "a"), str(t, "b")))
                  throw new IllegalArgumentException("Игрок слишком далеко");
                p.action(pl, () -> p.ledger.acceptTrade(id, owner), () -> p.menus.trade(pl));
              }
              case "money" -> {
                need(a, 4);
                String c = a[2];
                long n = p.amount(pl, c, join(a, 3));
                p.action(pl, () -> p.ledger.offerMoney(id, owner, c, n), () -> p.menus.trade(pl));
              }
            }
          },
          e -> p.error(pl, e));
      return;
    }
    Player target = Bukkit.getPlayerExact(a[1]);
    if (target == null || !p.near(owner, target.getUniqueId().toString()))
      throw new IllegalArgumentException("Игрок недоступен или слишком далеко");
    String to = target.getUniqueId().toString();
    p.action(
        pl,
        () ->
            p.ledger.requestTrade(
                UUID.randomUUID().toString(),
                owner,
                to,
                p.getConfig().getLong("trade.timeout-seconds") * 1000),
        () -> {
          target.sendMessage("§eЗапрос торговли от " + pl.getName() + ". /twm economy trade accept");
          p.menus.trade(pl);
        });
  }

  private void auction(Player pl, String[] a, String op) {
    p.check(pl, "auction");
    p.module("auction");
    if (a.length == 1) {
      p.menus.auction(pl, "", false, 0);
      return;
    }
    switch (a[1]) {
      case "search" -> {
        need(a, 3);
        p.menus.auction(pl, join(a, 2), false, 0);
      }
      case "mine" -> p.menus.auction(pl, "", true, 0);
      case "sell" -> {
        p.check(pl, "auction.sell");
        need(a, 3);
        long price = p.amount(pl, "gold", join(a, 2));
        Ledger.positive(price);
        String owner = pl.getUniqueId().toString();
        long deposit = p.getConfig().getLong("auction.deposit"),
            duration = p.getConfig().getLong("auction.duration-hours") * 3600000;
        ItemRules.check(pl.getInventory().getItemInMainHand(), pl.getUniqueId(), true);
        p.menus.confirm(
            pl,
            "Выкуп "
                + p.ledger.currency("gold").format(price)
                + "; залог "
                + p.ledger.currency("gold").format(deposit),
            () ->
                p.custody.deposit(
                    pl,
                    true,
                    asset ->
                        p.action(
                            pl,
                            () -> p.ledger.listLot(op, owner, asset, price, deposit, duration),
                            () -> p.menus.auction(pl, "", true, 0))),
            () -> p.menus.auction(pl, "", false, 0));
      }
      default -> throw new IllegalArgumentException("auction search/mine/sell");
    }
  }

  private void guild(Player pl, String[] a, String op) {
    p.module("guild");
    p.check(pl, "guild");
    need(a, 3);
    String owner = pl.getUniqueId().toString(), g = a[2];
    switch (a[1]) {
      case "items" ->
          p.async(
              () -> p.ledger.guildItems(g, owner),
              rows -> rows.forEach(r -> pl.sendMessage(str(r, "id") + " " + str(r, "label"))),
              e -> p.error(pl, e));
      case "store" -> {
        need(a, 4);
        p.async(
            () -> p.ledger.asset(owner, a[3]),
            row -> {
              ItemRules.check(
                  ItemStack.deserializeBytes((byte[]) row.get("data")), pl.getUniqueId(), true);
              update(
                  pl,
                  () ->
                      p.ledger.guildItem(
                          g, owner, a[3], true, p.getConfig().getLong("guild.daily-items")));
            },
            e -> p.error(pl, e));
      }
      case "take" -> {
        need(a, 4);
        update(
            pl,
            () ->
                p.ledger.guildItem(
                    g, owner, a[3], false, p.getConfig().getLong("guild.daily-items")));
      }
      case "create" -> {
        p.check(pl, "guild.create");
        update(pl, () -> p.ledger.guildCreate(g, owner));
      }
      case "role" -> {
        need(a, 5);
        p.async(
            () -> p.ledger.resolve(a[3]),
            member ->
                update(
                    pl, () -> p.ledger.guildRole(g, owner, member, a[4].toUpperCase(Locale.ROOT))),
            e -> p.error(pl, e));
      }
      case "balance" -> {
        need(a, 4);
        p.async(
            () -> p.ledger.guildBalance(g, owner, a[3]),
            n -> pl.sendMessage(p.ledger.currency(a[3]).format(n)),
            e -> p.error(pl, e));
      }
      case "deposit", "withdraw" -> {
        need(a, 5);
        String c = a[3];
        long n = p.amount(pl, c, join(a, 4));
        p.menus.confirm(
            pl,
            "Общий банк " + g + ": " + p.ledger.currency(c).format(n),
            () ->
                update(
                    pl,
                    () ->
                        p.ledger.guildMoney(
                            op,
                            g,
                            owner,
                            c,
                            n,
                            a[1].equals("deposit"),
                            p.getConfig().getLong("guild.daily-limit"))),
            () -> p.menus.home(pl));
      }
      default -> throw new IllegalArgumentException("guild create/role/balance/deposit/withdraw");
    }
  }

  private void cheque(Player pl, String[] a, String op) {
    p.module("physical");
    p.check(pl, "cheque");
    need(a, 2);
    String owner = pl.getUniqueId().toString();
    NamespacedKey key = new NamespacedKey(p, "cheque");
    if (a[1].equals("redeem")) {
      ItemStack item = pl.getInventory().getItemInMainHand();
      if (!item.hasItemMeta()) throw new IllegalArgumentException("Это не чек");
      var pdc = item.getItemMeta().getPersistentDataContainer();
      String id = pdc.get(key, PersistentDataType.STRING);
      if (id == null) throw new IllegalArgumentException("Это не чек");
      update(pl, () -> p.ledger.redeemVoucher(id, owner));
      return;
    }
    if (!a[1].equals("issue")) throw new IllegalArgumentException("cheque issue/redeem");
    need(a, 4);
    String c = a[2];
    long n = p.amount(pl, c, join(a, 3));
    Ledger.positive(n);
    ItemStack check = new ItemStack(Material.PAPER);
    ItemMeta meta = check.getItemMeta();
    meta.setDisplayName("§6Банковский чек");
    meta.setLore(
        List.of("§e" + p.ledger.currency(c).format(n), "§7/twm economy cheque redeem", "§8" + op));
    meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, op);
    check.setItemMeta(meta);
    byte[] bytes = check.serializeAsBytes();
    p.menus.confirm(
        pl,
        "Вывести " + p.ledger.currency(c).format(n),
        () ->
            p.action(
                pl,
                () -> {
                  p.ledger.issueVoucherAsset(op, owner, c, n, bytes);
                },
                () -> p.menus.inbox(pl, 0)),
        () -> p.menus.wallet(pl));
  }

  public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
    List<String> values;
    if (a.length <= 1)
      values =
          List.of(
              "help",
              "balance",
              "pay",
              "exchange",
              "bank",
              "mail",
              "trade",
              "auction",
              "history",
              "deposit",
              "inbox",
              "withdraw",
              "cheque",
              "give",
              "take",
              "set",
              "reward",
              "diagnose",
              "backup",
              "recovery",
              "npc");
    else if (a.length == 2)
      values =
          switch (a[0]) {
            case "bank" -> List.of("open", "rename", "store", "deposit", "withdraw", "balance");
            case "mail" -> List.of("send", "attach", "clear", "read");
            case "auction" -> List.of("sell", "search", "mine");
            case "cheque" -> List.of("issue", "redeem");
            default -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
          };
    else values = new ArrayList<>(p.ledger.currencies.keySet());
    String prefix = a[a.length - 1].toLowerCase(Locale.ROOT);
    return values.stream().filter(v -> v.startsWith(prefix)).toList();
  }
}
