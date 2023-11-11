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

package google.registry.util;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.util.BatchedStreams.batch;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link BatchedStreams}. */
public class BatchedStreamsTest {

  @Test
  void invalidBatchSize() {
    assertThat(assertThrows(IllegalArgumentException.class, () -> batch(Stream.of(), 0)))
        .hasMessageThat()
        .contains("must be a positive integer");
  }

  @Test
  void batch_success() {
    Stream<Integer> data = IntStream.rangeClosed(0, 1_000_000).boxed();
    assertThat(batch(data, 1000).map(ImmutableList::size).collect(groupingBy(x -> x, counting())))
        .containsExactly(1000, 1000L, 1, 1L);
  }

  @Test
  void batch_partialBatch() {
    Stream<Integer> data = Stream.of(1, 2, 3);
    assertThat(batch(data, 1000).map(ImmutableList::size).collect(groupingBy(x -> x, counting())))
        .containsExactly(3, 1L);
  }

  @Test
  void batch_truncateBatchSize() {
    Stream<Integer> data = IntStream.range(0, 1024 * 2048).boxed();
    assertThat(
            batch(data, 2_000_000).map(ImmutableList::size).collect(groupingBy(x -> x, counting())))
        .containsExactly(1024 * 1024, 2L);
  }
}
