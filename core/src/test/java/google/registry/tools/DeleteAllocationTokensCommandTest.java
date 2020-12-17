// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.model.domain.token.AllocationToken.TokenType.SINGLE_USE;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.persistence.transaction.TransactionManagerUtil.transactIfJpaTm;
import static google.registry.testing.DatabaseHelper.createTlds;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenType;
import google.registry.model.reporting.HistoryEntry;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.TestOfyAndSql;
import java.util.Arrays;
import javax.annotation.Nullable;
import org.junit.jupiter.api.BeforeEach;

/** Unit tests for {@link DeleteAllocationTokensCommand}. */
@DualDatabaseTest
class DeleteAllocationTokensCommandTest extends CommandTestCase<DeleteAllocationTokensCommand> {

  private AllocationToken preRed1;
  private AllocationToken preRed2;
  private AllocationToken preNot1;
  private AllocationToken preNot2;
  private AllocationToken othrRed;
  private AllocationToken othrNot;

  @BeforeEach
  void beforeEach() {
    createTlds("foo", "bar");
    preRed1 = persistToken("prefix12345AA", null, true);
    preRed2 = persistToken("prefixgh8907a", null, true);
    preNot1 = persistToken("prefix2978204", null, false);
    preNot2 = persistToken("prefix8ZZZhs8", null, false);
    othrRed = persistToken("h97987sasdfhh", null, true);
    othrNot = persistToken("asdgfho7HASDS", null, false);
  }

  @TestOfyAndSql
  void test_deleteOnlyUnredeemedTokensWithPrefix() throws Exception {
    runCommandForced("--prefix", "prefix");
    assertNonexistent(preNot1, preNot2);
    assertThat(reloadTokens(preRed1, preRed2, othrRed, othrNot))
        .containsExactly(preRed1, preRed2, othrRed, othrNot);
  }

  @TestOfyAndSql
  void test_deleteSingleAllocationToken() throws Exception {
    runCommandForced("--prefix", "asdgfho7HASDS");
    assertNonexistent(othrNot);
    assertThat(reloadTokens(preRed1, preRed2, preNot1, preNot2, othrRed))
        .containsExactly(preRed1, preRed2, preNot1, preNot2, othrRed);
  }

  @TestOfyAndSql
  void test_deleteParticularTokens() throws Exception {
    runCommandForced("--tokens", "prefix2978204,asdgfho7HASDS");
    assertNonexistent(preNot1, othrNot);
    assertThat(reloadTokens(preRed1, preRed2, preNot2, othrRed))
        .containsExactly(preRed1, preRed2, preNot2, othrRed);
  }

  @TestOfyAndSql
  void test_deleteTokensWithNonExistentPrefix_doesNothing() throws Exception {
    runCommandForced("--prefix", "nonexistent");
    assertThat(reloadTokens(preRed1, preRed2, preNot1, preNot2, othrRed, othrNot))
        .containsExactly(preRed1, preRed2, preNot1, preNot2, othrRed, othrNot);
  }

  @TestOfyAndSql
  void test_dryRun_deletesNothing() throws Exception {
    runCommandForced("--prefix", "prefix", "--dry_run");
    assertThat(reloadTokens(preRed1, preRed2, preNot1, preNot2, othrRed, othrNot))
        .containsExactly(preRed1, preRed2, preNot1, preNot2, othrRed, othrNot);
    assertInStdout("Would delete tokens: prefix2978204, prefix8ZZZhs8");
  }

  @TestOfyAndSql
  void test_defaultOptions_doesntDeletePerDomainTokens() throws Exception {
    AllocationToken preDom1 = persistToken("prefixasdfg897as", "foo.bar", false);
    AllocationToken preDom2 = persistToken("prefix98HAZXadbn", "foo.bar", true);
    runCommandForced("--prefix", "prefix");
    assertNonexistent(preNot1, preNot2);
    assertThat(reloadTokens(preRed1, preRed2, preDom1, preDom2, othrRed, othrNot))
        .containsExactly(preRed1, preRed2, preDom1, preDom2, othrRed, othrNot);
  }

  @TestOfyAndSql
  void test_withDomains_doesDeletePerDomainTokens() throws Exception {
    AllocationToken preDom1 = persistToken("prefixasdfg897as", "foo.bar", false);
    AllocationToken preDom2 = persistToken("prefix98HAZXadbn", "foo.bar", true);
    runCommandForced("--prefix", "prefix", "--with_domains");
    assertNonexistent(preNot1, preNot2, preDom1);
    assertThat(reloadTokens(preRed1, preRed2, preDom2, othrRed, othrNot))
        .containsExactly(preRed1, preRed2, preDom2, othrRed, othrNot);
  }

  @TestOfyAndSql
  void testSkipUnlimitedUseTokens() throws Exception {
    AllocationToken unlimitedUseToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("prefixasdfg897as")
                .setTokenType(TokenType.UNLIMITED_USE)
                .build());
    runCommandForced("--prefix", "prefix");
    assertThat(reloadTokens(unlimitedUseToken)).containsExactly(unlimitedUseToken);
  }

  @TestOfyAndSql
  void test_batching() throws Exception {
    for (int i = 0; i < 50; i++) {
      persistToken(String.format("batch%2d", i), null, i % 2 == 0);
    }
    assertThat(transactIfJpaTm(() -> tm().loadAll(AllocationToken.class).size())).isEqualTo(56);
    runCommandForced("--prefix", "batch");
    assertThat(transactIfJpaTm(() -> tm().loadAll(AllocationToken.class).size()))
        .isEqualTo(56 - 25);
  }

  @TestOfyAndSql
  void test_prefixIsRequired() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, this::runCommandForced);
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Must provide one of --tokens or --prefix, not both / neither");
  }

  @TestOfyAndSql
  void testFailure_bothPrefixAndTokens() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--prefix", "somePrefix", "--tokens", "someToken"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Must provide one of --tokens or --prefix, not both / neither");
  }

  @TestOfyAndSql
  void testFailure_emptyPrefix() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommandForced("--prefix", ""));
    assertThat(thrown).hasMessageThat().isEqualTo("Provided prefix should not be blank");
  }

  private static AllocationToken persistToken(
      String token, @Nullable String domainName, boolean redeemed) {
    AllocationToken.Builder builder =
        new AllocationToken.Builder()
            .setToken(token)
            .setTokenType(SINGLE_USE)
            .setDomainName(domainName);
    if (redeemed) {
      String domainToPersist = domainName != null ? domainName : "example.foo";
      DomainBase domain = persistActiveDomain(domainToPersist);
      Key<HistoryEntry> historyEntryKey = Key.create(Key.create(domain), HistoryEntry.class, 1051L);
      builder.setRedemptionHistoryEntry(HistoryEntry.createVKey(historyEntryKey));
    }
    return persistResource(builder.build());
  }

  private static ImmutableList<AllocationToken> reloadTokens(AllocationToken... tokens) {
    return transactIfJpaTm(() -> tm().loadAll(ImmutableSet.copyOf(tokens)));
  }

  private static void assertNonexistent(AllocationToken... tokens) {
    Arrays.stream(tokens).forEach(t -> transactIfJpaTm(() -> assertThat(tm().exists(t)).isFalse()));
  }
}
