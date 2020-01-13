// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.model.domain.token.AllocationToken.TokenStatus.CANCELLED;
import static google.registry.model.domain.token.AllocationToken.TokenStatus.ENDED;
import static google.registry.model.domain.token.AllocationToken.TokenStatus.NOT_STARTED;
import static google.registry.model.domain.token.AllocationToken.TokenStatus.VALID;
import static google.registry.model.domain.token.AllocationToken.TokenType.SINGLE_USE;
import static google.registry.model.domain.token.AllocationToken.TokenType.UNLIMITED_USE;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenStatus;
import org.joda.time.DateTime;
import org.junit.Test;

public class UpdateAllocationTokensCommandTest
    extends CommandTestCase<UpdateAllocationTokensCommand> {

  @Test
  public void testUpdateTlds_setTlds() throws Exception {
    AllocationToken token =
        persistResource(builderWithPromo().setAllowedTlds(ImmutableSet.of("toRemove")).build());
    runCommandForced("--prefix", "token", "--allowed_tlds", "tld,example");
    assertThat(reloadResource(token).getAllowedTlds()).containsExactly("tld", "example");
  }

  @Test
  public void testUpdateTlds_clearTlds() throws Exception {
    AllocationToken token =
        persistResource(builderWithPromo().setAllowedTlds(ImmutableSet.of("toRemove")).build());
    runCommandForced("--prefix", "token", "--allowed_tlds", "");
    assertThat(reloadResource(token).getAllowedTlds()).isEmpty();
  }

  @Test
  public void testUpdateClientIds_setClientIds() throws Exception {
    AllocationToken token =
        persistResource(
            builderWithPromo().setAllowedClientIds(ImmutableSet.of("toRemove")).build());
    runCommandForced("--prefix", "token", "--allowed_client_ids", "clientone,clienttwo");
    assertThat(reloadResource(token).getAllowedClientIds())
        .containsExactly("clientone", "clienttwo");
  }

  @Test
  public void testUpdateClientIds_clearClientIds() throws Exception {
    AllocationToken token =
        persistResource(
            builderWithPromo().setAllowedClientIds(ImmutableSet.of("toRemove")).build());
    runCommandForced("--prefix", "token", "--allowed_client_ids", "");
    assertThat(reloadResource(token).getAllowedClientIds()).isEmpty();
  }

  @Test
  public void testUpdateDiscountFraction() throws Exception {
    AllocationToken token = persistResource(builderWithPromo().setDiscountFraction(0.5).build());
    runCommandForced("--prefix", "token", "--discount_fraction", "0.15");
    assertThat(reloadResource(token).getDiscountFraction()).isEqualTo(0.15);
  }

  @Test
  public void testUpdateStatusTransitions() throws Exception {
    DateTime now = DateTime.now(UTC);
    AllocationToken token = persistResource(builderWithPromo().build());
    runCommandForced(
        "--prefix",
        "token",
        "--token_status_transitions",
        String.format(
            "\"%s=NOT_STARTED,%s=VALID,%s=CANCELLED\"", START_OF_TIME, now.minusDays(1), now));
    token = reloadResource(token);
    assertThat(token.getTokenStatusTransitions().toValueMap())
        .containsExactly(START_OF_TIME, NOT_STARTED, now.minusDays(1), VALID, now, CANCELLED);
  }

  @Test
  public void testUpdateStatusTransitions_badTransitions() {
    DateTime now = DateTime.now(UTC);
    persistResource(builderWithPromo().build());
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--prefix",
                    "token",
                    "--token_status_transitions",
                    String.format(
                        "\"%s=NOT_STARTED,%s=ENDED,%s=VALID\"",
                        START_OF_TIME, now.minusDays(1), now)));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("tokenStatusTransitions map cannot transition from NOT_STARTED to ENDED.");
  }

  @Test
  public void testUpdate_onlyWithPrefix() throws Exception {
    AllocationToken token =
        persistResource(builderWithPromo().setAllowedTlds(ImmutableSet.of("tld")).build());
    AllocationToken otherToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("otherToken")
                .setTokenType(SINGLE_USE)
                .setAllowedTlds(ImmutableSet.of("tld"))
                .build());
    runCommandForced("--prefix", "other", "--allowed_tlds", "");
    assertThat(reloadResource(token).getAllowedTlds()).containsExactly("tld");
    assertThat(reloadResource(otherToken).getAllowedTlds()).isEmpty();
  }

  @Test
  public void testUpdate_onlyTokensProvided() throws Exception {
    AllocationToken firstToken =
        persistResource(builderWithPromo().setAllowedTlds(ImmutableSet.of("tld")).build());
    AllocationToken secondToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("secondToken")
                .setTokenType(SINGLE_USE)
                .setAllowedTlds(ImmutableSet.of("tld"))
                .build());
    AllocationToken thirdToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("thirdToken")
                .setTokenType(SINGLE_USE)
                .setAllowedTlds(ImmutableSet.of("tld"))
                .build());
    runCommandForced("--tokens", "secondToken,thirdToken", "--allowed_tlds", "");
    assertThat(reloadResource(firstToken).getAllowedTlds()).containsExactly("tld");
    assertThat(reloadResource(secondToken).getAllowedTlds()).isEmpty();
    assertThat(reloadResource(thirdToken).getAllowedTlds()).isEmpty();
  }

  @Test
  public void testDoNothing() throws Exception {
    AllocationToken token =
        persistResource(
            builderWithPromo()
                .setAllowedClientIds(ImmutableSet.of("clientid"))
                .setAllowedTlds(ImmutableSet.of("tld"))
                .setDiscountFraction(0.15)
                .build());
    runCommandForced("--prefix", "token");
    AllocationToken reloaded = reloadResource(token);
    assertThat(reloaded.getAllowedTlds()).isEqualTo(token.getAllowedTlds());
    assertThat(reloaded.getAllowedClientIds()).isEqualTo(token.getAllowedClientIds());
    assertThat(reloaded.getDiscountFraction()).isEqualTo(token.getDiscountFraction());
  }

  @Test
  public void testFailure_bothTokensAndPrefix() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> runCommandForced("--prefix", "token", "--tokens", "token")))
        .hasMessageThat()
        .isEqualTo("Must provide one of --tokens or --prefix, not both / neither");
  }

  @Test
  public void testFailure_neitherTokensNorPrefix() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class, () -> runCommandForced("--allowed_tlds", "tld")))
        .hasMessageThat()
        .isEqualTo("Must provide one of --tokens or --prefix, not both / neither");
  }

  @Test
  public void testFailure_emptyPrefix() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommandForced("--prefix", ""));
    assertThat(thrown).hasMessageThat().isEqualTo("Provided prefix should not be blank");
  }

  private static AllocationToken.Builder builderWithPromo() {
    DateTime now = DateTime.now(UTC);
    return new AllocationToken.Builder()
        .setToken("token")
        .setTokenType(UNLIMITED_USE)
        .setTokenStatusTransitions(
            ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                .put(START_OF_TIME, NOT_STARTED)
                .put(now.minusDays(1), VALID)
                .put(now.plusDays(1), ENDED)
                .build());
  }
}
