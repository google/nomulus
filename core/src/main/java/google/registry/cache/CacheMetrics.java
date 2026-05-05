// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

package google.registry.cache;

import com.google.common.collect.ImmutableSet;
import com.google.monitoring.metrics.IncrementableMetric;
import com.google.monitoring.metrics.LabelDescriptor;
import com.google.monitoring.metrics.MetricRegistryImpl;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/** Metrics tracking effectiveness of local and remote EPP resource caching. */
@Singleton
public class CacheMetrics {

  public enum CacheHitType {
    LOCAL,
    REMOTE,
    MISS,
    MISS_NONEXISTENT
  }

  private static final ImmutableSet<LabelDescriptor> LABEL_DESCRIPTORS =
      ImmutableSet.of(
          LabelDescriptor.create("cache_name", "The type of the cache (domain/host)."),
          LabelDescriptor.create("hit_type", "The type of cache hit or miss."));

  private static final IncrementableMetric cacheLookups =
      MetricRegistryImpl.getDefault()
          .newIncrementableMetric(
              "/cache/lookups", "Count of cache lookups", "count", LABEL_DESCRIPTORS);

  @Inject
  public CacheMetrics() {}

  public void recordLookup(String cacheName, CacheHitType hitType) {
    cacheLookups.increment(cacheName, hitType.toString());
  }
}
