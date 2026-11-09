package ru.azeroth.economy;

import static ru.azeroth.economy.Database.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Durable economy and escrow domain. Methods accept immutable IDs and serialized bytes only. */
public final class Ledger {
  public final Database db;
  public final Map<String, Money.Currency> currencies;
  private final Map<String, Long> cache = new ConcurrentHashMap<>();
  public int maxPlayerMails = 100, maxCustodyItems = 500, maxListings = 50;

  public record Change(
      String operation, String owner, String currency, long delta, long balance, String reason) {}

  public java.util.function.Consumer<List<Change>> committed = changes -> {};

  public record Entry(String owner, String currency, long delta) {}

  public Ledger(Database db, Map<String, Money.Currency> currencies) {
    this.db = db;
    this.currencies = Collections.unmodifiableMap(new LinkedHashMap<>(currencies));
    db.tx(
        d -> {
          for (var currency : currencies.values()) {
            var stored =
                d.rows("SELECT precision_digits FROM currency_schema WHERE id=?", currency.id());
            if (!stored.isEmpty()
                && num(stored.getFirst(), "precision_digits") != currency.precision())
              throw new IllegalStateException(
                  "Изменена точность валюты " + currency.id() + "; нужна явная миграция");
            d.exec(
                "INSERT OR IGNORE INTO currency_schema VALUES(?,?)",
                currency.id(),
                currency.precision());
          }
          for (var r : d.rows("SELECT * FROM accounts")) {
            String currency = str(r, "currency");
            if (!currencies.containsKey(currency) && num(r, "amount") > 0)
              throw new IllegalStateException("Удалена валюта с остатками: " + currency);
            cache.put(str(r, "owner") + "/" + currency, num(r, "amount"));
          }
          return null;
        });
  }

  public Money.Currency currency(String id) {
    var c = currencies.get(id);
    if (c == null) throw new IllegalArgumentException("Неизвестная валюта: " + id);
    return c;
  }

  public long cached(String owner, String c) {
    return cache.getOrDefault(owner + "/" + c, 0L);
  }

  private final ThreadLocal<Map<String, Long>> changed = new ThreadLocal<>();
  private final ThreadLocal<List<Change>> changes = new ThreadLocal<>();

  private <T> T tx(Database.Work<T> work) {
    Map<String, Long> delta = new HashMap<>();
    changed.set(delta);
    changes.set(new ArrayList<>());
    try {
      return db.tx(
          work,
          () -> {
            cache.putAll(delta);
            try {
              committed.accept(List.copyOf(changes.get()));
            } catch (RuntimeException ignored) {
              /* Notification must not change the committed outcome. */
            }
          });
    } finally {
      changed.remove();
      changes.remove();
    }
  }

  private long bal(String owner, String c) throws Exception {
    currency(c);
    var r = db.rows("SELECT amount FROM accounts WHERE owner=? AND currency=?", owner, c);
    return r.isEmpty() ? 0 : num(r.getFirst(), "amount");
  }

  private void post(String op, String owner, String c, long delta, String reason) throws Exception {
    if (delta == 0) {
      currency(c);
      return;
    }
    long value = Math.addExact(bal(owner, c), delta);
    if (value < 0) throw new IllegalArgumentException("Недостаточно средств");
    // Escrows may aggregate many players; player and bank limits apply to actual accounts.
    if (!owner.startsWith("$") && value > currency(c).limit())
      throw new IllegalArgumentException("Достигнут предел накопления");
    db.exec(
        "INSERT INTO accounts VALUES(?,?,?) ON CONFLICT(owner,currency) DO UPDATE SET"
            + " amount=excluded.amount",
        owner,
        c,
        value);
    db.exec(
        "INSERT INTO ledger(op,owner,currency,delta,balance,reason,created) VALUES(?,?,?,?,?,?,?)",
        op,
        owner,
        c,
        delta,
        value,
        reason,
        now());
    changed.get().put(owner + "/" + c, value);
    changes.get().add(new Change(op, owner, c, delta, value, reason));
  }

  private boolean begin(String id, String fingerprint) throws Exception {
    if (id == null || id.isBlank() || id.length() > 200)
      throw new IllegalArgumentException("Неверный ID операции");
    var r = db.rows("SELECT fingerprint FROM operations WHERE id=?", id);
    if (!r.isEmpty()) {
      if (!fingerprint.equals(str(r.getFirst(), "fingerprint")))
        throw new IllegalArgumentException("ID повторён с другими параметрами");
      return false;
    }
    db.exec("INSERT INTO operations VALUES(?,?,?)", id, fingerprint, now());
    return true;
  }

  public void register(UUID owner, String name) {
    tx(
        d -> {
          d.exec(
              "INSERT INTO players VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name",
              owner.toString(),
              name);
          for (var c : currencies.values())
            if (begin("initial:" + owner + ":" + c.id(), "initial"))
              post("initial:" + owner, owner.toString(), c.id(), c.start(), "Стартовый баланс");
          d.exec("INSERT OR IGNORE INTO tabs VALUES(?,0,'Основная','CHEST',9)", owner.toString());
          return null;
        });
  }

  public String resolve(String name) {
    try {
      return UUID.fromString(name).toString();
    } catch (IllegalArgumentException ignored) {
    }
    return db.tx(
        d -> str(d.one("SELECT uuid FROM players WHERE lower(name)=lower(?)", name), "uuid"));
  }

  public boolean has(String owner) {
    return db.tx(d -> d.number("SELECT count(*) FROM players WHERE uuid=?", owner) > 0);
  }

  public void transfer(String op, String from, String to, String c, long n, long fee) {
    positive(n);
    if (fee < 0 || from.equals(to) || from.startsWith("$") || to.startsWith("$"))
      throw new IllegalArgumentException("Неверный перевод");
    if (!currency(c).transfer())
      throw new IllegalArgumentException("Переводы этой валюты запрещены");
    tx(
        d -> {
          if (!begin(op, "pay|" + from + "|" + to + "|" + c + "|" + n + "|" + fee)) return null;
          post(op, from, c, -Math.addExact(n, fee), "Перевод");
          post(op, to, c, n, "Перевод");
          if (fee > 0) post(op, "$fees", c, fee, "Комиссия");
          return null;
        });
  }

