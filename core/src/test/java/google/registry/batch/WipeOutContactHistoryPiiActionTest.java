// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.batch;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.DatabaseHelper.persistResource;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.contact.ContactAddress;
import google.registry.model.contact.ContactAuthInfo;
import google.registry.model.contact.ContactBase;
import google.registry.model.contact.ContactHistory;
import google.registry.model.contact.ContactPhoneNumber;
import google.registry.model.contact.ContactResource;
import google.registry.model.contact.Disclose;
import google.registry.model.contact.PostalInfo;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.PresenceMarker;
import google.registry.model.eppcommon.StatusValue;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.InjectExtension;
import google.registry.testing.TestSqlOnly;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link WipeOutContactHistoryPiiAction}. */
@DualDatabaseTest
class WipeOutContactHistoryPiiActionTest {
  private static final int MIN_MONTHS_BEFORE_WIPE_OUT = 18;
  private static final int BATCH_SIZE = 100;
  private static final int NUM_OF_ENTITIES_PER_TRANSACTION = 50;
  private static final ContactResource defaultContactResource =
      new ContactResource.Builder()
          .setContactId("sh8013")
          .setRepoId("2FF-ROID")
          .setStatusValues(ImmutableSet.of(StatusValue.CLIENT_DELETE_PROHIBITED))
          .setLocalizedPostalInfo(
              new PostalInfo.Builder()
                  .setType(PostalInfo.Type.LOCALIZED)
                  .setAddress(
                      new ContactAddress.Builder()
                          .setStreet(ImmutableList.of("123 Grand Ave"))
                          .build())
                  .build())
          .setInternationalizedPostalInfo(
              new PostalInfo.Builder()
                  .setType(PostalInfo.Type.INTERNATIONALIZED)
                  .setName("John Doe")
                  .setOrg("Example Inc.")
                  .setAddress(
                      new ContactAddress.Builder()
                          .setStreet(ImmutableList.of("123 Example Dr.", "Suite 100"))
                          .setCity("Dulles")
                          .setState("VA")
                          .setZip("20166-6503")
                          .setCountryCode("US")
                          .build())
                  .build())
          .setVoiceNumber(
              new ContactPhoneNumber.Builder()
                  .setPhoneNumber("+1.7035555555")
                  .setExtension("1234")
                  .build())
          .setFaxNumber(new ContactPhoneNumber.Builder().setPhoneNumber("+1.7035555556").build())
          .setEmailAddress("jdoe@example.com")
          .setPersistedCurrentSponsorRegistrarId("TheRegistrar")
          .setCreationRegistrarId("NewRegistrar")
          .setLastEppUpdateRegistrarId("NewRegistrar")
          .setCreationTimeForTest(DateTime.parse("1999-04-03T22:00:00.0Z"))
          .setLastEppUpdateTime(DateTime.parse("1999-12-03T09:00:00.0Z"))
          .setLastTransferTime(DateTime.parse("2000-04-08T09:00:00.0Z"))
          .setAuthInfo(ContactAuthInfo.create(PasswordAuth.create("2fooBAR")))
          .setDisclose(
              new Disclose.Builder()
                  .setFlag(true)
                  .setVoice(new PresenceMarker())
                  .setEmail(new PresenceMarker())
                  .build())
          .build();

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().withTaskQueue().build();

  @RegisterExtension public final InjectExtension inject = new InjectExtension();
  private final FakeClock clock = new FakeClock(DateTime.parse("2021-08-26T20:21:22Z"));

  private FakeResponse response;
  private WipeOutContactHistoryPiiAction action;

  @BeforeEach
  void beforeEach() {
    response = new FakeResponse();
    action =
        new WipeOutContactHistoryPiiAction(
            clock,
            MIN_MONTHS_BEFORE_WIPE_OUT,
            BATCH_SIZE,
            NUM_OF_ENTITIES_PER_TRANSACTION,
            response);
  }

  @TestSqlOnly
  void getAllHistoryEntitiesOlderThan_returnsEmptyList() {
    assertThat(
            jpaTm()
                .transact(
                    () ->
                        action
                            .getAllHistoryEntitiesOlderThan(MIN_MONTHS_BEFORE_WIPE_OUT)
                            .collect(toImmutableList())))
        .isEmpty();
  }

