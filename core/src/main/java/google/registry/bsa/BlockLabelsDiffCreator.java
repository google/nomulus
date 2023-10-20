package google.registry.bsa;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Maps.newHashMap;
import static com.google.common.collect.Multimaps.newListMultimap;
import static com.google.common.collect.Multimaps.toMultimap;

import com.google.auto.value.AutoValue;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import google.registry.bsa.common.BlockList;
import google.registry.bsa.common.Label;
import google.registry.bsa.common.Label.LabelType;
import google.registry.bsa.common.Order;
import google.registry.bsa.common.Order.OrderType;
import google.registry.bsa.jobs.BsaJob;
import google.registry.bsa.persistence.BsaDownload;
import google.registry.tldconfig.idn.IdnTableEnum;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import javax.inject.Inject;

class BlockLabelsDiffCreator {

  private static final Splitter LINE_SPLITTER = Splitter.on(',').trimResults();
  private static final Splitter ORDER_SPLITTER = Splitter.on(';').trimResults();

  private static final Long ORDER_ID_SENTINEL = Long.MIN_VALUE;

  private final GcsClient gcsClient;

  @Inject
  BlockLabelsDiffCreator(GcsClient gcsClient) {
    this.gcsClient = gcsClient;
  }

  private <K, V extends Comparable> Multimap<K, V> listBackedMultiMap() {
    return newListMultimap(newHashMap(), Lists::newArrayList);
  }

  BlockLabelsDiff createDiff(BsaJob bsaJob, IdnChecker idnChecker) {
    long currentJobId = bsaJob.job().getJobId();
    Optional<Long> previousJobId = bsaJob.previousJob().map(BsaDownload::getJobId);
    /**
     * To create a diff, we must load the new downloads entirely into memory. The memory size is
     * primarily determined by two parameters: the number of labels, and the average number of
     * orders each label is associated with. Assuming 400K labels and 10 orders per label, a naive
     * implementation that loads the data into a {@code Multimap<String, Long>} will need about 500
     * MB of memory, about one-third of the total on the highest-end AppEngine VMs.
     *
     * <p>By using the same instance for each Long value, and by using a array-list-backed Multimap,
     * we can reduce the memory footprint to below 100 MB.
     *
     * <p>We need to watch out for the download sizes even after the migration to GKE, post which we
     * will have a wider selection of hardware.
     *
     * <p>Beam pipeline is not a good option. It has to be launched as a separate job, increasing
     * code complexity.
     */
    Canonicals<Long> canonicals = new Canonicals<>();
    try (Stream<Line> currentStream = loadBlockLists(currentJobId);
        Stream<Line> previousStream =
            previousJobId.map(this::loadBlockLists).orElse(ImmutableList.<Line>of().stream())) {
      Multimap<String, Long> newAndRemaining =
          currentStream
              .map(line -> line.labelOrderPairs(canonicals))
              .flatMap(x -> x)
              .collect(toMultimap(KeyValue::key, KeyValue::value, this::listBackedMultiMap));

      Multimap<String, Long> deleted =
          previousStream
              .map(
                  line -> {
                    if (!newAndRemaining.containsEntry(line.label(), ORDER_ID_SENTINEL)) {
                      newAndRemaining.put(line.label(), ORDER_ID_SENTINEL);
                    }
                    return line;
                  })
              .map(line -> line.labelOrderPairs(canonicals))
              .flatMap(x -> x)
              .filter(kv -> !newAndRemaining.remove(kv.key(), kv.value()))
              .collect(toMultimap(KeyValue::key, KeyValue::value, this::listBackedMultiMap));
      /**
       * Labels in `newAndRemaining`:
       *
       * <ul>
       *   Mapped to `sentinel` only: Labels without change, ignore Mapped to `sentinel` and some
       *   orders: Existing labels with new order mapping. Those orders are new orders. Mapped to
       *   some orders but not `sentinel`: New labels and new orders.
       * </ul>
       *
       * <p>The `deleted` map has
       *
       * <ul>
       *   <li>Deleted labels: the keyset
       *   <li>Deleted orders: the union of values.
       * </ul>
       */
      return new BlockLabelsDiff(
          ImmutableMultimap.copyOf(newAndRemaining), ImmutableMultimap.copyOf(deleted), idnChecker);
    }
  }

