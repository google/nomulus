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

package google.registry.tools;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.persistence.transaction.TransactionManagerUtil.transactIfJpaTm;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import google.registry.model.common.Cursor;
import google.registry.model.common.Cursor.CursorType;
import google.registry.model.registry.Registries;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.TldType;
import google.registry.persistence.VKey;
import java.util.Map;
import java.util.Optional;

/** Lists {@link Cursor} timestamps used by locking rolling cursor tasks, like in RDE. */
@Parameters(separators = " =", commandDescription = "Lists cursor timestamps used by LRC tasks")
final class ListCursorsCommand implements CommandWithRemoteApi {

  @Parameter(names = "--type", description = "Which cursor to list.", required = true)
  private CursorType cursorType;

  @Parameter(
      names = "--tld_type",
      description = "Filter TLDs of a certain type (REAL or TEST; defaults to REAL).")
  private TldType filterTldType = TldType.REAL;

  @Parameter(
      names = "--escrow_enabled",
      description = "Filter TLDs to only include those with RDE escrow enabled; defaults to false.")
  private boolean filterEscrowEnabled = false;

  private static final String OUTPUT_FMT = "%-20s   %-24s   %-24s";

  @Override
  public void run() {
    Map<Registry, VKey<Cursor>> registries =
        Registries.getTlds().stream()
            .map(Registry::get)
            .filter(r -> r.getTldType() == filterTldType)
            .filter(r -> !filterEscrowEnabled || r.getEscrowEnabled())
            .collect(toImmutableMap(r -> r, r -> Cursor.createVKey(cursorType, r.getTldStr())));
    ImmutableMap<VKey<? extends Cursor>, Cursor> cursors =
        transactIfJpaTm(() -> tm().loadByKeysIfPresent(registries.values()));
    if (!registries.isEmpty()) {
      String header = String.format(OUTPUT_FMT, "TLD", "Cursor Time", "Last Update Time");
      System.out.printf("%s\n%s\n", header, Strings.repeat("-", header.length()));
      registries.entrySet().stream()
          .map(
              e ->
                  renderLine(
                      e.getKey().getTldStr(), Optional.ofNullable(cursors.get(e.getValue()))))
          .sorted()
          .forEach(System.out::println);
    }
  }

  private static String renderLine(String tld, Optional<Cursor> cursor) {
    return String.format(
        OUTPUT_FMT,
        tld,
        cursor.map(c -> c.getCursorTime().toString()).orElse("(absent)"),
        cursor.map(c -> c.getLastUpdateTime().toString()).orElse("(absent)"));
  }
}
