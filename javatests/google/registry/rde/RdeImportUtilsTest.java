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
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistResource;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteSource;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.VoidWork;
import com.googlecode.objectify.Work;
import google.registry.gcs.GcsUtils;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactResource;
import google.registry.model.index.EppResourceIndex;
import google.registry.model.index.EppResourceIndexBucket;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.model.registrar.Registrar;
import google.registry.model.registry.Registry.TldState;
import google.registry.testing.AppEngineRule;
import google.registry.testing.ExceptionRule;
import org.joda.time.DateTime;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.io.IOException;
import java.io.InputStream;

/** Unit tests for {@link RdeImportUtils} */
@RunWith(MockitoJUnitRunner.class)
public class RdeImportUtilsTest {

  private static final ByteSource DEPOSIT_XML = RdeTestData.get("deposit_full.xml");
  private static final ByteSource DEPOSIT_BADTLD_XML = RdeTestData.get("deposit_full_badtld.xml");
  private static final ByteSource DEPOSIT_GETLD_XML = RdeTestData.get("deposit_full_getld.xml");
  private static final ByteSource DEPOSIT_BADREGISTRAR_XML =
      RdeTestData.get("deposit_full_badregistrar.xml");

  private InputStream xmlInput;

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .build();

  @Rule
  public final ExceptionRule thrown = new ExceptionRule();

  @Mock
  private GcsUtils gcsUtils;

  private RdeImportUtils rdeImportUtils;

  @Before
  public void before() {
    rdeImportUtils = new RdeImportUtils("import-bucket", ofy(), gcsUtils);
    createTld("test", TldState.PREDELEGATION);
    createTld("getld", TldState.GENERAL_AVAILABILITY);
    createRegistrar("RegistrarX");
  }

  @After
  public void after() throws IOException {
    if (xmlInput != null) {
      xmlInput.close();
    }
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

  /** Verifies that no errors are thrown when a valid escrow file is validated */
  @Test
  public void testValidateEscrowFile_valid() throws Exception {
    xmlInput = DEPOSIT_XML.openBufferedStream();
    when(gcsUtils.openInputStream(any(GcsFilename.class))).thenReturn(xmlInput);
    rdeImportUtils.validateEscrowFileForImport("valid-deposit-file.xml");
    verify(gcsUtils).openInputStream(new GcsFilename("import-bucket", "valid-deposit-file.xml"));
  }

  /** Verifies thrown error when tld in escrow file is not in the registry */
  @Test
  public void testValidateEscrowFile_tldNotFound() throws Exception {
    thrown.expect(IllegalArgumentException.class, "Tld 'badtld' not found in the registry");
    xmlInput = DEPOSIT_BADTLD_XML.openBufferedStream();
    when(gcsUtils.openInputStream(any(GcsFilename.class))).thenReturn(xmlInput);
    rdeImportUtils.validateEscrowFileForImport("invalid-deposit-badtld.xml");
  }

  /** Verifies thrown errer when tld in escrow file is not in PREDELEGATION state */
  @Test
  public void testValidateEscrowFile_tldWrongState() throws Exception {
    thrown.expect(IllegalArgumentException.class,
        "Tld 'getld' is in state GENERAL_AVAILABILITY and cannot be imported");
    xmlInput = DEPOSIT_GETLD_XML.openBufferedStream();
    when(gcsUtils.openInputStream(any(GcsFilename.class))).thenReturn(xmlInput);
    rdeImportUtils.validateEscrowFileForImport("invalid-deposit-getld.xml");
  }

  /** Verifies thrown error when registrar in escrow file is not in the registry */
  @Test
  public void testValidateEscrowFile_badRegistrar() throws Exception {
    thrown.expect(IllegalArgumentException.class,
        "Registrar 'RegistrarY' not found in the registry");
    xmlInput = DEPOSIT_BADREGISTRAR_XML.openBufferedStream();
    when(gcsUtils.openInputStream(any(GcsFilename.class))).thenReturn(xmlInput);
    rdeImportUtils.validateEscrowFileForImport("invalid-deposit-badregistrar.xml");
  }

  /** Gets the contact with the specified ROID */
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

  /** Creates a stripped-down {@link Registrar} with the specified clientId */
  private static void createRegistrar(String clientId) {
    ofy().transact(new VoidWork() {

      @Override
      public void vrun() {
        ofy().save().entity(
            new Registrar.Builder()
              .setClientIdentifier(clientId)
              .setType(Registrar.Type.REAL)
              .setIanaIdentifier(1L)
              .build()
        );
      }
    });
  }
}
