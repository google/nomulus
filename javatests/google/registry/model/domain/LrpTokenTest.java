// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

package google.registry.model.domain;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistActiveDomainApplication;
import static google.registry.testing.DatastoreHelper.persistResource;

import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.model.EntityTestCase;
import google.registry.model.reporting.HistoryEntry;
import google.registry.testing.ExceptionRule;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** Unit tests for {@link LrpToken}. */
public class LrpTokenTest extends EntityTestCase {

  LrpToken unredeemedToken;
  LrpToken redeemedToken;

  @Rule
  public final ExceptionRule thrown = new ExceptionRule();

  @Before
  public void setUp() throws Exception {
    createTld("tld");
    DomainApplication lrpApplication = persistActiveDomainApplication("domain.tld");
    HistoryEntry applicationCreateHistoryEntry = persistResource(new HistoryEntry.Builder()
        .setParent(lrpApplication)
        .setType(HistoryEntry.Type.DOMAIN_APPLICATION_CREATE)
        .build());
    unredeemedToken = persistResource(
        new LrpToken.Builder()
            .setAssignee("1:1020304")
            .setToken("a0b1c2d3e4f5g6")
            .setValidTlds(ImmutableSet.of("tld"))
            .build());
    redeemedToken = persistResource(
        new LrpToken.Builder()
            .setAssignee("2:org.testdomain")
            .setToken("h0i1j2k3l4m")
            .setRedemptionHistoryEntry(
                Key.create(applicationCreateHistoryEntry))
            .setValidTlds(ImmutableSet.of("tld"))
            .build());
  }

  @Test
  public void testPersistence() throws Exception {
    assertThat(ofy().load().entity(redeemedToken).now()).isEqualTo(redeemedToken);
  }

  @Test
  public void testSuccess_loadByToken() throws Exception {
    assertThat(ofy().load().key(Key.create(LrpToken.class, "a0b1c2d3e4f5g6")).now())
        .isEqualTo(unredeemedToken);
  }

  @Test
  public void testSuccess_loadByAssignee() throws Exception {
    assertThat(ofy().load().type(LrpToken.class).filter("assignee", "1:1020304").first().now())
        .isEqualTo(unredeemedToken);
  }
  @Test
  public void testSuccess_isRedeemed() throws Exception {
    assertThat(redeemedToken.isRedeemed()).isTrue();
    assertThat(unredeemedToken.isRedeemed()).isFalse();
  }

  @Test
  public void testIndexing() throws Exception {
    verifyIndexing(redeemedToken, "assignee", "token");
  }
}
