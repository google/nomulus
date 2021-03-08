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

package google.registry.whois;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static google.registry.model.CacheUtils.memoizeWithShortExpiration;
import static google.registry.util.RegistrarUtils.normalizeRegistrarName;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import google.registry.model.registrar.Registrar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.joda.time.DateTime;

/** Represents a WHOIS lookup for a registrar by its name. */
final class RegistrarLookupCommand implements WhoisCommand {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** True if the command should return cached responses. */
  private boolean cached;

  /**
   * Cache of a map from a stripped-down (letters and digits only) name to the registrar. This map
   * includes only active, publicly visible registrars, because the others should be invisible to
   * WHOIS.
   */
  private static final Supplier<Map<String, Registrar>> REGISTRAR_BY_NORMALIZED_NAME_CACHE =
      memoizeWithShortExpiration(RegistrarLookupCommand::loadRegistrarMap);

  @VisibleForTesting
  final String registrarName;

  RegistrarLookupCommand(String registrarName, boolean cached) {
    checkArgument(!isNullOrEmpty(registrarName), "registrarName");
    this.registrarName = registrarName;
    this.cached = cached;
  }

  static Map<String, Registrar> loadRegistrarMap() {
    Map<String, Registrar> map = new HashMap<>();
    // Use the normalized registrar name as a key, and ignore inactive and hidden
    // registrars.
    for (Registrar registrar : Registrar.loadAll()) {
      if (!registrar.isLiveAndPubliclyVisible() || registrar.getRegistrarName() == null) {
        continue;
      }
      String normalized = normalizeRegistrarName(registrar.getRegistrarName());
      if (map.put(normalized, registrar) != null) {
        logger.atWarning().log(
            "%s appeared as a normalized registrar name for more than one registrar.", normalized);
      }
    }
    // Use the normalized registrar name without its last word as a key, assuming there are
    // multiple words in the name. This allows searches without LLC or INC, etc. Only insert
    // if there isn't already a mapping for this string, so that if there's a registrar with
    // a
    // two word name (Go Daddy) and no business-type suffix and another registrar with just
    // that first word as its name (Go), the latter will win.
    for (Registrar registrar : ImmutableList.copyOf(map.values())) {
      if (registrar.getRegistrarName() == null) {
        continue;
      }
      List<String> words =
          Splitter.on(CharMatcher.whitespace()).splitToList(registrar.getRegistrarName());
      if (words.size() > 1) {
        String normalized =
            normalizeRegistrarName(Joiner.on("").join(words.subList(0, words.size() - 1)));
        map.putIfAbsent(normalized, registrar);
      }
    }
    return ImmutableMap.copyOf(map);
  }

  @Override
  public WhoisResponse executeQuery(DateTime now) throws WhoisException {
    Map<String, Registrar> registrars =
        cached ? REGISTRAR_BY_NORMALIZED_NAME_CACHE.get() : loadRegistrarMap();
    Registrar registrar = registrars.get(normalizeRegistrarName(registrarName));
    // If a registrar is in the cache, we know it must be active and publicly visible.
    if (registrar == null) {
      throw new WhoisException(now, SC_NOT_FOUND, "No registrar found.");
    }
    return new RegistrarWhoisResponse(registrar, now);
  }
}
