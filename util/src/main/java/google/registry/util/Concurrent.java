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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.concurrent.Executors.newFixedThreadPool;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.common.util.concurrent.Uninterruptibles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Function;

/** Utilities for multithreaded operations. */
public final class Concurrent {

  /** Maximum number of threads per pool. */
  private static final int MAX_THREADS = 10;

  /**
   * Runs transform with the default number of threads.
   *
   * @see #transform(Collection, int, Function)
   */
  public static <A, B> ImmutableList<B> transform(Collection<A> items, final Function<A, B> funk) {
    return transform(items, MAX_THREADS, funk);
  }

  /**
   * Processes {@code items} in parallel using {@code funk}, with the specified number of threads.
   *
   * <p>If the maxThreadCount or the number of items is less than 2, will use a non-concurrent
   * transform.
   *
   * <p><b>Note:</b> Spawned threads will inherit the same namespace.
   *
   * @throws UncheckedExecutionException to wrap the exception thrown by {@code funk}. This will
   *     only contain the exception information for the first exception thrown.
   * @return transformed {@code items} in the same order.
   */
  public static <A, B> ImmutableList<B> transform(
      Collection<A> items,
      int maxThreadCount,
      final Function<A, B> funk) {
    checkNotNull(funk);
    checkNotNull(items);
    int threadCount = max(1, min(items.size(), maxThreadCount));
    if (threadCount == 1) {
      return items.stream().map(funk).collect(toImmutableList());
    }
    ;
    try (ExecutorService executor = newFixedThreadPool(threadCount)) {
      List<Future<B>> futures = new ArrayList<>();
      for (final A item : items) {
        futures.add(executor.submit(() -> funk.apply(item)));
      }
      ImmutableList.Builder<B> results = new ImmutableList.Builder<>();
      for (Future<B> future : futures) {
        try {
          results.add(Uninterruptibles.getUninterruptibly(future));
        } catch (ExecutionException e) {
          throw new UncheckedExecutionException(e.getCause());
        }
      }
      return results.build();
    }
  }

  private Concurrent() {}
}
