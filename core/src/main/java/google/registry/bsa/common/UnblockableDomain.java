package google.registry.bsa.common;

import com.google.auto.value.AutoValue;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import java.util.List;

@AutoValue
public abstract class UnblockableDomain {
  abstract String label();

  abstract String tld();

  abstract Reason reason();

  /** Reasons why a valid domain name cannot be blocked. */
  public enum Reason {
    REGISTERED,
    RESERVED,
    INVALID;
  }

  static final Joiner JOINER = Joiner.on(',');
  static final Splitter SPLITTER = Splitter.on(',');

  public String serialize() {
    return JOINER.join(label(), tld(), reason().name());
  }

  public static UnblockableDomain deserialize(String text) {
    List<String> items = SPLITTER.splitToList(text);
    return of(items.get(0), items.get(1), Reason.INVALID.valueOf(items.get(2)));
  }

  public static UnblockableDomain of(String label, String tld, Reason reason) {
    return new AutoValue_UnblockableDomain(label, tld, reason);
  }
}
