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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.immutableObjectCorrespondence;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.joda.money.CurrencyUnit.JPY;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.io.Files;
import google.registry.model.tld.Tld;
import google.registry.model.tld.label.PremiumList;
import google.registry.model.tld.label.PremiumList.PremiumEntry;
import google.registry.model.tld.label.PremiumListDao;
import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.joda.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link UpdatePremiumListCommand}. */
class UpdatePremiumListCommandTest<C extends UpdatePremiumListCommand>
    extends CreateOrUpdatePremiumListCommandTestCase<C> {
  Tld registry;

  @BeforeEach
  void beforeEach() {
    command.clock = fakeClock;
    registry = createTld(TLD_TEST, USD, initialPremiumListData);
  }

  @Test
  void verify_registryIsSetUpCorrectly() {
    Optional<PremiumList> list = PremiumListDao.getLatestRevision(TLD_TEST);
    // ensure that no premium list is created before running the command
    assertThat(list.isPresent()).isTrue();
    // data from @beforeEach of CreateOrUpdatePremiumListCommandTestCase.java
    assertThat(PremiumListDao.loadPremiumEntries(list.get()))
        .comparingElementsUsing(immutableObjectCorrespondence("revisionId"))
        .containsExactly(PremiumEntry.create(0L, new BigDecimal("9090.00"), "doge"));
  }

  @Test
  void commandPrompt_successStageEntityChange() throws Exception {
    File tmpFile = tmpDir.resolve(String.format("%s.txt", TLD_TEST)).toFile();
    String newPremiumListData = "omg,USD 1234";
    Files.asCharSink(tmpFile, UTF_8).write(newPremiumListData);
    command.inputFile = Paths.get(tmpFile.getPath());
    command.name = TLD_TEST;
    assertThat(command.prompt()).contains("Update premium list for prime?");
  }

  @Test
  void commandPrompt_successStageNoChange() throws Exception {
    File tmpFile = tmpDir.resolve(String.format("%s.txt", TLD_TEST)).toFile();
    command.inputFile = Paths.get(tmpFile.getPath());
    command.name = TLD_TEST;
    assertThat(command.prompt())
        .contains("This update contains no changes to the premium list for prime.");
  }

  @Test
  void commandRun_successUpdateList() throws Exception {
    File tmpFile = tmpDir.resolve(String.format("%s.txt", TLD_TEST)).toFile();
    String newPremiumListData = "eth,USD 9999";
    Files.asCharSink(tmpFile, UTF_8).write(newPremiumListData);

    // data come from @beforeEach of CreateOrUpdatePremiumListCommandTestCase.java
    command.inputFile = Paths.get(tmpFile.getPath());
    runCommandForced("--name=" + TLD_TEST, "--input=" + command.inputFile);

    assertThat(PremiumListDao.loadAllPremiumEntries(TLD_TEST))
        .comparingElementsUsing(immutableObjectCorrespondence("revisionId"))
        .containsExactly(PremiumEntry.create(0L, new BigDecimal("9999.00"), "eth"));
  }

  @Test
  void commandRun_successNoChange() throws Exception {
    File tmpFile = tmpDir.resolve(String.format("%s.txt", TLD_TEST)).toFile();

    command.inputFile = Paths.get(tmpFile.getPath());
    runCommandForced("--name=" + TLD_TEST, "--input=" + command.inputFile);

    assertThat(PremiumListDao.loadAllPremiumEntries(TLD_TEST))
        .containsExactly(PremiumEntry.create(0L, new BigDecimal("9090.00"), "doge"));
  }

  @Test
  void commandRun_successUpdateList_whenExistingListIsEmpty() throws Exception {
    File existingPremiumFile = tmpDir.resolve(TLD_TEST + ".txt").toFile();
    Files.asCharSink(existingPremiumFile, UTF_8).write("");

    File newPremiumFile = tmpDir.resolve(String.format("%s.txt", TLD_TEST)).toFile();
    String newPremiumListData = "eth,USD 9999";
    Files.asCharSink(newPremiumFile, UTF_8).write(newPremiumListData);

    // data come from @beforeEach of CreateOrUpdatePremiumListCommandTestCase.java
    command.inputFile = Paths.get(newPremiumFile.getPath());
    runCommandForced("--name=" + TLD_TEST, "--input=" + command.inputFile);

    assertThat(PremiumListDao.loadAllPremiumEntries(TLD_TEST))
        .comparingElementsUsing(immutableObjectCorrespondence("revisionId"))
        .containsExactly(PremiumEntry.create(0L, new BigDecimal("9999.00"), "eth"));
  }

  @Test
  void commandRun_successUpdateMultiLineList() throws Exception {
    File tmpFile = tmpDir.resolve(TLD_TEST + ".txt").toFile();
    String premiumTerms = "foo,USD 9000\ndoge,USD 100\nelon,USD 2021";
    Files.asCharSink(tmpFile, UTF_8).write(premiumTerms);

    command.inputFile = Paths.get(tmpFile.getPath());
    runCommandForced("--name=" + TLD_TEST, "--input=" + command.inputFile);

    // assert all three lines from premiumTerms are added
    assertThat(
            PremiumListDao.loadAllPremiumEntries(TLD_TEST).stream()
                .map(Object::toString)
                .collect(toImmutableList()))
        .containsExactly("foo, 9000.00", "doge, 100.00", "elon, 2021.00");
  }

  @Test
  void commandPrompt_failureUpdateEmptyList() throws Exception {
    Path tmpPath = tmpDir.resolve(String.format("%s.txt", TLD_TEST));
    Files.write(new byte[0], tmpPath.toFile());

    command.inputFile = tmpPath;
    command.name = TLD_TEST;
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::prompt);
    assertThat(thrown).hasMessageThat().isEqualTo("New premium list data cannot be empty");
  }

  @Test
  void commandPrompt_failureNoPreviousVersion() {
    registry = createTld("random", null, null);
    command.name = "random";
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::prompt);
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Could not update premium list random because it doesn't exist");
  }

  @Test
  void commandPrompt_failureNoInputFile() {
    assertThrows(NullPointerException.class, command::prompt);
  }

  @Test
  void commandPrompt_failureTldFromNameDoesNotExist() {
    command.name = "random2";
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::prompt);
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Could not update premium list random2 because it doesn't exist");
  }

  @Test
  void commandPrompt_failureTldFromInputFileDoesNotExist() {
    // using tld extracted from file name but this tld is not part of the registry
    command.inputFile = Paths.get(tmpDir.resolve("random3.txt").toFile().getPath());
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::prompt);
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Could not update premium list random3 because it doesn't exist");
  }

  @Test
  void commandDryRun_noChangesMade() throws Exception {
    File tmpFile = tmpDir.resolve(String.format("%s.txt", TLD_TEST)).toFile();
    String newPremiumListData = "eth,USD 9999";
    Files.asCharSink(tmpFile, UTF_8).write(newPremiumListData);

    command.inputFile = Paths.get(tmpFile.getPath());
    runCommandForced("--name=" + TLD_TEST, "--input=" + command.inputFile, "--dry_run");

    assertThat(PremiumListDao.loadAllPremiumEntries(TLD_TEST))
        .comparingElementsUsing(immutableObjectCorrespondence("revisionId"))
        .containsExactly(PremiumEntry.create(0L, new BigDecimal("9090.00"), "doge"));
  }

  @Test
  void testFailure_runCommandOnProduction_noFlag() throws Exception {
    File tmpFile = tmpDir.resolve(String.format("%s.txt", TLD_TEST)).toFile();
    String newPremiumListData = "eth,USD 9999";
    Files.asCharSink(tmpFile, UTF_8).write(newPremiumListData);
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandInEnvironment(
                    RegistryToolEnvironment.PRODUCTION,
                    "--name=" + TLD_TEST,
                    "--input=" + Paths.get(tmpFile.getPath())));
    assertThat(thrown.getMessage())
        .isEqualTo("The --build_environment flag must be used when running in production");
  }

  @Test
  void testSuccess_runCommandOnProduction_buildEnvFlag() throws Exception {
    File tmpFile = tmpDir.resolve(String.format("%s.txt", TLD_TEST)).toFile();
    String newPremiumListData = "eth,USD 9999";
    Files.asCharSink(tmpFile, UTF_8).write(newPremiumListData);
    runCommandInEnvironment(
        RegistryToolEnvironment.PRODUCTION,
        "--name=" + TLD_TEST,
        "--input=" + Paths.get(tmpFile.getPath()),
        "--build_environment",
        "-f");
    assertThat(PremiumListDao.loadAllPremiumEntries(TLD_TEST))
        .comparingElementsUsing(immutableObjectCorrespondence("revisionId"))
        .containsExactly(PremiumEntry.create(0L, new BigDecimal("9999.00"), "eth"));
  }

  @Test
  void commandRun_failureMalformedInputFile() throws Exception {
    File malformedFile = tmpDir.resolve(TLD_TEST + "_malformed.txt").toFile();
    Files.asCharSink(malformedFile, UTF_8).write("malformed_line_without_comma\n");
    assertThrows(
        IllegalArgumentException.class,
        () -> runCommandForced("--name=" + TLD_TEST, "--input=" + malformedFile.getPath()));
  }

  @Test
  void commandRun_failureCurrencyMismatchInInputFile() throws Exception {
    File mismatchedFile = tmpDir.resolve(TLD_TEST + "_jpy.txt").toFile();
    Files.asCharSink(mismatchedFile, UTF_8).write("doge,JPY 9090\n");
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--name=" + TLD_TEST, "--input=" + mismatchedFile.getPath()));
    assertThat(thrown).hasMessageThat().isEqualTo("The currency unit must be USD");
  }

  @Test
  void commandPrompt_successCreateListWithUpsert() throws Exception {
    File tmpFile = tmpDir.resolve("newlist.txt").toFile();
    Files.asCharSink(tmpFile, UTF_8).write("omg,USD 1234");
    command.inputFile = Paths.get(tmpFile.getPath());
    command.name = "newlist";
    command.upsert = true;
    assertThat(command.prompt()).contains("Create new premium list for newlist?");
  }

  @Test
  void commandRun_successCreateListWithUpsert_usd() throws Exception {
    File tmpFile = tmpDir.resolve("newlist.txt").toFile();
    Files.asCharSink(tmpFile, UTF_8).write("eth,USD 9999");
    runCommandForced("--name=newlist", "--input=" + tmpFile.getPath(), "--upsert");

    Optional<PremiumList> list = PremiumListDao.getLatestRevision("newlist");
    assertThat(list).isPresent();
    assertThat(list.get().getCurrency()).isEqualTo(USD);
    assertThat(PremiumListDao.loadAllPremiumEntries("newlist"))
        .comparingElementsUsing(immutableObjectCorrespondence("revisionId"))
        .containsExactly(PremiumEntry.create(0L, new BigDecimal("9999.00"), "eth"));
  }

  @Test
  void commandRun_successCreateListWithUpsertShortFlag_nonUsdAndComments() throws Exception {
    File tmpFile = tmpDir.resolve("jpylist.txt").toFile();
    String content =
        """
        # Leading comment
           # Indented comment

        doge,JPY 9090 # inline comment
        eth,JPY 100
        """;
    Files.asCharSink(tmpFile, UTF_8).write(content);
    runCommandForced("-i=" + tmpFile.getPath(), "-u");

    Optional<PremiumList> list = PremiumListDao.getLatestRevision("jpylist");
    assertThat(list).isPresent();
    assertThat(list.get().getCurrency()).isEqualTo(JPY);
    assertThat(PremiumListDao.getPremiumPrice("jpylist", "doge"))
        .hasValue(Money.ofMajor(JPY, 9090));
    assertThat(PremiumListDao.getPremiumPrice("jpylist", "eth")).hasValue(Money.ofMajor(JPY, 100));
  }

  @Test
  void commandRun_successUpdateExistingListWithUpsert() throws Exception {
    File tmpFile = tmpDir.resolve(String.format("%s.txt", TLD_TEST)).toFile();
    Files.asCharSink(tmpFile, UTF_8).write("eth,USD 9999");
    runCommandForced("--name=" + TLD_TEST, "--input=" + tmpFile.getPath(), "--upsert");

    Optional<PremiumList> list = PremiumListDao.getLatestRevision(TLD_TEST);
    assertThat(list).isPresent();
    assertThat(list.get().getCurrency()).isEqualTo(USD);
    assertThat(PremiumListDao.loadAllPremiumEntries(TLD_TEST))
        .comparingElementsUsing(immutableObjectCorrespondence("revisionId"))
        .containsExactly(PremiumEntry.create(0L, new BigDecimal("9999.00"), "eth"));
  }

  @Test
  void commandDryRun_upsertNewList_noChangesMade() throws Exception {
    File tmpFile = tmpDir.resolve("dryrunlist.txt").toFile();
    Files.asCharSink(tmpFile, UTF_8).write("eth,EUR 99.50");
    runCommandForced("--name=dryrunlist", "--input=" + tmpFile.getPath(), "--upsert", "--dry_run");

    assertThat(PremiumListDao.getLatestRevision("dryrunlist")).isEmpty();
  }

  @Test
  void commandRun_failureUpsertEmptyFile() throws Exception {
    File emptyFile = tmpDir.resolve("empty.txt").toFile();
    Files.write(new byte[0], emptyFile);
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--name=empty", "--input=" + emptyFile.getPath(), "--upsert"));
    assertThat(thrown).hasMessageThat().isEqualTo("New premium list data cannot be empty");
    assertThat(PremiumListDao.getLatestRevision("empty")).isEmpty();
  }

  @Test
  void commandRun_failureUpsertCommentOnlyFile() throws Exception {
    File commentFile = tmpDir.resolve("comments.txt").toFile();
    Files.asCharSink(commentFile, UTF_8).write("# only comment\n   \n");
    assertThrows(
        IllegalArgumentException.class,
        () -> runCommandForced("--name=comments", "--input=" + commentFile.getPath(), "--upsert"));
    assertThat(PremiumListDao.getLatestRevision("comments")).isEmpty();
  }

  @Test
  void commandRun_failureUpsertMissingCurrencyToken() throws Exception {
    File noCurrencyFile = tmpDir.resolve("nocurrency.txt").toFile();
    Files.asCharSink(noCurrencyFile, UTF_8).write("doge,9090\n");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--name=nocurrency", "--input=" + noCurrencyFile.getPath(), "--upsert"));
    assertThat(PremiumListDao.getLatestRevision("nocurrency")).isEmpty();
  }

  @Test
  void commandRun_failureUpsertMismatchedCurrenciesAcrossLines() throws Exception {
    File mixedFile = tmpDir.resolve("mixed.txt").toFile();
    Files.asCharSink(mixedFile, UTF_8).write("doge,EUR 9090\neth,USD 100\n");
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--name=mixed", "--input=" + mixedFile.getPath(), "--upsert"));
    assertThat(thrown).hasMessageThat().isEqualTo("The currency unit must be EUR");
    assertThat(PremiumListDao.getLatestRevision("mixed")).isEmpty();
  }

  @Test
  void commandRun_failureUpsertInvalidPriceLine() throws Exception {
    File invalidFile = tmpDir.resolve("invalidprice.txt").toFile();
    Files.asCharSink(invalidFile, UTF_8).write("doge,USD not_a_price\n");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--name=invalidprice", "--input=" + invalidFile.getPath(), "--upsert"));
    assertThat(PremiumListDao.getLatestRevision("invalidprice")).isEmpty();
  }
}
