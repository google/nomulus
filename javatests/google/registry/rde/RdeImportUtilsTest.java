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

package google.registry.rde;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.persistResource;

import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.Work;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactResource;
import google.registry.model.index.EppResourceIndex;
import google.registry.model.index.EppResourceIndexBucket;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link RdeImportUtils} */
@RunWith(JUnit4.class)
public class RdeImportUtilsTest {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .build();

  private RdeImportUtils rdeImportUtils;
  private FakeClock clock;

  @Before
  public void before() {
    clock = new FakeClock();
    clock.setTo(DateTime.now());
    rdeImportUtils = new RdeImportUtils(ofy(), clock);
  }

  /** Verifies import of a contact that has not been previously imported */
  @Test
  public void testImportNewContact() {
    ContactResource newContact = buildNewContact();
    assertThat(rdeImportUtils.importContact(newContact)).isTrue();
    assertEppResourceIndexEntityFor(newContact);
    assertForeignKeyIndexFor(newContact);

    // verify the new contact was saved
    ContactResource saved = getContact("TEST-123");
    assertThat(saved).isNotNull();
    assertThat(saved.getContactId()).isEqualTo(newContact.getContactId());
    assertThat(saved.getEmailAddress()).isEqualTo(newContact.getEmailAddress());
    assertThat(saved.getLastEppUpdateTime()).isEqualTo(newContact.getLastEppUpdateTime());
  }

  /** Verifies that a contact will not be imported more than once */
  @Test
  public void testImportExistingContact() {
    ContactResource newContact = buildNewContact();
    persistResource(newContact);
    ContactResource updatedContact = newContact.asBuilder()
        .setLastEppUpdateTime(newContact.getLastEppUpdateTime().plusSeconds(1))
        .build();
    assertThat(rdeImportUtils.importContact(updatedContact)).isFalse();

    // verify the updated contact was saved
    ContactResource saved = getContact("TEST-123");
    assertThat(saved).isNotNull();
    assertThat(saved.getContactId()).isEqualTo(newContact.getContactId());
    assertThat(saved.getEmailAddress()).isEqualTo(newContact.getEmailAddress());
    assertThat(saved.getLastEppUpdateTime()).isEqualTo(newContact.getLastEppUpdateTime());
  }

  private static ContactResource buildNewContact() {
    return new ContactResource.Builder()
        .setContactId("sh8013")
        .setEmailAddress("jdoe@example.com")
        .setLastEppUpdateTime(DateTime.parse("2010-10-10T00:00:00.000Z"))
        .setRepoId("TEST-123")
        .build();
  }

  private static ContactResource getContact(String repoId) {
    final Key<ContactResource> key = Key.create(ContactResource.class, repoId);
    return ofy().transact(new Work<ContactResource>() {

      @Override
      public ContactResource run() {
        return ofy().load().key(key).now();
      }});
  }

  /**
   * Confirms that a ForeignKeyIndex exists in the datastore for a given resource.
   */
  private static <T extends EppResource> void assertForeignKeyIndexFor(final T resource) {
    assertThat(ForeignKeyIndex.load(resource.getClass(), resource.getForeignKey(), DateTime.now()))
        .isNotNull();
  }

  /**
   * Confirms that an EppResourceIndex entity exists in datastore for a given resource.
   */
  private static <T extends EppResource> void assertEppResourceIndexEntityFor(final T resource) {
    ImmutableList<EppResourceIndex> indices = FluentIterable
        .from(ofy().load()
            .type(EppResourceIndex.class)
            .filter("kind", Key.getKind(resource.getClass())))
        .filter(new Predicate<EppResourceIndex>() {
            @Override
            public boolean apply(EppResourceIndex index) {
              return index.getReference().get().equals(resource);
            }})
        .toList();
    assertThat(indices).hasSize(1);
    assertThat(indices.get(0).getBucket())
        .isEqualTo(EppResourceIndexBucket.getBucketKey(Key.create(resource)));
  }
}
