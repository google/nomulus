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

package google.registry.tldconfig.idn;

import static google.registry.tldconfig.idn.IdnTableEnum.ES;
import static google.registry.tldconfig.idn.IdnTableEnum.FR;
import static google.registry.tldconfig.idn.IdnTableEnum.DE;
import static google.registry.tldconfig.idn.IdnTableEnum.ZH;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.util.Idn;
import google.registry.util.NonFinalForTesting;

/** Validates whether a given IDN label can be provisioned for a particular TLD. */
public final class IdnLabelValidator {

  /** Most TLDs will use this generic list of IDN tables. */
  private static final ImmutableList<IdnTableEnum> DEFAULT_IDN_TABLES =
      ImmutableList.of(ES, FR, DE, ZH);

  /** Some TLDs have their own IDN tables, configured here. */
  @NonFinalForTesting
  private static ImmutableMap<String, ImmutableList<IdnTableEnum>> idnTableListsPerTld =
      ImmutableMap.of();

  /**
   * Returns name of first matching {@link IdnTable} if domain label is valid for the given TLD.
   *
   * <p>A label is valid if it is considered valid by at least one configured IDN table for that
   * TLD. If no match is found, an absent value is returned.
   */
  public static Optional<String> findValidIdnTableForTld(String label, String tld) {
    String unicodeString = Idn.toUnicode(label);
    for (IdnTableEnum idnTable
        : Optional.fromNullable(idnTableListsPerTld.get(tld)).or(DEFAULT_IDN_TABLES)) {
      if (idnTable.getTable().isValidLabel(unicodeString)) {
        return Optional.of(idnTable.getTable().getName());
      }
    }
    return Optional.absent();
  }

  private IdnLabelValidator() {}
}
