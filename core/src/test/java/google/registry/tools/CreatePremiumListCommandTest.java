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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import google.registry.dns.writer.VoidDnsWriter;
import google.registry.model.pricing.StaticPremiumListPricingEngine;
import google.registry.model.registry.Registry;
import google.registry.model.registry.label.PremiumListDualDao;
import java.io.File;
import java.nio.file.Paths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CreatePremiumListCommand}. */
class CreatePremiumListCommandTest<C extends CreatePremiumListCommand>
    extends CreateOrUpdatePremiumListCommandTestCase<C> {
  Registry registry;

  @BeforeEach
  void beforeEach() {
    registry =
        new Registry.Builder()
            .setTldStr(TLD_TEST)
            .setPremiumPricingEngine(StaticPremiumListPricingEngine.NAME)
            .setDnsWriters(ImmutableSet.of(VoidDnsWriter.NAME))
            .setPremiumList(null)
            .build();
    tm().transact(() -> tm().put(registry));
  }

  @Test
  void testSuccess_createList() throws Exception {
    // ensure that no premium list is created before running the command
    assertThat(PremiumListDualDao.exists(TLD_TEST)).isFalse();
    runCommandForced("--name=" + TLD_TEST, "--input=" + premiumTermsPath);
    assertThat(registry.getTld().toString()).isEqualTo(TLD_TEST);
  }

  @Test
  void testSuccess_stageNewEntity() throws Exception {
    CreatePremiumListCommand command = new CreatePremiumListCommand();
    command.inputFile = Paths.get(premiumTermsPath);
    command.init();
    assertThat(command.prompt()).contains("Create PremiumList@");
  }

  @Test
  void testFailure_noInputFile() throws Exception {
    CreatePremiumListCommand command = new CreatePremiumListCommand();
    assertThrows(NullPointerException.class, command::init);
  }

  @Test
  void testFailure_listAlreadyExists() throws Exception {
    String randomStr = "random";
    createTld(randomStr);
    CreatePremiumListCommand command = new CreatePremiumListCommand();
    command.name = randomStr;
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::init);
    assertThat(thrown)
        .hasMessageThat().isEqualTo("A premium list already exists by this name");
  }

  @Test
  void testFailure_mismatchedTldFileName_noOverride() {
    CreatePremiumListCommand command = new CreatePremiumListCommand();
    String randomStr = "random";
    command.name = "random";
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::init);
    assertThat(thrown)
        .hasMessageThat()
        .contains(String.format( "Premium names must match the name of the TLD they are "
            + "intended to be used on (unless --override is specified), "
            + "yet TLD %s does not exist", randomStr));
  }

  @Test
  void testFailure_mismatchedTldName_noOverride() throws Exception {
    CreatePremiumListCommand command = new CreatePremiumListCommand();
    String randomStr = "random";
    command.name = "random";
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::init);
    assertThat(thrown)
        .hasMessageThat()
        .contains(String.format( "Premium names must match the name of the TLD they are "
            + "intended to be used on (unless --override is specified), "
            + "yet TLD %s does not exist", randomStr));
  }

  @Test
  void testFailure_emptyFile() throws Exception {
    CreatePremiumListCommand command = new CreatePremiumListCommand();
    File premiumTermsFile = tmpDir.resolve("empty.txt").toFile();
    String premiumTermsCsv = "";
    Files.asCharSink(premiumTermsFile, UTF_8).write(premiumTermsCsv);
    String tmpPremiumTermsPath = premiumTermsFile.getPath();
    command.inputFile = Paths.get(tmpPremiumTermsPath);
    command.name = TLD_TEST;
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::init);
    assertThat(thrown)
        .hasMessageThat()
        .contains("The Cloud SQL schema requires exactly one currency");
  }



}