  Stream<Line> loadBlockLists(long jobId) {
    return Stream.of(BlockList.values())
        .map(blockList -> gcsClient.readBlockList(jobId, blockList))
        .flatMap(x -> x)
        .map(BlockLabelsDiffCreator::parseLine);
  }

  static Line parseLine(String line) {
    List<String> columns = LINE_SPLITTER.splitToList(line);
    checkArgument(columns.size() == 2, "Invalid line: [%s]", line);
    checkArgument(!Strings.isNullOrEmpty(columns.get(0)), "Missing label in line: [%s]", line);
    ImmutableList<Long> orderIds =
        ORDER_SPLITTER.splitToStream(line).map(Long::valueOf).collect(toImmutableList());
    checkArgument(!orderIds.isEmpty(), "Missing orders in line: [%s]", line);
    return Line.of(columns.get(0), orderIds);
  }

  static class BlockLabelsDiff {

    private final ImmutableMultimap<String, Long> newAndRemaining;
    private final ImmutableMultimap<String, Long> deleted;
    private final IdnChecker idnChecker;

    BlockLabelsDiff(
        ImmutableMultimap<String, Long> newAndRemaining,
        ImmutableMultimap<String, Long> deleted,
        IdnChecker idnChecker) {
      this.newAndRemaining = newAndRemaining;
      this.deleted = deleted;
      this.idnChecker = idnChecker;
    }

    Stream<Order> getOrders() {
      return Stream.concat(
          newAndRemaining.values().stream()
              .filter(value -> !Objects.equals(ORDER_ID_SENTINEL, value))
              .map(id -> Order.of(id, OrderType.CREATE)),
          deleted.values().stream().map(id -> Order.of(id, OrderType.DELETE)));
    }

    Stream<Label> getLabels() {
      return Stream.concat(
          newAndRemaining.asMap().entrySet().stream()
              .filter(e -> e.getValue().size() > 1 || !e.getValue().contains(ORDER_ID_SENTINEL))
              .map(
                  entry -> {
                    Verify.verify(!entry.getValue().isEmpty(), "Unexpected empty set");
                    LabelType labelType =
                        entry.getValue().contains(ORDER_ID_SENTINEL)
                            ? LabelType.NEW_ORDER_ASSOCIATION
                            : LabelType.ADD;
                    return Label.of(
                        entry.getKey(),
                        labelType,
                        idnChecker.getAllValidIdns(entry.getKey()).stream()
                            .map(IdnTableEnum::name)
                            .collect(toImmutableSet()));
                  }),
          deleted.keySet().stream()
              .map(label -> Label.of(label, LabelType.DELETE, ImmutableSet.of())));
    }
  }

  static class Canonicals<T> {
    private final HashMap<T, T> cache;

    Canonicals() {
      cache = Maps.newHashMap();
    }

    T get(T value) {
      cache.putIfAbsent(value, value);
      return cache.get(value);
    }
  }

  @AutoValue
  abstract static class KeyValue<K, V> {
    abstract K key();

    abstract V value();

    static <K, V> KeyValue<K, V> of(K key, V value) {
      return new AutoValue_BlockLabelsDiffCreator_KeyValue<K, V>(key, value);
    }
  }

  @AutoValue
  abstract static class Line {
    abstract String label();

    abstract ImmutableList<Long> orderIds();

    Stream<KeyValue<String, Long>> labelOrderPairs(Canonicals<Long> canonicals) {
      return orderIds().stream().map(id -> KeyValue.of(label(), canonicals.get(id)));
    }

    static Line of(String label, ImmutableList<Long> orderIds) {
      return new AutoValue_BlockLabelsDiffCreator_Line(label, orderIds);
    }
  }
}
