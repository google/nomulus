package google.registry.bsa.common;

import com.google.auto.value.AutoValue;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import java.util.List;

@AutoValue
public abstract class Order {

  public abstract long orderId();

  public abstract OrderType orderType();

  static final Joiner JOINER = Joiner.on(',');
  static final Splitter SPLITTER = Splitter.on(',');

  public String serialize() {
    return JOINER.join(orderId(), orderType().name());
  }

  public static Order deserialize(String text) {
    List<String> items = SPLITTER.splitToList(text);
    return of(Long.valueOf(items.get(0)), OrderType.valueOf(items.get(1)));
  }

  public static Order of(long orderId, OrderType orderType) {
    return new AutoValue_Order(orderId, orderType);
  }

  public enum OrderType {
    CREATE,
    DELETE;
  }
}
