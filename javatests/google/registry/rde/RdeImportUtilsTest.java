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

  @Before
  public void before() {
    rdeImportUtils = new RdeImportUtils(ofy());
  }

  /** Verifies import of a contact that has not been previously imported */
  @Test
  public void testImportNewContact() {
    final ContactResource newContact = new ContactResource.Builder()
        .setContactId("sh8013")
        .setEmailAddress("jdoe@example.com")
        .setLastEppUpdateTime(DateTime.parse("2010-10-10T00:00:00.000Z"))
        .setRepoId("TEST-123")
        .build();
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

  /** Verifies import of an updated contact that was previously imported */
  @Test
  public void testImportUpdatedContact() {
    final ContactResource newContact = new ContactResource.Builder()
        .setContactId("sh8013")
        .setEmailAddress("jdoe@example.com")
        .setLastEppUpdateTime(DateTime.parse("2010-10-10T00:00:00.000Z"))
        .setRepoId("TEST-123")
        .build();
    persistResource(newContact);
    final ContactResource updatedContact = new ContactResource.Builder()
        .setContactId("sh8013")
        .setEmailAddress("jdoe@example.com")
        .setLastEppUpdateTime(DateTime.parse("2012-10-10T00:00:00.000Z"))
        .setRepoId("TEST-123")
        .build();
    assertThat(rdeImportUtils.importContact(updatedContact)).isTrue();
    assertEppResourceIndexEntityFor(updatedContact);
    assertForeignKeyIndexFor(updatedContact);

    // verify the updated contact was saved
    ContactResource saved = getContact("TEST-123");
    assertThat(saved).isNotNull();
    assertThat(saved.getContactId()).isEqualTo(updatedContact.getContactId());
    assertThat(saved.getEmailAddress()).isEqualTo(updatedContact.getEmailAddress());
    assertThat(saved.getLastEppUpdateTime()).isEqualTo(updatedContact.getLastEppUpdateTime());
  }

  /** Verifies that a contact won't be imported twice with the same epp updated timestamp */
  @Test
  public void testImportContactSameUpdatedTimestamp() {
    final ContactResource newContact = new ContactResource.Builder()
        .setContactId("sh8013")
        .setEmailAddress("jdoe@example.com")
        .setLastEppUpdateTime(DateTime.parse("2010-10-10T00:00:00.000Z"))
        .setRepoId("TEST-123")
        .build();
    persistResource(newContact);
    final ContactResource notUpdatedContact = new ContactResource.Builder()
        .setContactId("sh8013")
        .setEmailAddress("not.updated@example.com")
        .setLastEppUpdateTime(DateTime.parse("2010-10-10T00:00:00.000Z"))
        .setRepoId("TEST-123")
        .build();
    assertThat(rdeImportUtils.importContact(notUpdatedContact)).isFalse();

    // verify the (not) updated contact was not saved
    // fields of saved entity should all be the same as the newContact fields.
    ContactResource saved = getContact("TEST-123");
    assertThat(saved).isNotNull();
    assertThat(saved.getContactId()).isEqualTo(newContact.getContactId());
    assertThat(saved.getEmailAddress()).isEqualTo(newContact.getEmailAddress());
    assertThat(saved.getLastEppUpdateTime()).isEqualTo(newContact.getLastEppUpdateTime());
  }

  /** Verifies that a contact won't be imported if it has an older updated timestamp. */
  @Test
  public void testImportContactOlderUpdatedTimestamp() {
    final ContactResource newContact = new ContactResource.Builder()
        .setContactId("sh8013")
        .setEmailAddress("jdoe@example.com")
        .setLastEppUpdateTime(DateTime.parse("2010-10-10T00:00:00.000Z"))
        .setRepoId("TEST-123")
        .build();
    persistResource(newContact);
    final ContactResource notUpdatedContact = new ContactResource.Builder()
        .setContactId("sh8013")
        .setEmailAddress("old.contact@example.com")
        .setLastEppUpdateTime(DateTime.parse("2009-10-10T00:00:00.000Z"))
        .setRepoId("TEST-123")
        .build();
    assertThat(rdeImportUtils.importContact(notUpdatedContact)).isFalse();

    // verify the old contact was not saved
    // fields of saved entity should all be the same as the newContact fields.
    ContactResource saved = getContact("TEST-123");
    assertThat(saved).isNotNull();
    assertThat(saved.getContactId()).isEqualTo(newContact.getContactId());
    assertThat(saved.getEmailAddress()).isEqualTo(newContact.getEmailAddress());
    assertThat(saved.getLastEppUpdateTime()).isEqualTo(newContact.getLastEppUpdateTime());
  }

  private static ContactResource getContact(String repoId) {
    final Key<ContactResource> key = Key.create(null, ContactResource.class, repoId);
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
