// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.base.Preconditions.checkState;
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.util.CacheUtils.memoizeWithShortExpiration;

import com.google.common.base.Supplier;
import com.google.common.collect.HashBiMap;
import com.googlecode.objectify.Work;
import google.registry.model.registry.Registry;

/** Utility class for dealing with EPP ROID suffixes. */
public final class RoidSuffixes {

  private static Supplier<HashBiMap<String, String>> roidSuffixMapCache =
      memoizeWithShortExpiration(new Supplier<HashBiMap<String, String>>() {
        @Override
        public HashBiMap<String, String> get() {
          return ofy().doTransactionless(new Work<HashBiMap<String, String>>() {
            @Override
            public HashBiMap<String, String> run() {
              HashBiMap<String, String> bimap = HashBiMap.create();
              for (Registry registry :
                  ofy().load().type(Registry.class).ancestor(getCrossTldKey()).list()) {
                bimap.put(registry.getTldStr(), registry.getRoidSuffix());
              }
              return bimap;
            }
          });
        }
      });

  /**
   * Returns the roid suffix corresponding to the given tld using the per-tld roidSuffix field.
   *
   * @throws IllegalStateException if there is no such tld, or the tld does not have a roid suffix
   * configured on it
   */
  public static String getRoidSuffixForTld(String tld, boolean requireUniqueRoidSuffix) {
    String roidSuffix;
    if (requireUniqueRoidSuffix) {
      roidSuffix = roidSuffixMapCache.get().get(tld);
    } else {
      roidSuffix = Registry.get(tld).getRoidSuffix();
    }
    checkState(roidSuffix != null, "Could not find ROID suffix for TLD %s", tld);
    return roidSuffix;
  }

  public static boolean isRoidSuffixUsed(String roidSuffix) {
    return roidSuffixMapCache.get().containsValue(roidSuffix);
  }

}
