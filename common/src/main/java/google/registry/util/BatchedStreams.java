// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.util;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Iterators.partition;
import static com.google.common.collect.Iterators.transform;
import static com.google.common.collect.Streams.stream;
import static java.lang.Math.min;

import com.google.common.collect.ImmutableList;
import java.util.stream.Stream;

/** Utilities for breaking up a {@link Stream} into batches. */
public final class BatchedStreams {

  static final int MAX_BATCH = 1024 * 1024;

  private BatchedStreams() {}

  /**
   * Transform a flat {@link Stream} into a {@code Stream} of batches.
   *
   * <p>Closing the returned stream does not close the original stream.
   */
  public static <T> Stream<ImmutableList<T>> toBatches(Stream<T> stream, int batchSize) {
    checkArgument(batchSize > 0, "batchSize must be a positive integer.");
    return stream(
        transform(partition(stream.iterator(), min(MAX_BATCH, batchSize)), ImmutableList::copyOf));
  }
}