  public void admin(String op, String owner, String c, long n, String mode) {
    if (n < 0) throw new IllegalArgumentException("Отрицательная сумма");
    tx(
        d -> {
          if (!begin(op, "admin|" + owner + "|" + c + "|" + n + "|" + mode)) return null;
          long delta =
              switch (mode) {
                case "give" -> n;
                case "take" -> -n;
                case "set" -> Math.subtractExact(n, bal(owner, c));
                default -> throw new IllegalArgumentException("give/take/set");
              };
          post(op, owner, c, delta, "Администратор: " + mode);
          return null;
        });
  }

  public void exchange(
      String op,
      String owner,
      String from,
      String to,
      long n,
      long numerator,
      long denominator,
      int fee) {
    positive(n);
    if (from.equals(to)) throw new IllegalArgumentException("Одинаковые валюты");
    long out = Money.exchange(n, numerator, denominator, fee);
    positive(out);
    tx(
        d -> {
          if (!begin(op, "exchange|" + owner + "|" + from + "|" + to + "|" + n + "|" + out))
            return null;
          post(op, owner, from, -n, "Обмен валют: уничтожение");
          post(op, owner, to, out, "Обмен валют: создание");
          return null;
        });
  }

  public void bankMoney(String op, String owner, String c, long n, boolean deposit) {
    positive(n);
    tx(
        d -> {
          if (!begin(op, "bank|" + owner + "|" + c + "|" + n + "|" + deposit)) return null;
          String from = deposit ? owner : "bank:" + owner, to = deposit ? "bank:" + owner : owner;
          post(op, from, c, -n, "Банковский счёт");
          post(op, to, c, n, "Банковский счёт");
          return null;
        });
  }

  public List<Map<String, Object>> history(String owner, int page) {
    return db.tx(
        d ->
            d.rows(
                "SELECT * FROM ledger WHERE owner=? ORDER BY seq DESC LIMIT 45 OFFSET ?",
                owner,
                offset(page)));
  }

  public List<Map<String, Object>> tabs(String owner) {
    return db.tx(d -> d.rows("SELECT * FROM tabs WHERE owner=? ORDER BY tab", owner));
  }

  public void buyTab(String op, String owner, long price, int max) {
    tx(
        d -> {
          if (!begin(op, "tab|" + owner + "|" + price)) return null;
          long count = d.number("SELECT count(*) FROM tabs WHERE owner=?", owner);
          if (count >= max) throw new IllegalArgumentException("Максимум вкладок");
          charge(op, owner, price, "Покупка вкладки");
          d.exec(
              "INSERT INTO tabs VALUES(?,?,?,'CHEST',9)", owner, count, "Вкладка " + (count + 1));
          return null;
        });
  }

  public void expand(String op, String owner, int tab, long price) {
    tx(
        d -> {
          if (!begin(op, "slots|" + owner + "|" + tab + "|" + price)) return null;
          var r = d.one("SELECT * FROM tabs WHERE owner=? AND tab=?", owner, tab);
          if (num(r, "slots") >= 45) throw new IllegalArgumentException("Все ячейки открыты");
          charge(op, owner, price, "Покупка 9 ячеек");
          d.exec("UPDATE tabs SET slots=slots+9 WHERE owner=? AND tab=?", owner, tab);
          return null;
        });
  }

  public void renameTab(String owner, int tab, String name, String icon) {
    if (name.length() > 32 || name.isBlank())
      throw new IllegalArgumentException("Название: 1–32 символа");
    db.tx(
        d -> {
          if (d.exec(
                  "UPDATE tabs SET name=?,icon=? WHERE owner=? AND tab=?", name, icon, owner, tab)
              != 1) throw new IllegalArgumentException("Нет вкладки");
          return null;
        });
  }

  private void charge(String op, String owner, long price, String reason) throws Exception {
    if (price < 0) throw new IllegalArgumentException("Неверная цена");
    post(op, owner, "gold", -price, reason);
    post(op, "$fees", "gold", price, reason);
  }

  public List<Map<String, Object>> assets(String owner, String place) {
    return db.tx(
        d ->
            d.rows(
                "SELECT * FROM assets WHERE owner=? AND place=? AND state='READY' ORDER BY"
                    + " created,id",
                owner,
                place));
  }

  public Map<String, Object> asset(String owner, String id) {
    return db.tx(
        d -> d.one("SELECT * FROM assets WHERE owner=? AND id=? AND state='READY'", owner, id));
  }

  public void prepareIn(String id, String owner, byte[] bytes, String label) {
    if (bytes.length == 0 || bytes.length > 2_000_000)
      throw new IllegalArgumentException("Размер предмета");
    db.tx(
        d -> {
          ensureClear(owner);
          if (d.number(
                  "SELECT count(*) FROM assets WHERE owner=? AND state NOT IN"
                      + " ('CLAIMED','ABORTED')",
                  owner)
              >= maxCustodyItems) throw new IllegalArgumentException("Лимит предметов на хранении");
          d.exec(
              "INSERT INTO assets VALUES(?,?,'INBOX',?,?,'IN_PREPARED',?)",
              id,
              owner,
              bytes,
              label,
              now());
          audit(id, owner, "IN_PREPARED");
          return null;
        });
  }

  public void confirmIn(String id, String owner) {
    db.tx(
        d -> {
          if (d.exec(
                  "UPDATE assets SET state='READY' WHERE id=? AND owner=? AND state='IN_PREPARED'",
                  id,
                  owner)
              != 1) throw new IllegalArgumentException("Состояние внесения изменилось");
          audit(id, owner, "IN_SAVED");
          return null;
        });
  }

  public void abortIn(String id, String owner) {
    db.tx(
        d -> {
          d.exec(
              "UPDATE assets SET state='ABORTED' WHERE id=? AND owner=? AND state='IN_PREPARED'",
              id,
              owner);
          audit(id, owner, "IN_ABORTED_BEFORE_MUTATION");
          return null;
        });
  }

