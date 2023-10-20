package google.registry.bsa.common;

import com.google.auto.value.AutoValue;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import java.util.List;

@AutoValue
public abstract class Label {

  static final Joiner JOINER = Joiner.on(',');
  static final Splitter SPLITTER = Splitter.on(',').trimResults();

  public abstract String label();

  public abstract LabelType labelType();

  public abstract ImmutableSet<String> idnTables();

  public String serialize() {
    return JOINER.join(label(), labelType().name(), idnTables().toArray());
  }

  public static Label deserialize(String text) {
    List<String> items = SPLITTER.splitToList(text);
    return of(
        items.get(0),
        LabelType.valueOf(items.get(1)),
        ImmutableSet.copyOf(items.subList(2, items.size())));
  }

  public static Label of(String label, LabelType type, ImmutableSet<String> idnTables) {
    return new AutoValue_Label(label, type, idnTables);
  }

  public enum LabelType {
    ADD,
    NEW_ORDER_ASSOCIATION,
    DELETE;
  }
}
