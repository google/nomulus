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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Maps.transformValues;
import static google.registry.model.tld.Tld.isEnrolledWithBsa;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tld.TldType;
import google.registry.model.tld.Tlds;
import google.registry.tldconfig.idn.IdnLabelValidator;
import google.registry.tldconfig.idn.IdnTableEnum;
import google.registry.util.Clock;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * Checks labels' validity wrt Idns in TLDs enrolled with BSA.
 *
 * <p>Each instance takes a snapshot of the TLDs at instantiation time, and should be limited to the
 * Request scope.
 */
public class IdnChecker {
  private static final IdnLabelValidator IDN_LABEL_VALIDATOR = new IdnLabelValidator();

  private final ImmutableMap<IdnTableEnum, ImmutableSet<Tld>> idnToTlds;
  private final ImmutableSet<Tld> allTlds;

  @Inject
  IdnChecker(Clock clock) {
    this.idnToTlds = getIdnToTldMap(clock.nowUtc());
    allTlds = idnToTlds.values().stream().flatMap(ImmutableSet::stream).collect(toImmutableSet());
  }

  /** Returns all IDNs in which the {@code label} is valid. */
  public ImmutableSet<IdnTableEnum> getAllValidIdns(String label) {
    return idnToTlds.keySet().stream()
        .filter(idnTable -> idnTable.getTable().isValidLabel(label))
        .collect(toImmutableSet());
  }

  /**
   * Returns the TLDs that support at least one IDN in the {@code idnTables}.
   *
   * @param idnTables String names of {@link IdnTableEnum} values
   */
  public ImmutableSet<Tld> getSupportingTlds(ImmutableSet<String> idnTables) {
    return idnTables.stream()
        .map(IdnTableEnum::valueOf)
        .filter(idnToTlds::containsKey)
        .map(idnToTlds::get)
        .flatMap(ImmutableSet::stream)
        .collect(toImmutableSet());
  }

  /**
   * Returns the TLDs that do not support any IDN in the {@code idnTables}.
   *
   * @param idnTables String names of {@link IdnTableEnum} values
   */
  public ImmutableSet<Tld> getForbiddingTlds(ImmutableSet<String> idnTables) {
    return Sets.difference(allTlds, getSupportingTlds(idnTables)).immutableCopy();
  }

  private static ImmutableMap<IdnTableEnum, ImmutableSet<Tld>> getIdnToTldMap(DateTime now) {
    var idnToTldMap = new ImmutableMultimap.Builder<IdnTableEnum, Tld>();
    Tlds.getTldEntitiesOfType(TldType.REAL).stream()
        .filter(tld -> isEnrolledWithBsa(tld, now))
        .forEach(
            tld -> {
              for (IdnTableEnum idn : IDN_LABEL_VALIDATOR.getIdnTablesForTld(tld)) {
                idnToTldMap.put(idn, tld);
              }
            });
    return ImmutableMap.copyOf(transformValues(idnToTldMap.build().asMap(), ImmutableSet::copyOf));
  }
}
