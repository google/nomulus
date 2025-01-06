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

package google.registry.tldconfig.idn;

import static google.registry.tldconfig.idn.IdnTableEnum.EXTENDED_LATIN;
import static google.registry.tldconfig.idn.IdnTableEnum.JA;

import com.google.common.collect.ImmutableSet;
import google.registry.model.tld.Tld;
import google.registry.util.Idn;
import java.util.Optional;

/** Validates whether a given IDN label can be provisioned for a particular TLD. */
public final class IdnLabelValidator {

  /** Most TLDs will use this generic list of IDN tables. */
  private static final ImmutableSet<IdnTableEnum> DEFAULT_IDN_TABLES =
      ImmutableSet.of(EXTENDED_LATIN, JA);

  /**
   * Returns name of first matching {@link IdnTable} if domain label is valid for the given TLD.
   *
   * <p>A label is valid if it is considered valid by at least one configured IDN table for that
   * TLD. If no match is found, an absent value is returned.
   */
  public Optional<String> findValidIdnTableForTld(String label, String tldStr) {
    String unicodeString = Idn.toUnicode(label);
    Tld tld = Tld.get(tldStr); // uses the cache
    ImmutableSet<IdnTableEnum> idnTables = getIdnTablesForTld(tld);
    for (IdnTableEnum idnTable : idnTables) {
      if (idnTable.getTable().isValidLabel(unicodeString)) {
        return Optional.of(idnTable.getTable().getName());
      }
    }
    return Optional.empty();
  }

  /** Returns the names of the IDN tables supported by a {@code tld}. */
  public ImmutableSet<IdnTableEnum> getIdnTablesForTld(Tld tld) {
    ImmutableSet<IdnTableEnum> idnTablesForTld = tld.getIdnTables();
    return idnTablesForTld.isEmpty() ? DEFAULT_IDN_TABLES : idnTablesForTld;
  }
}