  @TestSqlOnly
  void getAllHistoryEntitiesOlderThan_returnsAllPersistedEntities() {
    ImmutableList<ContactHistory> expectedToBeWipedOut =
        persistLotsOfContactHistoryEntities(
            20, MIN_MONTHS_BEFORE_WIPE_OUT + 1, 0, defaultContactResource);
    assertThat(
            jpaTm()
                .transact(
                    () ->
                        action
                            .getAllHistoryEntitiesOlderThan(MIN_MONTHS_BEFORE_WIPE_OUT)
                            .collect(toImmutableList())))
        .isEqualTo(expectedToBeWipedOut);
  }

  @TestSqlOnly
  void getAllHistoryEntitiesOlderThan_returnsNoEntity() {
    ImmutableList<ContactHistory> expectedNotToBeWiped =
        persistLotsOfContactHistoryEntities(
            20, MIN_MONTHS_BEFORE_WIPE_OUT, 0, defaultContactResource);

    ImmutableList<ContactHistory> returnedEntities =
        jpaTm()
            .transact(
                () ->
                    action
                        .getAllHistoryEntitiesOlderThan(MIN_MONTHS_BEFORE_WIPE_OUT)
                        .collect(toImmutableList()));

    assertThat(returnedEntities).isEmpty();
  }

  @TestSqlOnly
  void getAllHistoryEntitiesOlderThan_returnsOnlyPartOfThePersistedEntities() {
    ImmutableList<ContactHistory> expectedToBeWipedOut =
        persistLotsOfContactHistoryEntities(40, 20, 0, defaultContactResource);
    ImmutableList<ContactHistory> expectedNotToBeWiped =
        persistLotsOfContactHistoryEntities(15, 17, 5, defaultContactResource);

    ImmutableList<ContactHistory> returnedEntities =
        jpaTm()
            .transact(
                () ->
                    action
                        .getAllHistoryEntitiesOlderThan(MIN_MONTHS_BEFORE_WIPE_OUT)
                        .collect(toImmutableList()));

    assertThat(returnedEntities).isEqualTo(expectedToBeWipedOut);
    for (ContactHistory returnedEntity : returnedEntities) {
      assertThat(expectedNotToBeWiped).doesNotContain(returnedEntity);
    }
  }

  @TestSqlOnly
  void run_withOneBatch_success() {
    int expectedMonthsFromNow = 20;
    ImmutableList<ContactHistory> expectedToBeWipedOut =
        persistLotsOfContactHistoryEntities(20, expectedMonthsFromNow, 0, defaultContactResource);

    // The query should return a stream of all persisted entities.
    assertThat(
            jpaTm()
                .transact(
                    () ->
                        action.getAllHistoryEntitiesOlderThan(MIN_MONTHS_BEFORE_WIPE_OUT).count()))
        .isEqualTo(expectedToBeWipedOut.size());

    // All pii fields of the contact history entities are not null.
    for (ContactHistory originalEntity : expectedToBeWipedOut) {
      assertThat(checksAllPiiFields(originalEntity.getContactBase().get(), false)).isTrue();
    }

    action.run();

    // The query should return an empty stream after the wipe out action.
    assertThat(
            jpaTm()
                .transact(
                    () ->
                        action.getAllHistoryEntitiesOlderThan(MIN_MONTHS_BEFORE_WIPE_OUT).count()))
        .isEqualTo(0);

    for (ContactHistory originalEntity : expectedToBeWipedOut) {
      ContactHistory wipedEntity = jpaTm().transact(() -> jpaTm().loadByEntity(originalEntity));
      assertThat(wipedEntity.getModificationTime())
          .isEqualTo(clock.nowUtc().minusMonths(expectedMonthsFromNow));
      assertThat(checksAllPiiFields(wipedEntity.getContactBase().get(), true)).isTrue();
    }
  }