  public Map<String, Object> reserveOut(String id, String owner) {
    return db.tx(
        d -> {
          ensureClear(owner);
          var r =
              d.one(
                  "SELECT * FROM assets WHERE id=? AND owner=? AND state='READY' AND (place='INBOX'"
                      + " OR place LIKE 'BANK:%')",
                  id, owner);
          d.exec("UPDATE assets SET state='OUT_PREPARED' WHERE id=?", id);
          audit(id, owner, "OUT_PREPARED");
          return r;
        });
  }

  public void finishOut(String id, String owner, boolean delivered) {
    db.tx(
        d -> {
          if (d.exec(
                  "UPDATE assets SET state=? WHERE id=? AND owner=? AND state='OUT_PREPARED'",
                  delivered ? "CLAIMED" : "READY",
                  id,
                  owner)
              != 1) throw new IllegalArgumentException("Состояние получения изменилось");
          audit(id, owner, delivered ? "OUT_SAVED" : "OUT_ABORTED_BEFORE_MUTATION");
          return null;
        });
  }

  private void ensureClear(String owner) throws Exception {
    if (db.number(
            "SELECT count(*) FROM assets WHERE owner=? AND state IN"
                + " ('IN_PREPARED','OUT_PREPARED','QUARANTINE_IN','QUARANTINE_OUT')",
            owner)
        > 0)
      throw new IllegalArgumentException("Есть незавершённый перенос: обратитесь к администратору");
  }

  private void audit(String id, String owner, String action) throws Exception {
    db.exec(
        "INSERT INTO item_journal(asset,owner,action,created) VALUES(?,?,?,?)",
        id,
        owner,
        action,
        now());
  }

  public void recover() {
    db.tx(
        d -> {
          d.exec("UPDATE assets SET state='QUARANTINE_IN' WHERE state='IN_PREPARED'");
          d.exec("UPDATE assets SET state='QUARANTINE_OUT' WHERE state='OUT_PREPARED'");
          d.exec("UPDATE xp_operations SET state='QUARANTINE' WHERE state='PREPARED'");
          return null;
        });
    for (var t : db.tx(d -> d.rows("SELECT id FROM trades WHERE state IN ('REQUEST','OPEN')")))
      cancelTrade(str(t, "id"));
  }

  public List<Map<String, Object>> pending() {
    return db.tx(
        d ->
            d.rows(
                "SELECT id,owner,place,state,label FROM assets WHERE state LIKE '%PREPARED' OR"
                    + " state LIKE 'QUARANTINE%'"));
  }

  public void resolveAsset(String id, boolean databaseOwns, String operator) {
    db.tx(
        d -> {
          var r = d.one("SELECT * FROM assets WHERE id=? AND state LIKE 'QUARANTINE%'", id);
          d.exec(
              "UPDATE assets SET state=?,place='INBOX' WHERE id=?",
              databaseOwns ? "READY" : "ABORTED",
              id);
          audit(id, str(r, "owner"), "MANUAL:" + operator + ":" + databaseOwns);
          return null;
        });
  }

  public void storeBank(String owner, String id, int tab) {
    db.tx(
        d -> {
          var t = d.one("SELECT * FROM tabs WHERE owner=? AND tab=?", owner, tab);
          String place = "BANK:" + tab;
          if (d.number(
                  "SELECT count(*) FROM assets WHERE owner=? AND place=? AND state NOT IN"
                      + " ('CLAIMED','ABORTED')",
                  owner,
                  place)
              >= num(t, "slots")) throw new IllegalArgumentException("Вкладка заполнена");
          if (d.exec(
                  "UPDATE assets SET place=? WHERE owner=? AND id=? AND state='READY' AND"
                      + " place='INBOX'",
                  place,
                  owner,
                  id)
              != 1) throw new IllegalArgumentException("Предмет недоступен");
          audit(id, owner, "BANK:" + tab);
          return null;
        });
  }

  public void sendMail(
      String id,
      String sender,
      String recipient,
      String subject,
      String body,
      String c,
      long money,
      long cod,
      List<String> assets,
      long fee,
      long delay,
      long lifetime,
      boolean reward) {
    if (money < 0
        || cod < 0
        || fee < 0
        || delay < 0
        || lifetime <= 0
        || subject.length() > 80
        || body.length() > 1000
        || assets.size() > 27
        || new HashSet<>(assets).size() != assets.size())
      throw new IllegalArgumentException("Неверное письмо");
    if (cod > 0 && (money > 0 || assets.isEmpty()))
      throw new IllegalArgumentException("Наложенный платёж: только письмо с предметами без денег");
    if (!reward && !currency(c).transfer() && (money > 0 || cod > 0))
      throw new IllegalArgumentException("Валюта не передаётся");
    tx(
        d -> {
          if (!begin(
              id,
              "mail|" + sender + "|" + recipient + "|" + subject + "|" + body + "|" + c + "|"
                  + money + "|" + cod + "|" + assets + "|" + reward)) return null;
          if (!reward
              && d.number(
                      "SELECT count(*) FROM mails WHERE recipient=? AND state='OPEN'", recipient)
                  >= maxPlayerMails)
            throw new IllegalArgumentException("Почтовый ящик получателя заполнен");
          if (!reward) {
            post(id, sender, c, -money, "Вложение в письмо");
            charge(id, sender, fee, "Почтовая комиссия");
          }
          post(
              id, "$mail:" + id, c, money, reward ? "Награда: создание" : "Почтовое депонирование");
          insertMail(id, sender, recipient, subject, body, c, money, cod, delay, lifetime);
          for (String asset : assets) {
            if (d.exec(
                    "UPDATE assets SET owner=?,place=? WHERE id=? AND owner=? AND place='INBOX' AND"
                        + " state='READY'",
                    recipient,
                    "MAIL:" + id,
                    asset,
                    sender)
                != 1) throw new IllegalArgumentException("Вложение недоступно");
            audit(asset, recipient, "MAIL:" + id);
          }
          return null;
        });
  }

