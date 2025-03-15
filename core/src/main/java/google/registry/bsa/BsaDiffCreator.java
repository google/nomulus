// Copyright 2023 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.bsa;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Maps.newHashMap;
import static com.google.common.collect.Multimaps.newListMultimap;
import static com.google.common.collect.Multimaps.toMultimap;

import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import google.registry.bsa.api.BlockLabel;
import google.registry.bsa.api.BlockLabel.LabelType;
import google.registry.bsa.api.BlockOrder;
import google.registry.bsa.api.BlockOrder.OrderType;
import google.registry.bsa.persistence.DownloadSchedule;
import google.registry.bsa.persistence.DownloadSchedule.CompletedJob;
import google.registry.tldconfig.idn.IdnTableEnum;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/** Creates diffs between the most recent download and the previous one. */
class BsaDiffCreator {

  private static final Splitter LINE_SPLITTER = Splitter.on(',').trimResults();
  private static final Splitter ORDER_SPLITTER = Splitter.on(';').trimResults();

  private static final String BSA_CSV_HEADER = "domainLabel,orderIDs";

  /** An impossible value for order ID. See {@link #createDiff} for usage. */
  static final Long ORDER_ID_SENTINEL = Long.MIN_VALUE;

  private final GcsClient gcsClient;

  @Inject
  BsaDiffCreator(GcsClient gcsClient) {
    this.gcsClient = gcsClient;
  }

  private <K, V extends Comparable<?>> Multimap<K, V> listBackedMultiMap() {
    return newListMultimap(newHashMap(), Lists::newArrayList);
  }

  BsaDiff createDiff(DownloadSchedule schedule, IdnChecker idnChecker) {
    String currentJobName = schedule.jobName();
    Optional<String> previousJobName = schedule.latestCompleted().map(CompletedJob::jobName);
    /*
     * Memory usage is a concern when creating a diff, when the newest download needs to be held in
     * memory in its entirety. The top-grade AppEngine VM has 3GB of memory, leaving less than 1.5GB
     * to application memory footprint after subtracting overheads due to copying garbage collection
     * and non-heap data etc. Assuming 400K labels, each of which on average included in 5 orders,
     * the memory footprint is at least 300MB when loaded into a Hashset-backed Multimap (64-bit
     * JVM, with 12-byte object header, 16-byte array header, and 16-byte alignment).
     *
     * <p>The memory footprint can be reduced in two ways, by using a canonical instance for each
     * order ID value, and by using a ArrayList-backed Multimap. Together they reduce memory size to
     * well below 100MB for the scenario above.
     *
     * <p>We need to watch out for the download sizes even after the migration to GKE. However, at
     * that point we will have a wider selection of hardware.
     *
     * <p>Beam pipeline is not a good option. It has to be launched as a separate, asynchronous job,
     * and there is no guaranteed limit to launch delay. Both issues would increase code complexity.
     */
    Canonicals<Long> canonicals = new Canonicals<>();
    try (Stream<Line> currentStream = loadBlockLists(currentJobName);
        Stream<Line> previousStream =
            previousJobName.map(this::loadBlockLists).orElseGet(Stream::of)) {
      /*
       * Load current label/order pairs into a multimap, which will contain both new labels and
       * those that stay on when processing is done.
       */
      Multimap<String, Long> newAndRemaining =
          currentStream
              .map(line -> line.labelOrderPairs(canonicals))
              .flatMap(x -> x)
              .collect(
                  toMultimap(
                      LabelOrderPair::label, LabelOrderPair::orderId, this::listBackedMultiMap));

      Multimap<String, Long> deleted =
          previousStream
              .map(
                  line -> {
                    // Mark labels that exist in both downloads with the SENTINEL id. This helps
                    // distinguish existing label with new order from new labels.
                    if (newAndRemaining.containsKey(line.label())
                        && !newAndRemaining.containsEntry(line.label(), ORDER_ID_SENTINEL)) {
                      newAndRemaining.put(line.label(), ORDER_ID_SENTINEL);
                    }
                    return line;
                  })
              .map(line -> line.labelOrderPairs(canonicals))
              .flatMap(x -> x)
              .filter(kv -> !newAndRemaining.remove(kv.label(), kv.orderId()))
              .collect(
                  toMultimap(
                      LabelOrderPair::label, LabelOrderPair::orderId, this::listBackedMultiMap));

      /*
       * Labels in `newAndRemaining`:
       *
       * <ul>
       *   <li>Mapped to `sentinel` only: Labels without change, ignore
       *   <li>Mapped to `sentinel` and some orders: Existing labels with new order mapping. Those
       *       orders are new orders.
       *   <li>Mapped to some orders but not `sentinel`: New labels and new orders.
       * </ul>
       *
       * <p>The `deleted` map has
       *
       * <ul>
       *   <li>Deleted labels: the keyset of deleted minus the keyset of the newAndRemaining
       *   <li>Deleted orders: the union of values.
       * </ul>
       */
      return new BsaDiff(
          ImmutableMultimap.copyOf(newAndRemaining), ImmutableMultimap.copyOf(deleted), idnChecker);
    }
  }

