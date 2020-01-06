// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model;

import static com.google.common.base.Suppliers.memoizeWithExpiration;
import static google.registry.config.RegistryConfig.getSingletonCacheRefreshDuration;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.joda.time.Duration.ZERO;

import com.google.common.base.Supplier;
import org.joda.time.Duration;

/** Utility methods related to caching Datastore entities. */
public class CacheUtils {

  /**
   * Memoize a supplier, with a short expiration specified in the environment config.
   *
   * <p>Use this for things that might change while code is running. (For example, the various
   * lists downloaded from the TMCH get updated in Datastore and the caches need to be refreshed.)
   */
  public static <T> Supplier<T> memoizeWithShortExpiration(Supplier<T> original) {
    return tryMemoizeWithExpiration(getSingletonCacheRefreshDuration(), original);
  }

  /**
   * Memoize a supplier with the given expiration. If the expiration is zero(likely happens in a
   * unit test), it returns the original supplier.
   */
  public static <T> Supplier<T> tryMemoizeWithExpiration(
      Duration expiration, Supplier<T> original) {
    return expiration.isEqual(ZERO)
        ? original
        : memoizeWithExpiration(original, expiration.getMillis(), MILLISECONDS);
  }
}
