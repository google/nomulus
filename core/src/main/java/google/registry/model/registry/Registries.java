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

package google.registry.model.registry;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Predicates.equalTo;
import static com.google.common.base.Predicates.in;
import static com.google.common.base.Predicates.not;
import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Maps.filterValues;
import static google.registry.model.CacheUtils.memoizeWithShortExpiration;
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.CollectionUtils.entriesToImmutableMap;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.base.Joiner;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Streams;
import com.google.common.net.InternetDomainName;
import com.googlecode.objectify.Key;
import google.registry.model.registry.Registry.TldType;
import java.util.Optional;

/** Utilities for finding and listing {@link Registry} entities. */
public final class Registries {

  private Registries() {}

  /** Supplier of a cached registries map. */
  private static Supplier<ImmutableMap<String, TldType>> cache = createFreshCache();

  /**
   * Returns a newly-created Supplier of a registries to types map.
   *
   * <p>The supplier's get() method enters a transactionless context briefly to avoid enrolling the
   * query inside an unrelated client-affecting transaction.
   */
  private static Supplier<ImmutableMap<String, TldType>> createFreshCache() {
    return memoizeWithShortExpiration(
        () ->
            tm().doTransactionless(
                    () -> {
                      ImmutableSet<String> tlds =
                          tm().isOfy()
                              ? ofy()
                                  .load()
                                  .type(Registry.class)
                                  .ancestor(getCrossTldKey())
                                  .keys()
                                  .list()
                                  .stream()
                                  .map(Key::getName)
                                  .collect(toImmutableSet())
                              : tm().loadAll(Registry.class).stream()
                                  .map(Registry::getTldStr)
                                  .collect(toImmutableSet());
                      return Registry.getAll(tlds).stream()
                          .map(e -> Maps.immutableEntry(e.getTldStr(), e.getTldType()))
                          .collect(entriesToImmutableMap());
                    }));
  }

  /** Manually reset the static cache backing the methods on this class. */
  // TODO(b/24903801): offer explicit cached and uncached paths instead.
  public static void resetCache() {
    cache = createFreshCache();
  }

  public static ImmutableSet<String> getTlds() {
    return cache.get().keySet();
  }

  public static ImmutableSet<String> getTldsOfType(TldType type) {
    return ImmutableSet.copyOf(filterValues(cache.get(), equalTo(type)).keySet());
  }

  /** Returns the Registry entities themselves of the given type loaded fresh from Datastore. */
  public static ImmutableSet<Registry> getTldEntitiesOfType(TldType type) {
    return Registry.getAll(filterValues(cache.get(), equalTo(type)).keySet());
  }

  /** Pass-through check that the specified TLD exists, otherwise throw an IAE. */
  public static String assertTldExists(String tld) {
    checkArgument(
        getTlds().contains(checkArgumentNotNull(emptyToNull(tld), "Null or empty TLD specified")),
        "TLD %s does not exist",
        tld);
    return tld;
  }

  /** Pass-through check that every TLD in the given iterable exists, otherwise throw an IAE. */
  public static Iterable<String> assertTldsExist(Iterable<String> tlds) {
    for (String tld : tlds) {
      checkArgumentNotNull(emptyToNull(tld), "Null or empty TLD specified");
    }
    ImmutableSet<String> badTlds =
        Streams.stream(tlds).filter(not(in(getTlds()))).collect(toImmutableSet());
    checkArgument(badTlds.isEmpty(), "TLDs do not exist: %s", Joiner.on(", ").join(badTlds));
    return tlds;
  }

  /**
   * Returns TLD which the domain name or hostname falls under, no matter how many levels of
   * sublabels there are.
   *
   * <p><b>Note:</b> This routine will only work on names under TLDs for which this registry is
   * authoritative. To extract TLDs from domains (not hosts) that other registries control, use
   * {@link google.registry.util.DomainNameUtils#getTldFromDomainName(String)
   * DomainNameUtils#getTldFromDomainName}.
   *
   * @param domainName domain name or host name (but not TLD) under an authoritative TLD
   * @return TLD or absent if {@code domainName} has no labels under an authoritative TLD
   */
  public static Optional<InternetDomainName> findTldForName(InternetDomainName domainName) {
    ImmutableSet<String> tlds = getTlds();
    while (domainName.hasParent()) {
      domainName = domainName.parent();
      if (tlds.contains(domainName.toString())) {
        return Optional.of(domainName);
      }
    }
    return Optional.empty();
  }

  /**
   * Returns the registered TLD which this domain name falls under, or throws an exception if no
   * match exists.
   */
  public static InternetDomainName findTldForNameOrThrow(InternetDomainName domainName) {
    return checkArgumentNotNull(
        findTldForName(domainName).orElse(null),
        "Domain name is not under a recognized TLD: %s", domainName.toString());
  }
}