  Stream<Line> loadBlockLists(String jobName) {
    return Stream.of(BlockListType.values())
        .map(blockList -> gcsClient.readBlockList(jobName, blockList))
        .flatMap(x -> x)
        .filter(line -> !line.startsWith(BSA_CSV_HEADER))
        .map(BsaDiffCreator::parseLine);
  }

  static Line parseLine(String line) {
    List<String> columns = LINE_SPLITTER.splitToList(line);
    checkArgument(columns.size() == 2, "Invalid line: [%s]", line);
    checkArgument(!Strings.isNullOrEmpty(columns.get(0)), "Missing label in line: [%s]", line);
    try {
      ImmutableList<Long> orderIds =
          ORDER_SPLITTER
              .splitToStream(columns.get(1))
              .map(Long::valueOf)
              .collect(toImmutableList());
      checkArgument(!orderIds.isEmpty(), "Missing orders in line: [%s]", line);
      checkArgument(
          !orderIds.contains(ORDER_ID_SENTINEL), "Invalid order id %s", ORDER_ID_SENTINEL);
      return Line.of(columns.get(0), orderIds);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(line, e);
    }
  }

  static class BsaDiff {
    private final ImmutableMultimap<String, Long> newAndRemaining;

    private final ImmutableMultimap<String, Long> deleted;
    private final IdnChecker idnChecker;

    BsaDiff(
        ImmutableMultimap<String, Long> newAndRemaining,
        ImmutableMultimap<String, Long> deleted,
        IdnChecker idnChecker) {
      this.newAndRemaining = newAndRemaining;
      this.deleted = deleted;
      this.idnChecker = idnChecker;
    }

    Stream<BlockOrder> getOrders() {
      return Stream.concat(
          newAndRemaining.values().stream()
              .filter(value -> !Objects.equals(ORDER_ID_SENTINEL, value))
              .distinct()
              .map(id -> BlockOrder.create(id, OrderType.CREATE)),
          deleted.values().stream().distinct().map(id -> BlockOrder.create(id, OrderType.DELETE)));
    }

    Stream<BlockLabel> getLabels() {
      return Stream.of(
              newAndRemaining.asMap().entrySet().stream()
                  .filter(e -> e.getValue().size() > 1 || !e.getValue().contains(ORDER_ID_SENTINEL))
                  .filter(entry -> entry.getValue().contains(ORDER_ID_SENTINEL))
                  .map(
                      entry ->
                          BlockLabel.create(
                              entry.getKey(),
                              LabelType.NEW_ORDER_ASSOCIATION,
                              getAllValidIdnNames(entry.getKey()))),
              newAndRemaining.asMap().entrySet().stream()
                  .filter(e -> e.getValue().size() > 1 || !e.getValue().contains(ORDER_ID_SENTINEL))
                  .filter(entry -> !entry.getValue().contains(ORDER_ID_SENTINEL))
                  .map(
                      entry ->
                          BlockLabel.create(
                              entry.getKey(),
                              LabelType.CREATE,
                              getAllValidIdnNames(entry.getKey()))),
              Sets.difference(deleted.keySet(), newAndRemaining.keySet()).stream()
                  .map(
                      label ->
                          BlockLabel.create(label, LabelType.DELETE, getAllValidIdnNames(label))))
          .flatMap(x -> x);
    }

    ImmutableSet<String> getAllValidIdnNames(String label) {
      return idnChecker.getAllValidIdns(label).stream()
          .map(IdnTableEnum::name)
          .collect(toImmutableSet());
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

  record LabelOrderPair(String label, Long orderId) {

    static LabelOrderPair of(String key, Long value) {
      return new LabelOrderPair(key, value);
    }
  }

  record Line(String label, ImmutableList<Long> orderIds) {

    Stream<LabelOrderPair> labelOrderPairs(Canonicals<Long> canonicals) {
      return orderIds().stream().map(id -> LabelOrderPair.of(label(), canonicals.get(id)));
    }

    static Line of(String label, ImmutableList<Long> orderIds) {
      return new Line(label, orderIds);
    }
  }
}
