// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.tmch;

import static google.registry.model.CacheUtils.memoizeWithShortExpiration;
import static google.registry.model.tmch.ClaimsListDaoFactory.claimsListDao;

import google.registry.schema.tmch.ClaimsList;
import google.registry.util.Retrier;
import google.registry.util.SystemSleeper;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** A cache for the current {@link ClaimsList} in the database. */
public class ClaimsListCache {
  private static final Retrier LOADER_RETRIER = new Retrier(new SystemSleeper(), 2);

  /**
   * A cached supplier that fetches the claims list shards from Datastore and recombines them into a
   * single {@link ClaimsListShard} object.
   */
  private static final Supplier<ClaimsList> CACHE = createCache();

  private static Supplier<ClaimsList> createCache() {
    return memoizeWithShortExpiration(
        () -> LOADER_RETRIER.callWithRetry(
            () -> claimsListDao().getCurrentList(),
            IllegalStateException.class));
  }

  /** Returns the cached {@link ClaimsList}. */
  @Nullable
  public static ClaimsList get() {
    return CACHE.get();
  }
}