  private void insertMail(
      String id,
      String sender,
      String recipient,
      String subject,
      String body,
      String c,
      long money,
      long cod,
      long delay,
      long lifetime)
      throws Exception {
    long deliver = Math.addExact(now(), delay);
    db.exec(
        "INSERT INTO"
            + " mails(id,sender,recipient,subject,body,currency,money,cod,deliver,expires,state)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,'OPEN')",
        id,
        sender,
        recipient,
        subject,
        body,
        c,
        money,
        cod,
        deliver,
        Math.addExact(deliver, lifetime));
  }

  private static final long RETURN_LIFE = 3650L * 86400000;

  private String systemMail(String id, String recipient, String subject, String c, long money)
      throws Exception {
    insertMail(
        id, "Система", recipient, subject, "Автоматическая доставка", c, money, 0, 0, RETURN_LIFE);
    return id;
  }

  public List<Map<String, Object>> mails(String recipient, int page) {
    return db.tx(
        d ->
            d.rows(
                "SELECT * FROM mails WHERE recipient=? AND state='OPEN' AND deliver<=? ORDER BY"
                    + " deliver DESC LIMIT 45 OFFSET ?",
                recipient,
                now(),
                offset(page)));
  }

  public Map<String, Object> mail(String recipient, String id) {
    return db.tx(
        d ->
            d.one(
                "SELECT * FROM mails WHERE id=? AND recipient=? AND state='OPEN' AND deliver<=?",
                id,
                recipient,
                now()));
  }

  public void archiveMail(String id, String recipient) {
    db.tx(
        d -> {
          var m =
              d.one(
                  "SELECT * FROM mails WHERE id=? AND recipient=? AND state='OPEN'", id, recipient);
          if (num(m, "money") > 0
              || d.number(
                      "SELECT count(*) FROM assets WHERE place=? AND state='READY'", "MAIL:" + id)
                  > 0)
            throw new IllegalArgumentException("Сначала заберите вложения или верните письмо");
          d.exec("UPDATE mails SET state='CLOSED' WHERE id=?", id);
          return null;
        });
  }

  /**
   * COD covers the whole parcel and is settled once before any attachment is released into custody.
   */
  public void claimMail(String op, String recipient, String id, String selected) {
    tx(
        d -> {
          if (!begin(op, "claim|" + recipient + "|" + id + "|" + selected)) return null;
          var m =
              d.one(
                  "SELECT * FROM mails WHERE id=? AND recipient=? AND state='OPEN' AND deliver<=?"
                      + " AND (expires>? OR returned=1)",
                  id,
                  recipient,
                  now(),
                  now());
          String c = str(m, "currency");
          if (num(m, "cod") > 0 && num(m, "paid") == 0) {
            long cod = num(m, "cod");
            post(op, recipient, c, -cod, "Наложенный платёж");
            String payment = "cod:" + id;
            post(op, "$mail:" + payment, c, cod, "Оплата отправителю");
            systemMail(payment, str(m, "sender"), "Оплата наложенного платежа", c, cod);
            d.exec("UPDATE mails SET paid=1 WHERE id=?", id);
          }
          if (selected.equals("all") || selected.equals("money")) {
            long n = num(m, "money");
            post(op, "$mail:" + id, c, -n, "Получение письма");
            post(op, recipient, c, n, "Получение письма");
            d.exec("UPDATE mails SET money=0 WHERE id=?", id);
          }
          if (selected.equals("all"))
            d.exec(
                "UPDATE assets SET place='INBOX' WHERE owner=? AND place=? AND state='READY'",
                recipient,
                "MAIL:" + id);
          else if (!selected.equals("money")
              && d.exec(
                      "UPDATE assets SET place='INBOX' WHERE id=? AND owner=? AND place=? AND"
                          + " state='READY'",
                      selected,
                      recipient,
                      "MAIL:" + id)
                  != 1) throw new IllegalArgumentException("Вложение уже получено");
          audit(id, recipient, "MAIL_CLAIM:" + selected);
          return null;
        });
  }

  public void returnMail(String id, String recipient) {
    tx(
        d -> {
          var m =
              d.one(
                  "SELECT * FROM mails WHERE id=? AND recipient=? AND state='OPEN'", id, recipient);
          String sender = str(m, "sender");
          if (!isUuid(sender) || num(m, "returned") == 1)
            throw new IllegalArgumentException("Возврат невозможен; вложения остаются доступны");
          d.exec(
              "UPDATE mails SET"
                  + " sender=?,recipient=?,cod=0,paid=0,returned=1,deliver=?,expires=?,subject=?"
                  + " WHERE id=?",
              recipient,
              sender,
              now(),
              now() + RETURN_LIFE,
              "Возврат: " + str(m, "subject"),
              id);
          d.exec("UPDATE assets SET owner=? WHERE place=? AND state='READY'", sender, "MAIL:" + id);
          audit(id, sender, "MAIL_RETURN");
          return null;
        });
  }

  public void expireMail() {
    for (var m :
        db.tx(
            d ->
                d.rows(
                    "SELECT id,recipient FROM mails WHERE state='OPEN' AND returned=0 AND"
                        + " expires<=?",
                    now()))) {
      try {
        returnMail(str(m, "id"), str(m, "recipient"));
      } catch (IllegalArgumentException e) {
        db.tx(
            d -> {
              d.exec(
                  "UPDATE mails SET expires=?,returned=1 WHERE id=?",
                  now() + RETURN_LIFE,
                  str(m, "id"));
              return null;
            });
      }
    }
  }

  public void listLot(
      String id, String seller, String asset, long price, long deposit, long duration) {
    positive(price);
    if (deposit < 0 || duration <= 0) throw new IllegalArgumentException("Неверное размещение");
    tx(
        d -> {
          if (!begin(id, "lot|" + seller + "|" + asset + "|" + price + "|" + deposit)) return null;
          if (d.number("SELECT count(*) FROM lots WHERE seller=? AND state='OPEN'", seller)
              >= maxListings) throw new IllegalArgumentException("Достигнут лимит лотов");
          post(id, seller, "gold", -deposit, "Залог аукциона");
          post(id, "$lot:" + id, "gold", deposit, "Залог аукциона");
          if (d.exec(
                  "UPDATE assets SET place=? WHERE id=? AND owner=? AND place='INBOX' AND"
                      + " state='READY'",
                  "LOT:" + id,
                  asset,
                  seller)
              != 1) throw new IllegalArgumentException("Предмет недоступен");
          d.exec(
              "INSERT INTO lots VALUES(?,?,?,?,?,?,'OPEN',NULL)",
              id,
              seller,
              asset,
              price,
              deposit,
              Math.addExact(now(), duration));
          audit(asset, seller, "LOT:" + id);
          return null;
        });
  }

