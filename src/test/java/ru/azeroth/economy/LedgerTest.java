package ru.azeroth.economy;

import static org.junit.jupiter.api.Assertions.*;
import static ru.azeroth.economy.Database.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class LedgerTest {
  @TempDir Path dir;
  Database db;
  Ledger l;
  String a = UUID.randomUUID().toString(),
      b = UUID.randomUUID().toString(),
      c = UUID.randomUUID().toString();

  Map<String, Money.Currency> currencies() {
    return Map.of(
        "gold",
        new Money.Currency(
            "gold", "Монеты", "м", "GOLD_NUGGET", 0, 0, 1_000_000, true, true, true, ""),
        "honor",
        new Money.Currency(
            "honor", "Честь", "ч", "IRON_SWORD", 0, 0, 10000, false, false, false, ""));
  }

  @BeforeEach
  void setup() throws Exception {
    db = new Database(dir.resolve("test.db"));
    l = new Ledger(db, currencies());
    for (String id : List.of(a, b, c)) l.register(UUID.fromString(id), id);
    l.admin("seed", a, "gold", 10000, "give");
  }

  @AfterEach
  void close() throws Exception {
    db.close();
  }

  String id() {
    return UUID.randomUUID().toString();
  }

  String asset(String owner) {
    String id = id();
    l.prepareIn(id, owner, new byte[] {1, 2, 3}, "SWORD");
    l.confirmIn(id, owner);
    return id;
  }

  long sum() {
    return db.tx(d -> d.number("SELECT sum(amount) FROM accounts WHERE currency='gold'"));
  }

  @Test
  void denomination() {
    assertEquals(
        12345, l.currency("gold").parse("1g 23s 45c", Map.of("g", 10000L, "s", 100L, "c", 1L)));
    assertEquals("1 з 23 с 45 м", l.currency("gold").format(12345));
    assertEquals(12345, l.currency("gold").parse("12345", Map.of()));
  }

  @Test
  void badAmounts() {
    for (String n :
        List.of("-1", "NaN", "Infinity", "1.1", "9223372036854775808", "1x", "1g garbage", "1g 1g"))
      assertThrows(RuntimeException.class, () -> l.currency("gold").parse(n, Map.of("g", 10000L)));
  }

  @Test
  void vaultValidation() {
    for (double n :
        new double[] {
          Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1, 1.5, Double.MAX_VALUE
        }) assertThrows(RuntimeException.class, () -> Money.vault(n));
    assertEquals(100, Money.vault(100));
  }

  @Test
  void exactDecimal() {
    var t = new Money.Currency("token", "T", "T", "PAPER", 2, 0, 100000, true, true, true, "");
    assertEquals(123, t.parse("1.23", Map.of()));
    assertThrows(RuntimeException.class, () -> t.parse("1.234", Map.of()));
  }

  @Test
  void exchangeRounding() {
    for (long n = 1; n < 1000; n++) {
      long out = Money.exchange(n, 7, 3, 0);
      assertTrue(Money.exchange(out, 3, 7, 0) <= n);
    }
  }

  @Test
  void insufficientRollsBack() {
    assertThrows(RuntimeException.class, () -> l.transfer("x", a, b, "gold", 10001, 0));
    assertEquals(10000, l.cached(a, "gold"));
    assertEquals(0, l.cached(b, "gold"));
    assertEquals(10000, sum());
  }

  @Test
  void limitRollsBack() {
    l.admin("b", b, "gold", 999999, "set");
    assertThrows(RuntimeException.class, () -> l.transfer("x", a, b, "gold", 2, 0));
    assertEquals(10000, l.cached(a, "gold"));
  }

  @Test
  void duplicateAndFingerprint() {
    l.transfer("x", a, b, "gold", 50, 0);
    l.transfer("x", a, b, "gold", 50, 0);
    assertEquals(50, l.cached(b, "gold"));
    assertThrows(RuntimeException.class, () -> l.transfer("x", a, b, "gold", 51, 0));
  }

  @Test
  void concurrentWithdrawals() throws Exception {
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      var jobs = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 2; i++)
        jobs.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    l.admin(id(), a, "gold", 8000, "take");
                    return true;
                  } catch (RuntimeException e) {
                    return false;
                  }
                }));
      start.countDown();
      int successes = 0;
      for (var f : jobs) if (f.get()) successes++;
      assertEquals(1, successes);
      assertEquals(2000, l.cached(a, "gold"));
    }
  }

  @Test
  void transferConservesWithFees() {
    l.transfer(id(), a, b, "gold", 100, 3);
    assertEquals(10000, sum());
    assertEquals(3, l.cached("$fees", "gold"));
  }

  @Test
  void honorNotTransferable() {
    assertThrows(RuntimeException.class, () -> l.transfer(id(), a, b, "honor", 1, 0));
  }

  @Test
  void bankCapacityAndPrices() {
    for (int i = 0; i < 9; i++) l.storeBank(a, asset(a), 0);
    String extra = asset(a);
    assertThrows(RuntimeException.class, () -> l.storeBank(a, extra, 0));
    l.expand("slots", a, 0, 100);
    l.storeBank(a, extra, 0);
    l.buyTab("tab", a, 500, 3);
    assertEquals(2, l.tabs(a).size());
    assertEquals(10000, sum());
  }

  @Test
  void outgoingFullInventoryAbort() {
    String item = asset(a);
    l.reserveOut(item, a);
    l.finishOut(item, a, false);
    assertEquals(1, l.assets(a, "INBOX").size());
    l.reserveOut(item, a);
    l.finishOut(item, a, true);
    assertEquals(0, l.assets(a, "INBOX").size());
    assertThrows(RuntimeException.class, () -> l.reserveOut(item, a));
  }

  @Test
  void crashQuarantine() {
    String incoming = id();
    l.prepareIn(incoming, a, new byte[] {7}, "TEST");
    l.recover();
    assertEquals("QUARANTINE_IN", str(l.pending().getFirst(), "state"));
    assertThrows(RuntimeException.class, () -> asset(a));
    l.resolveAsset(incoming, true, "test");
    assertEquals(1, l.assets(a, "INBOX").size());
    l.reserveOut(incoming, a);
    l.recover();
    assertEquals("QUARANTINE_OUT", str(l.pending().getFirst(), "state"));
    l.resolveAsset(incoming, false, "test");
    assertEquals(0, l.assets(a, "INBOX").size());
  }

  @Test
  void mailOnce() {
    String item = asset(a), mail = id();
    l.sendMail(mail, a, b, "Тема", "Текст", "gold", 100, 0, List.of(item), 2, 0, 100000, false);
    l.claimMail("claim", b, mail, "all");
    l.claimMail("claim", b, mail, "all");
    l.claimMail("again", b, mail, "all");
    assertEquals(100, l.cached(b, "gold"));
    assertEquals(1, l.assets(b, "INBOX").size());
    assertEquals(10000, sum());
  }

  @Test
  void codPaidOnce() {
    l.admin("seed-b", b, "gold", 1000, "give");
    String item = asset(a), mail = id();
    l.sendMail(mail, a, b, "COD", "", "gold", 0, 300, List.of(item), 0, 0, 100000, false);
    l.claimMail("claim", b, mail, "all");
    l.claimMail("again", b, mail, "all");
    assertEquals(700, l.cached(b, "gold"));
    assertEquals(300, l.cached("$mail:cod:" + mail, "gold"));
    assertEquals(1, l.mails(a, 0).size());
    assertEquals(11000, sum());
  }

  @Test
  void unpaidCodCannotTake() {
    String item = asset(a), mail = id();
    l.sendMail(mail, a, b, "COD", "", "gold", 0, 300, List.of(item), 0, 0, 100000, false);
    assertThrows(RuntimeException.class, () -> l.claimMail(id(), b, mail, "all"));
    assertEquals(0, l.assets(b, "INBOX").size());
    assertEquals(1, l.assets(b, "MAIL:" + mail).size());
  }

  @Test
  void mailReturn() {
    String item = asset(a), mail = id();
    l.sendMail(mail, a, b, "T", "", "gold", 100, 0, List.of(item), 0, 0, 100000, false);
    l.returnMail(mail, b);
    l.claimMail(id(), a, mail, "all");
    assertEquals(10000, l.cached(a, "gold"));
    assertEquals(1, l.assets(a, "INBOX").size());
  }

  @Test
  void auctionOneBuyer() throws Exception {
    l.admin("b", b, "gold", 1000, "give");
    l.admin("c", c, "gold", 1000, "give");
    String item = asset(a), lot = id();
    l.listLot(lot, a, item, 300, 10, 100000);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var barrier = new CountDownLatch(1);
      var fs = new ArrayList<Future<Boolean>>();
      for (String buyer : List.of(b, c))
        fs.add(
            pool.submit(
                () -> {
                  barrier.await();
                  try {
                    l.buyLot(id(), buyer, lot, 500);
                    return true;
                  } catch (RuntimeException e) {
                    return false;
                  }
                }));
      barrier.countDown();
      int n = 0;
      for (var f : fs) if (f.get()) n++;
      assertEquals(1, n);
    }
    assertEquals(12000, sum());
    assertEquals(0, l.lots("", "", 0).size());
    assertEquals(1, l.mails(a, 0).size());
  }

  @Test
  void auctionCancel() {
    String item = asset(a), lot = id();
    l.listLot(lot, a, item, 300, 10, 100000);
    l.cancelLot(lot, a, false);
    assertEquals(10, l.cached("$fees", "gold"));
    assertEquals(1, l.mails(a, 0).size());
    assertEquals(10000, sum());
  }

  @Test
  void tradeRevisionReset() {
    String t = id();
    l.requestTrade(t, a, b, 100000);
    l.acceptTrade(t, b);
    l.offerMoney(t, a, "gold", 100);
    long rev = l.confirmTrade(t, a);
    l.confirmTrade(t, b);
    l.offerMoney(t, a, "gold", 200);
    assertThrows(RuntimeException.class, () -> l.completeTrade(t, rev));
    assertEquals(-1, num(l.trade(a), "confirm_a"));
  }

  @Test
  void tradeCancelAndRestart() {
    String item = asset(a), t = id();
    l.requestTrade(t, a, b, 100000);
    l.acceptTrade(t, b);
    l.offerItem(t, a, item);
    l.offerMoney(t, a, "gold", 100);
    l.recover();
    l.cancelTrade(t);
    assertEquals(1, l.assets(a, "INBOX").size());
    assertEquals(1, l.mails(a, 0).size());
    assertEquals(10000, sum());
  }

  @Test
  void tradeCompleteWithItems() {
    String item = asset(a), t = id();
    l.requestTrade(t, a, b, 100000);
    l.acceptTrade(t, b);
    l.offerItem(t, a, item);
    l.offerMoney(t, a, "gold", 100);
    long rev = l.confirmTrade(t, a);
    l.confirmTrade(t, b);
    l.completeTrade(t, rev);
    assertEquals(100, l.cached(b, "gold"));
    assertEquals(1, l.mails(b, 0).size());
    assertEquals(10000, sum());
    assertThrows(RuntimeException.class, () -> l.completeTrade(t, rev));
  }

  @Test
  void copiedChequeOnce() {
    l.issueVoucherAsset("v", a, "gold", 100, new byte[] {3});
    l.redeemVoucher("v", b);
    assertThrows(RuntimeException.class, () -> l.redeemVoucher("v", c));
    assertEquals(100, l.cached(b, "gold"));
    assertEquals(10000, sum());
  }

  @Test
  void schedulesIdempotent() {
    l.scheduled("daily:1", a, b, "gold", 100);
    l.scheduled("daily:1", a, b, "gold", 100);
    assertEquals(100, l.cached(b, "gold"));
    assertEquals(10000, sum());
  }

  @Test
  void backupAndMigration() throws Exception {
    Path backup = dir.resolve("backup.db");
    l.transfer("x", a, b, "gold", 123, 0);
    db.backup(backup);
    try (Database restored = new Database(backup)) {
      Ledger copy = new Ledger(restored, currencies());
      assertEquals(123, copy.cached(b, "gold"));
      assertTrue(copy.diagnostics().contains("integrity=ok"));
      assertEquals(
          3L, (long) restored.tx(d -> d.number("SELECT max(version) FROM schema_version")));
    }
  }

  @Test
  void databaseFailureDoesNotPublish() throws Exception {
    db.tx(
        d -> {
          d.exec("PRAGMA query_only=ON");
          return null;
        });
    assertThrows(RuntimeException.class, () -> l.transfer("fail", a, b, "gold", 100, 0));
    assertEquals(10000, l.cached(a, "gold"));
    assertEquals(0, l.cached(b, "gold"));
    db.tx(
        d -> {
          d.exec("PRAGMA query_only=OFF");
          return null;
        });
  }

  @Test
  void bytesPreservedAcrossCustody() {
    String item = asset(a);
    l.storeBank(a, item, 0);
    assertArrayEquals(new byte[] {1, 2, 3}, (byte[]) l.assets(a, "BANK:0").getFirst().get("data"));
  }

  @Test
  void staleTradeScreenCannotConfirm() {
    String t = id();
    l.requestTrade(t, a, b, 100000);
    l.acceptTrade(t, b);
    long rev = Database.num(l.trade(a), "revision");
    l.offerMoney(t, a, "gold", 100);
    assertThrows(RuntimeException.class, () -> l.confirmTrade(t, b, rev));
  }

  @Test
  void guildRolesLimitsAndItems() {
    l.guildCreate("guild", a);
    l.guildRole("guild", a, b, "OFFICER");
    l.guildRole("guild", a, c, "MEMBER");
    l.guildMoney("g1", "guild", a, "gold", 1000, true, 100);
    assertThrows(
        RuntimeException.class, () -> l.guildMoney("g2", "guild", c, "gold", 1, false, 100));
    l.guildMoney("g3", "guild", b, "gold", 100, false, 100);
    assertThrows(
        RuntimeException.class, () -> l.guildMoney("g4", "guild", b, "gold", 1, false, 100));
    String item = asset(a);
    l.guildItem("guild", a, item, true, 1);
    l.guildItem("guild", b, item, false, 1);
    assertEquals(1, l.assets(b, "INBOX").size());
    assertEquals(10000, sum());
  }

  @Test
  void interestOnceAndCap() {
    l.bankMoney("bank", a, "gold", 10000, true);
    l.interest("day1", 100, 50);
    l.interest("day1", 100, 50);
    assertEquals(10050, l.cached("bank:" + a, "gold"));
    assertEquals(10050, sum());
  }

  @Test
  void xpSagaAndRecovery() {
    l.xpPrepare("xp1", a, 100, 50, 50, true, 1000);
    l.xpFinish("xp1", true, false);
    assertEquals(50, l.xpBalance(a));
    l.xpPrepare("xp2", a, 50, 70, 20, false, 1000);
    l.recover();
    assertEquals(1, l.xpPending().size());
    l.xpFinish("xp2", false, true);
    assertEquals(50, l.xpBalance(a));
    assertThrows(RuntimeException.class, () -> l.xpFinish("xp1", true, false));
  }

  @Test
  void databaseLeaseRejectsSecondInstance() {
    assertThrows(
        Exception.class,
        () -> {
          try (Database other = new Database(dir.resolve("test.db"))) {}
          ;
        });
  }

  @Test
  void changingPrecisionIsRejected() {
    Map<String, Money.Currency> altered = new HashMap<>(currencies());
    altered.put(
        "honor",
        new Money.Currency("honor", "H", "H", "PAPER", 2, 0, 10000, false, false, false, ""));
    assertThrows(RuntimeException.class, () -> new Ledger(db, altered));
  }

  @Test
  void repeatedItemRewardAndArchive() {
    l.rewardItems("reward-items", b, "Reward", List.of(new byte[] {5, 6}));
    l.rewardItems("reward-items", b, "Reward", List.of(new byte[] {5, 6}));
    assertThrows(RuntimeException.class, () -> l.archiveMail("reward-items", b));
    l.claimMail("claim", b, "reward-items", "all");
    l.archiveMail("reward-items", b);
    assertEquals(1, l.assets(b, "INBOX").size());
    assertEquals(0, l.mails(b, 0).size());
  }

  @Test
  void eventsPublishedOnlyAfterCommit() {
    List<Ledger.Change> changes = new ArrayList<>();
    l.committed = changes::addAll;
    l.transfer("good", a, b, "gold", 50, 0);
    assertEquals(2, changes.size());
    assertThrows(RuntimeException.class, () -> l.transfer("bad", a, b, "gold", 999999, 0));
    assertEquals(2, changes.size());
  }
}
