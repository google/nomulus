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

import static google.registry.model.domain.token.AllocationToken.TokenType.SINGLE_USE;
import static google.registry.testing.DatabaseHelper.createHistoryEntryForEppResource;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.createTlds;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DatabaseHelper.persistResources;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.domain.Domain;
import google.registry.model.domain.fee.FeeQueryCommandExtensionItem;
import google.registry.model.domain.token.AllocationToken;
import google.registry.util.DateTimeUtils;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link GetAllocationTokenCommand}. */
class GetAllocationTokenCommandTest extends CommandTestCase<GetAllocationTokenCommand> {

  @Test
  void testSuccess_oneToken() throws Exception {
    createTlds("bar");
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("foo")
                .setTokenType(SINGLE_USE)
                .setAllowedEppActions(
                    ImmutableSet.of(FeeQueryCommandExtensionItem.CommandName.CREATE))
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setAllowedTlds(ImmutableSet.of("bar"))
                .setDiscountFraction(0.5)
                .setDiscountYears(2)
                .setTokenStatusTransitions(
                    ImmutableSortedMap.of(
                        DateTimeUtils.START_OF_TIME,
                        AllocationToken.TokenStatus.NOT_STARTED,
                        fakeClock.nowUtc(),
                        AllocationToken.TokenStatus.VALID))
                .setDomainName("foo.bar")
                .build());
    runCommand("foo");
    assertStdoutIs(
"""
AllocationToken: {
    allowedClientIds=[TheRegistrar]
    allowedEppActions=[CREATE]
    allowedTlds=[bar]
    creationTime=CreateAutoTimestamp: {
        creationTime=2022-09-01T00:00:00.000Z
    }
    discountFraction=0.5
    discountPremiums=false
    discountPrice=null
    discountYears=2
    domainName=foo.bar
    redemptionHistoryId=null
    registrationBehavior=DEFAULT
    renewalPrice=null
    renewalPriceBehavior=DEFAULT
    token=foo
    tokenStatusTransitions={1970-01-01T00:00:00.000Z=NOT_STARTED, 2022-09-01T00:00:00.000Z=VALID}
    tokenType=SINGLE_USE
    updateTimestamp=UpdateAutoTimestamp: {
        lastUpdateTime=2022-09-01T00:00:00.000Z
    }
}
Token foo was not redeemed.

""");
  }

  @Test
  void testSuccess_multipleTokens() throws Exception {
    createTlds("baz");
    ImmutableList<AllocationToken> tokens =
        persistResources(
            ImmutableList.of(
                new AllocationToken.Builder()
                    .setToken("fee")
                    .setTokenType(SINGLE_USE)
                    .setCreationTimeForTest(DateTime.parse("2015-04-07T22:19:17.044Z"))
                    .build(),
                new AllocationToken.Builder()
                    .setToken("fii")
                    .setTokenType(SINGLE_USE)
                    .setDomainName("bar.baz")
                    .build()));
    runCommand("fee", "fii");
    assertInStdout(
        tokens.get(0).toString(),
        "Token fee was not redeemed.",
        tokens.get(1).toString(),
        "Token fii was not redeemed.");
  }

  @Test
  void testSuccess_redeemedToken() throws Exception {
    createTld("tld");
    Domain domain = persistActiveDomain("fqqdn.tld", DateTime.parse("2016-04-07T22:19:17.044Z"));
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("foo")
                .setTokenType(SINGLE_USE)
                .setDomainName("fqqdn.tld")
                .setRedemptionHistoryId(
                    createHistoryEntryForEppResource(domain).getHistoryEntryId())
                .build());
    runCommand("foo");
    assertInStdout(
        token.toString(),
        "Token foo was redeemed to create domain fqqdn.tld at 2016-04-07T22:19:17.044Z.");
  }

  @Test
  void testSuccess_oneTokenDoesNotExist() throws Exception {
    createTlds("bar");
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("foo")
                .setTokenType(SINGLE_USE)
                .setDomainName("foo.bar")
                .build());
    runCommand("foo", "bar");
    assertInStdout(
        token.toString(), "Token foo was not redeemed.", "ERROR: Token bar does not exist.");
  }

  @Test
  void testFailure_noAllocationTokensSpecified() {
    assertThrows(ParameterException.class, this::runCommand);
  }
}
