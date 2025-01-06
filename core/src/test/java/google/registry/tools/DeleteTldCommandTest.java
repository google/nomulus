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

package google.registry.tools;

import static google.registry.model.tld.Tld.TldState.GENERAL_AVAILABILITY;
import static google.registry.testing.DatabaseHelper.allowRegistrarAccess;
import static google.registry.testing.DatabaseHelper.newTld;
import static google.registry.testing.DatabaseHelper.persistDeletedDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tld.TldNotFoundException;
import google.registry.model.tld.Tld.TldType;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DeleteTldCommand}. */
class DeleteTldCommandTest extends CommandTestCase<DeleteTldCommand> {

  private static final String TLD_REAL = "tldreal";
  private static final String TLD_TEST = "tldtest";

  @BeforeEach
  void beforeEach() {
    persistResource(
        newTld(
            TLD_REAL,
            Ascii.toUpperCase(TLD_REAL),
            ImmutableSortedMap.of(START_OF_TIME, GENERAL_AVAILABILITY),
            TldType.REAL));
    persistResource(
        newTld(
            TLD_TEST,
            Ascii.toUpperCase(TLD_TEST),
            ImmutableSortedMap.of(START_OF_TIME, GENERAL_AVAILABILITY),
            TldType.TEST));
  }

  @Test
  void testSuccess_otherTldUnaffected() throws Exception {
    runCommandForced("--tld=" + TLD_TEST);

    Tld.get(TLD_REAL);
    assertThrows(TldNotFoundException.class, () -> Tld.get(TLD_TEST));
  }

  @Test
  void testFailure_whenTldDoesNotExist() {
    assertThrows(TldNotFoundException.class, () -> runCommandForced("--tld=nonexistenttld"));
  }

  @Test
  void testFailure_whenTldIsReal() {
    assertThrows(IllegalStateException.class, () -> runCommandForced("--tld=" + TLD_REAL));
  }

  @Test
  void testFailure_whenDomainsArePresent() {
    persistDeletedDomain("domain." + TLD_TEST, DateTime.parse("2000-01-01TZ"));

    assertThrows(IllegalStateException.class, () -> runCommandForced("--tld=" + TLD_TEST));
  }

  @Test
  void testFailure_whenRegistrarLinksToTld() {
    allowRegistrarAccess("TheRegistrar", TLD_TEST);

    assertThrows(IllegalStateException.class, () -> runCommandForced("--tld=" + TLD_TEST));
  }
}
