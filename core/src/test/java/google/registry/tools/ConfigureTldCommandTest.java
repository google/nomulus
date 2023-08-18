// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.TestDataHelper.loadFile;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.google.common.io.Files;
import google.registry.model.EntityYamlUtils;
import google.registry.model.tld.Tld;
import java.io.File;
import org.joda.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ConfigureTldCommand} */
public class ConfigureTldCommandTest extends CommandTestCase<ConfigureTldCommand> {

  @BeforeEach
  void beforeEach() {
    command.mapper = EntityYamlUtils.createObjectMapper();
  }

  @Test
  void testSuccess_createNewTld() throws Exception {
    File tldFile = tmpDir.resolve("tld.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8).write(loadFile(getClass(), "tld.yaml"));
    runCommandForced("--input=" + tldFile);
    Tld tld = Tld.get("tld");
    assertThat(tld).isNotNull();
    assertThat(tld.getDriveFolderId()).isEqualTo("driveFolder");
    assertThat(tld.getCreateBillingCost()).isEqualTo(Money.of(USD, 25));
  }

  @Test
  void testSuccess_updateTld() throws Exception {
    Tld tld = createTld("tld");
    assertThat(tld.getCreateBillingCost()).isEqualTo(Money.of(USD, 13));
    File tldFile = tmpDir.resolve("tld.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8).write(loadFile(getClass(), "tld.yaml"));
    runCommandForced("--input=" + tldFile);
    Tld updatedTld = Tld.get("tld");
    assertThat(updatedTld.getCreateBillingCost()).isEqualTo(Money.of(USD, 25));
  }

  @Test
  void testFailure_fileMissingNullableFieldsOnCreate() throws Exception {
    File tldFile = tmpDir.resolve("missingnullablefields.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8).write(loadFile(getClass(), "missingnullablefields.yaml"));
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommandForced("--input=" + tldFile));
    assertThat(thrown.getMessage())
        .isEqualTo(
            "The input file is missing data for the following fields: [tldStateTransitions,"
                + " numDnsPublishLocks, currency]");
  }

  @Test
  void testFailure_fileMissingNullableFieldOnUpdate() throws Exception {
    Tld tld = createTld("missingnullablefields");
    persistResource(
        tld.asBuilder().setNumDnsPublishLocks(5).build()); // numDnsPublishLocks is nullable
    File tldFile = tmpDir.resolve("missingnullablefields.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8)
        .write(
            loadFile(
                getClass(), "missingnullablefields.yaml")); // file is missing numDnsPublishLocks
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommandForced("--input=" + tldFile));
    assertThat(thrown.getMessage())
        .isEqualTo(
            "The input file is missing data for the following fields: [tldStateTransitions,"
                + " numDnsPublishLocks, currency]");
  }

  @Test
  void testFailure_fileContainsExtraFields() throws Exception {
    File tldFile = tmpDir.resolve("extrafield.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8).write(loadFile(getClass(), "extrafield.yaml"));
    assertThrows(UnrecognizedPropertyException.class, () -> runCommandForced("--input=" + tldFile));
  }

  @Test
  void testFailure_fileNameDoesNotMatchTldName() throws Exception {
    File tldFile = tmpDir.resolve("othertld.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8).write(loadFile(getClass(), "tld.yaml"));
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommandForced("--input=" + tldFile));
    assertThat(thrown.getMessage())
        .isEqualTo("The input file name must match the name of the TLD it represents");
  }

  @Test
  void testFailure_tldUnicodeDoesNotMatch() throws Exception {
    File tldFile = tmpDir.resolve("badunicode.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8).write(loadFile(getClass(), "badunicode.yaml"));
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommandForced("--input=" + tldFile));
    assertThat(thrown.getMessage())
        .isEqualTo(
            "The value for tldUnicode must equal the unicode representation of the TLD name");
  }
}
