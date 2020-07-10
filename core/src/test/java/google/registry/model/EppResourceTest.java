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

package google.registry.model;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistResource;

import com.google.common.collect.ImmutableList;
import google.registry.model.contact.ContactResource;
import google.registry.model.host.HostResource;
import google.registry.testing.TestCacheRule;
import org.joda.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link EppResource}. */
public class EppResourceTest extends EntityTestCase {

  @RegisterExtension
  public final TestCacheRule testCacheRule =
      new TestCacheRule.Builder().withEppResourceCache(Duration.standardDays(1)).build();

  @Test
  void test_loadCached_ignoresContactChange() {
    ContactResource originalContact = persistActiveContact("contact123");
    assertThat(EppResource.loadCached(ImmutableList.of(originalContact.createVKey())))
        .containsExactly(originalContact.createVKey(), originalContact);
    ContactResource modifiedContact =
        persistResource(originalContact.asBuilder().setEmailAddress("different@fake.lol").build());
    assertThat(EppResource.loadCached(ImmutableList.of(originalContact.createVKey())))
        .containsExactly(originalContact.createVKey(), originalContact);
    assertThat(loadByForeignKey(ContactResource.class, "contact123", fakeClock.nowUtc()))
        .hasValue(modifiedContact);
  }

  @Test
  void test_loadCached_ignoresHostChange() {
    HostResource originalHost = persistActiveHost("ns1.example.com");
    assertThat(EppResource.loadCached(ImmutableList.of(originalHost.createVKey())))
        .containsExactly(originalHost.createVKey(), originalHost);
    HostResource modifiedHost =
        persistResource(
            originalHost.asBuilder().setLastTransferTime(fakeClock.nowUtc().minusDays(60)).build());
    assertThat(EppResource.loadCached(ImmutableList.of(originalHost.createVKey())))
        .containsExactly(originalHost.createVKey(), originalHost);
    assertThat(loadByForeignKey(HostResource.class, "ns1.example.com", fakeClock.nowUtc()))
        .hasValue(modifiedHost);
  }
}