  @TestSqlOnly
  void run_withMultipleBatches_success() {
    int expectedMonthsFromNow = 20;
    ImmutableList<ContactHistory> expectedToBeWipedOut =
        persistLotsOfContactHistoryEntities(2000, expectedMonthsFromNow, 0, defaultContactResource);

    // The query should return a subset of all persisted data.
    assertThat(
            jpaTm()
                .transact(
                    () ->
                        action.getAllHistoryEntitiesOlderThan(MIN_MONTHS_BEFORE_WIPE_OUT).count()))
        .isEqualTo(NUM_OF_ENTITIES_PER_TRANSACTION);

    action.run();

    // The query should return an empty stream after the wipe out action.
    assertThat(
            jpaTm()
                .transact(
                    () ->
                        action.getAllHistoryEntitiesOlderThan(MIN_MONTHS_BEFORE_WIPE_OUT).count()))
        .isEqualTo(0);

    for (ContactHistory originalEntity : expectedToBeWipedOut) {
      ContactHistory wipedEntity = jpaTm().transact(() -> jpaTm().loadByEntity(originalEntity));
      assertThat(wipedEntity.getModificationTime())
          .isEqualTo(clock.nowUtc().minusMonths(expectedMonthsFromNow));
      assertThat(checksAllPiiFields(wipedEntity.getContactBase().get(), true)).isTrue();
    }
  }

  @TestSqlOnly
  void wipeOutContactHistoryData_wipesOutMultipleEntities() {
    int expectedMonthsFromNow = 25;
    ImmutableList<ContactHistory> expectedToBeWipedOut =
        persistLotsOfContactHistoryEntities(20, expectedMonthsFromNow, 0, defaultContactResource);

    // assert that each of the contact history entity in the db contains data in all pii fields.
    expectedToBeWipedOut.forEach(
        originalEntity -> {
          ContactHistory toBeWiped = jpaTm().transact(() -> jpaTm().loadByEntity(originalEntity));
          assertThat(toBeWiped.getModificationTime())
              .isEqualTo(clock.nowUtc().minusMonths(expectedMonthsFromNow));
          assertThat(checksAllPiiFields(toBeWiped.getContactBase().get(), false)).isTrue();
        });

    jpaTm()
        .transact(
            () -> {
              action.wipeOutContactHistoryData(
                  action.getAllHistoryEntitiesOlderThan(MIN_MONTHS_BEFORE_WIPE_OUT));
            });

    // verify that all pii fields of the old contact history entities are wiped out.
    // assert that each of the contact history entity in the db contains data in all pii fields.
    expectedToBeWipedOut.forEach(
        originalEntity -> {
          ContactHistory wipedEntity = jpaTm().transact(() -> jpaTm().loadByEntity(originalEntity));
          assertThat(wipedEntity.getModificationTime())
              .isEqualTo(clock.nowUtc().minusMonths(expectedMonthsFromNow));
          assertThat(checksAllPiiFields(wipedEntity.getContactBase().get(), true)).isTrue();
        });
  }

  /** persists a number of ContactHistory entities for load and query testing. */
  ImmutableList<ContactHistory> persistLotsOfContactHistoryEntities(
      int numOfEntities, int minusMonths, int minusDays, ContactResource contact) {
    ImmutableList.Builder<ContactHistory> expectedEntitesBuilder = new ImmutableList.Builder<>();
    for (int i = 0; i < numOfEntities; i++) {
      expectedEntitesBuilder.add(
          persistResource(
              new ContactHistory()
                  .asBuilder()
                  .setRegistrarId("NewRegistrar")
                  .setModificationTime(clock.nowUtc().minusMonths(minusMonths).minusDays(minusDays))
                  .setType(ContactHistory.Type.CONTACT_DELETE)
                  .setContact(persistResource(contact))
                  .build()));
    }
    return expectedEntitesBuilder.build();
  }

  /** verifies all pii fields are either null or not null. */
  Boolean checksAllPiiFields(ContactBase contactBase, Boolean allNull) {
    if (allNull) {
      return contactBase.getEmailAddress() == null
          && contactBase.getFaxNumber() == null
          && contactBase.getInternationalizedPostalInfo() == null
          && contactBase.getLocalizedPostalInfo() == null
          && contactBase.getVoiceNumber() == null;
    } else {
      return contactBase.getEmailAddress() != null
          && contactBase.getFaxNumber() != null
          && contactBase.getInternationalizedPostalInfo() != null
          && contactBase.getLocalizedPostalInfo() != null
          && contactBase.getVoiceNumber() != null;
    }
  }
}
