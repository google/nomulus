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

package google.registry.model.tld;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Predicates.equalTo;
import static com.google.common.base.Predicates.in;
import static com.google.common.base.Predicates.not;
import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Maps.filterValues;
import static google.registry.model.CacheUtils.memoizeWithShortExpiration;
import static google.registry.model.tld.Tld.isEnrolledWithBsa;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.CollectionUtils.entriesToImmutableMap;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Streams;
import com.google.common.net.InternetDomainName;
import google.registry.model.tld.Tld.TldType;
import google.registry.util.DomainNameUtils;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.joda.time.DateTime;

/** Utilities for finding and listing {@link Tld} entities. */
public final class Tlds {

  private Tlds() {}

  /** Supplier of a cached TLDs map. */
  private static Supplier<ImmutableMap<String, TldType>> cache = createFreshCache();

  /**
   * Returns a newly-created Supplier of a TLDs to types map.
   *
   * <p>The supplier's get() method enters a transactionless context briefly to avoid enrolling the
   * query inside an unrelated client-affecting transaction.
   */
  private static Supplier<ImmutableMap<String, TldType>> createFreshCache() {
    return memoizeWithShortExpiration(
        () ->
            tm().reTransact(
                    () -> {
                      EntityManager entityManager = tm().getEntityManager();
                      Stream<?> resultStream =
                          entityManager
                              .createQuery("SELECT tldStr, tldType FROM Tld")
                              .getResultStream();
                      return resultStream
                          .map(e -> ((Object[]) e))
                          .map(e -> Maps.immutableEntry((String) e[0], ((TldType) e[1])))
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

  /** Returns the TLD entities themselves of the given type loaded fresh from the database. */
  public static ImmutableSet<Tld> getTldEntitiesOfType(TldType type) {
    return Tld.get(filterValues(cache.get(), equalTo(type)).keySet());
  }

  /** Pass-through check that the specified TLD exists, otherwise throw an IAE. */
  public static String assertTldExists(String tld) {
    checkArgument(
        getTlds().contains(checkArgumentNotNull(emptyToNull(tld), "Null or empty TLD specified")),
        "TLD %s does not exist",
        tld);
    return tld;
  }

  /**
   * Pass-through check that the TLD exists, otherwise throw using the given error format message.
   *
   * <p>The specified TLD will be passed to the format message string.
   */
  public static String assertTldExists(String tld, String fmtMessage) {
    String message = String.format(fmtMessage, tld);
    checkArgument(getTlds().contains(checkArgumentNotNull(emptyToNull(tld), message)), message);
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
   * {@link DomainNameUtils#getTldFromDomainName(String) DomainNameUtils#getTldFromDomainName}.
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

  /** Returns true if at least one TLD is enrolled {@code now}. */
  public static boolean hasActiveBsaEnrollment(DateTime now) {
    return getTldEntitiesOfType(TldType.REAL).stream().anyMatch(tld -> isEnrolledWithBsa(tld, now));
  }
}