  public List<Map<String, Object>> lots(String query, String seller, int page) {
    return db.tx(
        d ->
            d.rows(
                "SELECT l.*,a.data,a.label FROM lots l JOIN assets a ON l.asset=a.id WHERE"
                    + " l.state='OPEN' AND l.expires>? AND lower(a.label) LIKE ? AND (?='' OR"
                    + " l.seller=?) ORDER BY l.expires LIMIT 45 OFFSET ?",
                now(),
                "%" + query.toLowerCase(Locale.ROOT) + "%",
                seller,
                seller,
                offset(page)));
  }

  public void buyLot(String op, String buyer, String id, int feeBps) {
    tx(
        d -> {
          if (!begin(op, "buy|" + buyer + "|" + id)) return null;
          var l = d.one("SELECT * FROM lots WHERE id=? AND state='OPEN' AND expires>?", id, now());
          String seller = str(l, "seller");
          if (seller.equals(buyer)) throw new IllegalArgumentException("Это ваш лот");
          long price = num(l, "price"), deposit = num(l, "deposit"), fee = Money.fee(price, feeBps);
          post(op, buyer, "gold", -price, "Покупка лота");
          String pay = "sale:" + id;
          post(op, "$lot:" + id, "gold", -deposit, "Возврат залога");
          post(op, "$mail:" + pay, "gold", Math.addExact(price - fee, deposit), "Выручка аукциона");
          post(op, "$fees", "gold", fee, "Комиссия аукциона");
          systemMail(
              pay, seller, "Продажа на аукционе", "gold", Math.addExact(price - fee, deposit));
          String delivery = systemMail("purchase:" + id, buyer, "Покупка на аукционе", "gold", 0);
          d.exec(
              "UPDATE assets SET owner=?,place=? WHERE id=?",
              buyer,
              "MAIL:" + delivery,
              str(l, "asset"));
          d.exec("UPDATE lots SET state='SOLD',buyer=? WHERE id=?", buyer, id);
          audit(str(l, "asset"), buyer, "LOT_SOLD:" + id);
          return null;
        });
  }

  public void cancelLot(String id, String seller, boolean expired) {
    tx(
        d -> {
          var l = d.one("SELECT * FROM lots WHERE id=? AND seller=? AND state='OPEN'", id, seller);
          long dep = num(l, "deposit");
          post("cancel:" + id, "$lot:" + id, "gold", -dep, "Удержание залога");
          post("cancel:" + id, "$fees", "gold", dep, "Удержание залога");
          String mail =
              systemMail(
                  "return-lot:" + id,
                  seller,
                  expired ? "Срок лота истёк" : "Лот отменён",
                  "gold",
                  0);
          d.exec("UPDATE assets SET place=? WHERE id=?", "MAIL:" + mail, str(l, "asset"));
          d.exec("UPDATE lots SET state='CANCELLED' WHERE id=?", id);
          audit(str(l, "asset"), seller, "LOT_RETURN");
          return null;
        });
  }

  public void expireLots() {
    for (var l :
        db.tx(d -> d.rows("SELECT id,seller FROM lots WHERE state='OPEN' AND expires<=?", now())))
      cancelLot(str(l, "id"), str(l, "seller"), true);
  }

  public void requestTrade(String id, String a, String b, long lifetime) {
    if (a.equals(b)) throw new IllegalArgumentException("Нельзя торговать с собой");
    db.tx(
        d -> {
          if (d.number(
                  "SELECT count(*) FROM trades WHERE state IN ('REQUEST','OPEN') AND (a IN (?,?) OR"
                      + " b IN (?,?))",
                  a,
                  b,
                  a,
                  b)
              > 0) throw new IllegalArgumentException("Участник уже занят торговлей");
          d.exec(
              "INSERT INTO trades(id,a,b,state,expires) VALUES(?,?,?,'REQUEST',?)",
              id,
              a,
              b,
              now() + lifetime);
          return null;
        });
  }

  public Map<String, Object> trade(String owner) {
    return db.tx(
        d ->
            d.one(
                "SELECT * FROM trades WHERE (a=? OR b=?) AND state IN ('REQUEST','OPEN')",
                owner,
                owner));
  }

  public void acceptTrade(String id, String b) {
    db.tx(
        d -> {
          if (d.exec(
                  "UPDATE trades SET state='OPEN' WHERE id=? AND b=? AND state='REQUEST' AND"
                      + " expires>?",
                  id,
                  b,
                  now())
              != 1) throw new IllegalArgumentException("Запрос недоступен");
          return null;
        });
  }

  private Map<String, Object> openTrade(String id, String owner) throws Exception {
    return db.one(
        "SELECT * FROM trades WHERE id=? AND (a=? OR b=?) AND state='OPEN' AND expires>?",
        id,
        owner,
        owner,
        now());
  }

  private void changedTrade(String id) throws Exception {
    db.exec("UPDATE trades SET revision=revision+1,confirm_a=-1,confirm_b=-1 WHERE id=?", id);
  }

  public void offerMoney(String id, String owner, String c, long n) {
    if (n < 0 || !currency(c).trade()) throw new IllegalArgumentException("Валюта не торгуется");
    tx(
        d -> {
          openTrade(id, owner);
          var r =
              d.rows(
                  "SELECT amount FROM offers WHERE trade_id=? AND owner=? AND currency=?",
                  id,
                  owner,
                  c);
          long old = r.isEmpty() ? 0 : num(r.getFirst(), "amount"),
              diff = Math.subtractExact(n, old);
          post(UUID.randomUUID().toString(), owner, c, -diff, "Предложение торговли");
          post(
              UUID.randomUUID().toString(),
              "$trade:" + id + ":" + owner,
              c,
              diff,
              "Торговое депонирование");
          d.exec(
              "INSERT INTO offers VALUES(?,?,?,?) ON CONFLICT(trade_id,owner,currency) DO UPDATE"
                  + " SET amount=excluded.amount",
              id,
              owner,
              c,
              n);
          changedTrade(id);
          return null;
        });
  }

