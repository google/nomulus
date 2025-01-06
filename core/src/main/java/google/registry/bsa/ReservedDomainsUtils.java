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

package google.registry.bsa;

import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.bsa.BsaStringUtils.DOMAIN_JOINER;
import static google.registry.flows.domain.DomainFlowUtils.isReserved;
import static google.registry.model.tld.Tlds.findTldForName;
import static google.registry.model.tld.label.ReservedList.loadReservedLists;

import com.google.common.collect.ImmutableSet;
import com.google.common.net.InternetDomainName;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tld.TldState;
import google.registry.model.tld.Tld.TldType;
import google.registry.model.tld.Tlds;
import google.registry.model.tld.label.ReservedList;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.joda.time.DateTime;

/**
 * Utility for looking up reserved domain names.
 *
 * <p>This utility is only concerned with reserved domains that can be created (with appropriate
 * tokens).
 */
public final class ReservedDomainsUtils {

  private ReservedDomainsUtils() {}

  public static Stream<String> getAllReservedNames(DateTime now) {
    return Tlds.getTldEntitiesOfType(TldType.REAL).stream()
        .filter(tld -> Tld.isEnrolledWithBsa(tld, now))
        .map(tld -> getAllReservedDomainsInTld(tld, now))
        .flatMap(ImmutableSet::stream);
  }

  /** Returns all reserved domains in a given {@code tld} as of {@code now}. */
  static ImmutableSet<String> getAllReservedDomainsInTld(Tld tld, DateTime now) {
    return loadReservedLists(tld.getReservedListNames()).stream()
        .map(ReservedList::getReservedListEntries)
        .map(Map::keySet)
        .flatMap(Set::stream)
        .map(label -> DOMAIN_JOINER.join(label, tld.getTldStr()))
        .filter(domain -> isReservedDomain(domain, now))
        .collect(toImmutableSet());
  }

  /**
   * Returns true if {@code domain} is a reserved name that can be registered right now (e.g.,
   * during sunrise or with allocation token), therefore unblockable.
   */
  public static boolean isReservedDomain(String domain, DateTime now) {
    Optional<InternetDomainName> tldStr = findTldForName(InternetDomainName.from(domain));
    verify(tldStr.isPresent(), "Tld for domain [%s] unexpectedly missing.", domain);
    Tld tld = Tld.get(tldStr.get().toString());
    return isReserved(
        InternetDomainName.from(domain),
        Objects.equals(tld.getTldState(now), TldState.START_DATE_SUNRISE));
  }
}
