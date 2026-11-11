package ru.terrawow.main;

import java.math.*;
import java.util.*;
import java.util.regex.*;

/** No binary floating point enters the ledger. Plain primary amounts are copper. */
public final class Money {
  public static volatile String primaryFormat = "{g} з {s} с {c} м";

  private Money() {}

  public record Currency(
      String id,
      String name,
      String symbol,
      String icon,
      int precision,
      long start,
      long limit,
      boolean transfer,
      boolean trade,
      boolean physical,
      String permission) {
    public Currency {
      if (!id.matches("[a-z][a-z0-9_]{0,31}")
          || precision < 0
          || precision > 6
          || start < 0
          || limit < start) throw new IllegalArgumentException("Неверная валюта: " + id);
      if (id.equals("gold") && (precision != 0 || limit > 9_007_199_254_740_991L))
        throw new IllegalArgumentException("gold: целые медные, предел <= 2^53-1");
    }

    public long parse(String s, Map<String, Long> units) {
      s = s.trim().toLowerCase(Locale.ROOT);
      try {
        if (!id.equals("gold") || s.matches("[0-9]+")) {
          if (!s.matches("[0-9]+(?:\\.[0-9]+)?")) throw new ArithmeticException();
          return new BigDecimal(s).movePointRight(precision).longValueExact();
        }
        Matcher m = Pattern.compile("([0-9]+)\\s*([\\p{L}]+)").matcher(s);
        long total = 0;
        int end = 0;
        Set<String> seen = new HashSet<>();
        while (m.find()) {
          if (!s.substring(end, m.start()).isBlank() || !seen.add(m.group(2)))
            throw new ArithmeticException();
          Long factor = units.get(m.group(2));
          if (factor == null) throw new ArithmeticException();
          total = Math.addExact(total, Math.multiplyExact(Long.parseLong(m.group(1)), factor));
          end = m.end();
        }
        if (end == 0 || !s.substring(end).isBlank()) throw new ArithmeticException();
        return total;
      } catch (ArithmeticException e) {
        throw new IllegalArgumentException("Неверная сумма или переполнение");
      }
    }

    public String format(long n) {
      if (id.equals("gold"))
        return primaryFormat
            .replace("{g}", Long.toString(n / 10000))
            .replace("{s}", Long.toString(n / 100 % 100))
            .replace("{c}", Long.toString(n % 100));
      return BigDecimal.valueOf(n, precision).toPlainString() + " " + symbol;
    }
  }

  public static long vault(double n) {
    if (!Double.isFinite(n) || n < 0 || n > 9_007_199_254_740_991D)
      throw new IllegalArgumentException("Недопустимая сумма Vault");
    try {
      return BigDecimal.valueOf(n).longValueExact();
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("Vault принимает только целые медные");
    }
  }

  public static long exchange(long amount, long numerator, long denominator, int feeBps) {
    if (amount <= 0 || numerator <= 0 || denominator <= 0 || feeBps < 0 || feeBps > 10000)
      throw new IllegalArgumentException("Неверный курс");
    return BigInteger.valueOf(amount)
        .multiply(BigInteger.valueOf(numerator))
        .multiply(BigInteger.valueOf(10000 - feeBps))
        .divide(BigInteger.valueOf(denominator).multiply(BigInteger.valueOf(10000)))
        .longValueExact();
  }

  public static long fee(long amount, int bps) {
    if (amount < 0 || bps < 0 || bps > 10000)
      throw new IllegalArgumentException("Неверная комиссия");
    return BigInteger.valueOf(amount)
        .multiply(BigInteger.valueOf(bps))
        .add(BigInteger.valueOf(9999))
        .divide(BigInteger.valueOf(10000))
        .longValueExact();
  }

  public record Rate(String from, String to, long numerator, long denominator, int feeBps) {}

  private record Fraction(BigInteger n, BigInteger d) {
    Fraction multiply(Fraction o) {
      BigInteger a = n.multiply(o.n), b = d.multiply(o.d), g = a.gcd(b);
      return new Fraction(a.divide(g), b.divide(g));
    }

    boolean greater(Fraction o) {
      return n.multiply(o.d).compareTo(o.n.multiply(d)) > 0;
    }
  }

  /** Exact multiplicative Bellman-Ford; rejects profitable cycles of any length. */
  public static void validateRates(List<Rate> rates) {
    Map<String, Fraction> best = new HashMap<>();
    Fraction one = new Fraction(BigInteger.ONE, BigInteger.ONE);
    for (Rate r : rates) {
      exchange(1, r.numerator, r.denominator, r.feeBps);
      best.put(r.from, one);
      best.put(r.to, one);
    }
    for (int i = 0; i < best.size(); i++) {
      boolean change = false;
      Map<String, Fraction> next = new HashMap<>(best);
      for (Rate r : rates) {
        Fraction f =
            new Fraction(
                BigInteger.valueOf(r.numerator).multiply(BigInteger.valueOf(10000 - r.feeBps)),
                BigInteger.valueOf(r.denominator).multiply(BigInteger.valueOf(10000)));
        Fraction product = best.get(r.from).multiply(f);
        if (product.greater(next.get(r.to))) {
          next.put(r.to, product);
          change = true;
        }
      }
      if (!change) return;
      if (i == best.size() - 1) throw new IllegalArgumentException("Прибыльный цикл обмена валют");
      best = next;
    }
  }
}