  public void offerItem(String id, String owner, String asset) {
    db.tx(
        d -> {
          openTrade(id, owner);
          if (d.number(
                  "SELECT count(*) FROM assets WHERE place=? AND owner=? AND state='READY'",
                  "TRADE:" + id,
                  owner)
              >= 9) throw new IllegalArgumentException("Не более 9 предметов с каждой стороны");
          if (d.exec(
                  "UPDATE assets SET place=? WHERE id=? AND owner=? AND place='INBOX' AND"
                      + " state='READY'",
                  "TRADE:" + id,
                  asset,
                  owner)
              != 1) throw new IllegalArgumentException("Предмет недоступен");
          changedTrade(id);
          audit(asset, owner, "TRADE_OFFER");
          return null;
        });
  }

  public void removeOfferItem(String id, String owner, String asset) {
    db.tx(
        d -> {
          openTrade(id, owner);
          if (d.exec(
                  "UPDATE assets SET place='INBOX' WHERE id=? AND owner=? AND place=? AND"
                      + " state='READY'",
                  asset,
                  owner,
                  "TRADE:" + id)
              != 1) throw new IllegalArgumentException("Нет предложения");
          changedTrade(id);
          return null;
        });
  }

  public List<Map<String, Object>> offers(String id) {
    return db.tx(d -> d.rows("SELECT * FROM offers WHERE trade_id=?", id));
  }

  public long confirmTrade(String id, String owner) {
    return db.tx(
        d -> {
          var t = openTrade(id, owner);
          String col = str(t, "a").equals(owner) ? "confirm_a" : "confirm_b";
          d.exec("UPDATE trades SET " + col + "=revision WHERE id=?", id);
          return num(t, "revision");
        });
  }

  public long confirmTrade(String id, String owner, long expected) {
    return db.tx(
        d -> {
          var t = openTrade(id, owner);
          if (num(t, "revision") != expected)
            throw new IllegalArgumentException(
                "Предложение изменилось. Обновите меню и проверьте его заново");
          String col = str(t, "a").equals(owner) ? "confirm_a" : "confirm_b";
          d.exec("UPDATE trades SET " + col + "=revision WHERE id=?", id);
          return expected;
        });
  }

  public void completeTrade(String id, long revision) {
    tx(
        d -> {
          var t =
              d.one(
                  "SELECT * FROM trades WHERE id=? AND state='OPEN' AND expires>? AND revision=?"
                      + " AND confirm_a=revision AND confirm_b=revision",
                  id,
                  now(),
                  revision);
          String a = str(t, "a"), b = str(t, "b");
          String ma = systemMail("trade:" + id + ":a", a, "Результат торговли", "gold", 0),
              mb = systemMail("trade:" + id + ":b", b, "Результат торговли", "gold", 0);
          for (var r : d.rows("SELECT * FROM offers WHERE trade_id=?", id)) {
            String owner = str(r, "owner"), c = str(r, "currency");
            long n = num(r, "amount");
            post(id, "$trade:" + id + ":" + owner, c, -n, "Торговля завершена");
            post(id, owner.equals(a) ? b : a, c, n, "Торговля завершена");
          }
          for (var r :
              d.rows(
                  "SELECT id,owner FROM assets WHERE place=? AND state='READY'", "TRADE:" + id)) {
            String to = str(r, "owner").equals(a) ? b : a;
            d.exec(
                "UPDATE assets SET owner=?,place=? WHERE id=?",
                to,
                "MAIL:" + (to.equals(a) ? ma : mb),
                str(r, "id"));
            audit(str(r, "id"), to, "TRADE_COMPLETE");
          }
          d.exec("UPDATE trades SET state='DONE' WHERE id=?", id);
          return null;
        });
  }

  public void cancelTrade(String id) {
    tx(
        d -> {
          var list = d.rows("SELECT * FROM trades WHERE id=? AND state IN ('REQUEST','OPEN')", id);
          if (list.isEmpty()) return null;
          for (var r : d.rows("SELECT * FROM offers WHERE trade_id=?", id)) {
            String owner = str(r, "owner"), c = str(r, "currency");
            long n = num(r, "amount");
            String mail = "trade-return:" + id + ":" + owner + ":" + c;
            post(id, "$trade:" + id + ":" + owner, c, -n, "Отмена торговли");
            post(id, "$mail:" + mail, c, n, "Возврат торговли");
            systemMail(mail, owner, "Возврат торговли", c, n);
          }
          d.exec("UPDATE assets SET place='INBOX' WHERE place=? AND state='READY'", "TRADE:" + id);
          d.exec("UPDATE trades SET state='CANCELLED' WHERE id=?", id);
          return null;
        });
  }

  public List<Map<String, Object>> activeTrades() {
    return db.tx(d -> d.rows("SELECT * FROM trades WHERE state IN ('REQUEST','OPEN')"));
  }

  public void issueVoucher(String id, String owner, String c, long n) {
    positive(n);
    if (!currency(c).physical()) throw new IllegalArgumentException("Вывод валюты запрещён");
    tx(
        d -> {
          if (!begin(id, "voucher|" + owner + "|" + c + "|" + n)) return null;
          post(id, owner, c, -n, "Выдача чека");
          post(id, "$voucher:" + id, c, n, "Депонирование чека");
          d.exec("INSERT INTO vouchers VALUES(?,?,?,'ISSUED')", id, c, n);
          return null;
        });
  }

  public void issueVoucherAsset(String id, String owner, String c, long n, byte[] bytes) {
    positive(n);
    if (!currency(c).physical()) throw new IllegalArgumentException("Вывод валюты запрещён");
    tx(
        d -> {
          if (!begin(id, "voucher-asset|" + owner + "|" + c + "|" + n)) return null;
          post(id, owner, c, -n, "Выдача чека");
          post(id, "$voucher:" + id, c, n, "Депонирование чека");
          d.exec("INSERT INTO vouchers VALUES(?,?,?,'ISSUED')", id, c, n);
          d.exec(
              "INSERT INTO assets VALUES(?,?,'INBOX',?,'CHEQUE','READY',?)",
              id,
              owner,
              bytes,
              now());
          audit(id, owner, "CHEQUE_ISSUED");
          return null;
        });
  }

