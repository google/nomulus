package google.registry.util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.collect.Streams;
import java.util.stream.Stream;

/** Utilities for handling {@link Stream streams}. */
public final class MoreStreams {

  private static final int MAX_BATCH_SIZE = 1024 * 1024;

  private MoreStreams() {}

  /** Converts a stream into batches. */
  public static <T> Stream<ImmutableList<T>> batchedStream(Stream<T> stream, int desiredBatchSize) {
    int batchSize = Math.min(desiredBatchSize, MAX_BATCH_SIZE);
    return Streams.stream(
        () ->
            Iterators.transform(
                Iterators.partition(stream.iterator(), batchSize), ImmutableList::copyOf));
  }
}