  public void rewardItems(String id, String recipient, String subject, List<byte[]> items) {
    if (items.isEmpty() || items.size() > 27)
      throw new IllegalArgumentException("Награда: 1–27 стопок");
    List<String> hashes = new ArrayList<>();
    try {
      for (byte[] bytes : items) {
        if (bytes.length > 2_000_000) throw new IllegalArgumentException("Предмет слишком большой");
        hashes.add(
            HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
      }
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    tx(
        d -> {
          if (!begin(id, "reward-items|" + recipient + "|" + subject + "|" + hashes)) return null;
          systemMail(id, recipient, subject, "gold", 0);
          int i = 0;
          for (byte[] bytes : items) {
            String asset = id + ":" + (i++);
            d.exec(
                "INSERT INTO assets VALUES(?,?,?,?,?,'READY',?)",
                asset,
                recipient,
                "MAIL:" + id,
                bytes,
                "REWARD",
                now());
            audit(asset, recipient, "REWARD_CREATED");
          }
          return null;
        });
  }

  public void redeemVoucher(String id, String owner) {
    tx(
        d -> {
          var v = d.one("SELECT * FROM vouchers WHERE id=? AND state='ISSUED'", id);
          String c = str(v, "currency");
          long n = num(v, "amount");
          post("redeem:" + id, "$voucher:" + id, c, -n, "Погашение чека");
          post("redeem:" + id, owner, c, n, "Погашение чека");
          d.exec("UPDATE vouchers SET state='REDEEMED' WHERE id=?", id);
          return null;
        });
  }

  public void scheduled(String runId, String from, String to, String c, long n) {
    positive(n);
    tx(
        d -> {
          if (!begin("schedule:" + runId, from + "|" + to + "|" + c + "|" + n)) return null;
          if (!from.equals("MINT")) post(runId, from, c, -n, "Платёж по расписанию");
          post(runId, to, c, n, "Платёж по расписанию");
          d.exec("INSERT INTO schedule_runs VALUES(?,?)", runId, now());
          return null;
        });
  }

  public void interest(String runId, int bps, long maximum) {
    if (bps < 0 || bps > 10000 || maximum < 0)
      throw new IllegalArgumentException("Неверные проценты");
    tx(
        d -> {
          if (!begin("interest:" + runId, "interest|" + bps + "|" + maximum)) return null;
          for (var r : d.rows("SELECT * FROM accounts WHERE owner LIKE 'bank:%'")) {
            String owner = str(r, "owner"), c = str(r, "currency");
            long current = num(r, "amount"),
                gain =
                    Math.min(
                        maximum, Money.exchange(Math.max(1, current), Math.max(1, bps), 10000, 0));
            if (current == 0 || bps == 0) gain = 0;
            gain = Math.min(gain, Math.max(0, currency(c).limit() - current));
            if (gain > 0) post(runId, owner, c, gain, "Проценты: создание");
          }
          d.exec("INSERT INTO schedule_runs VALUES(?,?)", "interest:" + runId, now());
          return null;
        });
  }

  public void guildCreate(String guild, String owner) {
    if (!guild.matches("[a-z0-9_]{1,32}"))
      throw new IllegalArgumentException("ID банка: a-z, 0-9, _ до 32 символов");
    db.tx(
        d -> {
          if (d.number("SELECT count(*) FROM guild_roles WHERE guild=?", guild) > 0)
            throw new IllegalArgumentException("Банк уже существует");
          d.exec("INSERT INTO guild_roles VALUES(?,?,'OWNER')", guild, owner);
          return null;
        });
  }

  public void guildRole(String guild, String actor, String member, String role) {
    if (!List.of("OFFICER", "MEMBER", "REMOVE").contains(role))
      throw new IllegalArgumentException("OFFICER/MEMBER/REMOVE");
    db.tx(
        d -> {
          guildPermission(guild, actor, "OWNER");
          if (actor.equals(member)) throw new IllegalArgumentException("Нельзя снять владельца");
          d.exec("DELETE FROM guild_roles WHERE guild=? AND owner=?", guild, member);
          if (!role.equals("REMOVE"))
            d.exec("INSERT INTO guild_roles VALUES(?,?,?)", guild, member, role);
          return null;
        });
  }

  private String guildPermission(String guild, String owner, String required) throws Exception {
    String role =
        str(db.one("SELECT role FROM guild_roles WHERE guild=? AND owner=?", guild, owner), "role");
    if (required.equals("OWNER") && !role.equals("OWNER")
        || required.equals("OFFICER") && role.equals("MEMBER"))
      throw new IllegalArgumentException("Недостаточная роль в общем банке");
    return role;
  }

  public long guildBalance(String guild, String owner, String c) {
    return db.tx(
        d -> {
          guildPermission(guild, owner, "MEMBER");
          return bal("guild:" + guild, c);
        });
  }

  public void guildMoney(
      String op, String guild, String owner, String c, long n, boolean deposit, long dailyLimit) {
    positive(n);
    if (dailyLimit < 0 || !currency(c).trade())
      throw new IllegalArgumentException("Валюта запрещена или неверный лимит");
    tx(
        d -> {
          if (!begin(op, "guild|" + guild + "|" + owner + "|" + c + "|" + n + "|" + deposit))
            return null;
          String role = guildPermission(guild, owner, deposit ? "MEMBER" : "OFFICER");
          if (!deposit && !role.equals("OWNER")) {
            long day = now() / 86400000;
            var rows =
                d.rows(
                    "SELECT amount FROM guild_usage WHERE guild=? AND owner=? AND day=?",
                    guild,
                    owner,
                    day);
            long used = rows.isEmpty() ? 0 : num(rows.getFirst(), "amount");
            long total = Math.addExact(used, n);
            if (total > dailyLimit)
              throw new IllegalArgumentException("Дневной лимит общего банка");
            d.exec(
                "INSERT INTO guild_usage VALUES(?,?,?,?) ON CONFLICT(guild,owner,day) DO UPDATE SET"
                    + " amount=excluded.amount",
                guild,
                owner,
                day,
                total);
          }
          post(op, deposit ? owner : "guild:" + guild, c, -n, "Общий банк " + guild);
          post(op, deposit ? "guild:" + guild : owner, c, n, "Общий банк " + guild);
          return null;
        });
  }

  public List<Map<String, Object>> guildItems(String guild, String owner) {
    return db.tx(
        d -> {
          guildPermission(guild, owner, "MEMBER");
          return d.rows(
              "SELECT * FROM assets WHERE owner=? AND place='GUILD' AND state='READY'",
              "guild:" + guild);
        });
  }

  public void guildItem(String guild, String owner, String asset, boolean deposit, long limit) {
    db.tx(
        d -> {
          String role = guildPermission(guild, owner, deposit ? "MEMBER" : "OFFICER");
          if (!deposit && !role.equals("OWNER")) {
            long day = now() / 86400000;
            var r =
                d.rows(
                    "SELECT amount FROM guild_usage WHERE guild=? AND owner=? AND day=?",
                    guild + ":items",
                    owner,
                    day);
            long used = r.isEmpty() ? 0 : num(r.getFirst(), "amount");
            if (used >= limit) throw new IllegalArgumentException("Дневной лимит предметов");
            d.exec(
                "INSERT INTO guild_usage VALUES(?,?,?,?) ON CONFLICT(guild,owner,day) DO UPDATE SET"
                    + " amount=excluded.amount",
                guild + ":items",
                owner,
                day,
                used + 1);
          }
          if (d.exec(
                  "UPDATE assets SET owner=?,place=? WHERE id=? AND owner=? AND place=? AND"
                      + " state='READY'",
                  deposit ? "guild:" + guild : owner,
                  deposit ? "GUILD" : "INBOX",
                  asset,
                  deposit ? owner : "guild:" + guild,
                  deposit ? "INBOX" : "GUILD")
              != 1) throw new IllegalArgumentException("Предмет недоступен");
          audit(asset, owner, "GUILD:" + guild + ":" + deposit);
          return null;
        });
  }

  public long xpBalance(String owner) {
    return db.tx(
        d -> {
          var r = d.rows("SELECT amount FROM xp_accounts WHERE owner=?", owner);
          return r.isEmpty() ? 0 : num(r.getFirst(), "amount");
        });
  }

  public void xpPrepare(
      String id, String owner, int before, int after, int n, boolean deposit, long limit) {
    if (n <= 0
        || before < 0
        || after < 0
        || after != (deposit ? Math.subtractExact(before, n) : Math.addExact(before, n)))
      throw new IllegalArgumentException("Неверный перенос XP");
    db.tx(
        d -> {
          if (d.number(
                  "SELECT count(*) FROM xp_operations WHERE owner=? AND state IN"
                      + " ('PREPARED','QUARANTINE')",
                  owner)
              > 0) throw new IllegalArgumentException("Есть незавершённая операция XP");
          var r = d.rows("SELECT amount FROM xp_accounts WHERE owner=?", owner);
          long balance = r.isEmpty() ? 0 : num(r.getFirst(), "amount");
          if (deposit && Math.addExact(balance, n) > limit || !deposit && balance < n)
            throw new IllegalArgumentException("Недостаточно XP или превышен предел");
          d.exec(
              "INSERT INTO xp_accounts VALUES(?,?) ON CONFLICT(owner) DO UPDATE SET"
                  + " amount=excluded.amount",
              owner,
              deposit ? balance : balance - n);
          d.exec(
              "INSERT INTO xp_operations VALUES(?,?,?,?,?,?, 'PREPARED')",
              id,
              owner,
              n,
              before,
              after,
              deposit ? 1 : 0);
          audit(id, owner, "XP_PREPARED:" + n);
          return null;
        });
  }

  public void xpFinish(String id, boolean applied, boolean recovery) {
    db.tx(
        d -> {
          var r =
              d.one(
                  "SELECT * FROM xp_operations WHERE id=? AND state=?",
                  id,
                  recovery ? "QUARANTINE" : "PREPARED");
          boolean deposit = num(r, "deposit") == 1;
          long delta =
              deposit ? (applied ? num(r, "amount") : 0) : (applied ? 0 : num(r, "amount"));
          d.exec("UPDATE xp_accounts SET amount=amount+? WHERE owner=?", delta, str(r, "owner"));
          d.exec("UPDATE xp_operations SET state=? WHERE id=?", applied ? "DONE" : "ABORTED", id);
          audit(
              id,
              str(r, "owner"),
              "XP_" + (recovery ? "MANUAL_" : "") + (applied ? "APPLIED" : "ABORTED"));
          return null;
        });
  }

  public List<Map<String, Object>> xpPending() {
    return db.tx(
        d -> d.rows("SELECT * FROM xp_operations WHERE state IN ('PREPARED','QUARANTINE')"));
  }

  public List<Map<String, Object>> auditItems(String owner, int page) {
    return db.tx(
        d ->
            d.rows(
                "SELECT * FROM item_journal WHERE owner=? ORDER BY seq DESC LIMIT 45 OFFSET ?",
                owner,
                offset(page)));
  }

  public String diagnostics() {
    return db.tx(
        d ->
            "integrity="
                + d.integrity()
                + ", schema="
                + d.number("SELECT max(version) FROM schema_version")
                + ", operations="
                + d.number("SELECT count(*) FROM operations")
                + ", pending="
                + d.number(
                    "SELECT count(*) FROM assets WHERE state LIKE '%PREPARED' OR state LIKE"
                        + " 'QUARANTINE%'"));
  }

  public static long now() {
    return System.currentTimeMillis();
  }

  public static boolean isUuid(String s) {
    try {
      UUID.fromString(s);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  public static void positive(long n) {
    if (n <= 0) throw new IllegalArgumentException("Сумма должна быть положительной");
  }

  public static int offset(int page) {
    if (page < 0 || page > 1000000) throw new IllegalArgumentException("Неверная страница");
    return Math.multiplyExact(page, 45);
  }
}
