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

package google.registry.batch;

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
import google.registry.request.Response;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectExtension;
import google.registry.testing.TestSqlOnly;
import org.hibernate.ScrollableResults;
import org.joda.time.DateTime;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link WipeOutContactHistoryPiiAction}. */
@DualDatabaseTest
class WipeOutContactHistoryPiiActionTest {
  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().withTaskQueue().build();

  @RegisterExtension public final InjectExtension inject = new InjectExtension();

  private final FakeClock clock = new FakeClock(DateTime.parse("2021-08-26T20:21:22Z"));
  private final int minMonthsBeforeWipedOut = 18;
  private final int batchSize = 100;
  private Response response;
  private WipeOutContactHistoryPiiAction action =
      new WipeOutContactHistoryPiiAction(clock, minMonthsBeforeWipedOut, batchSize, response);
  private ContactResource defaultContactResource =
      new ContactResource.Builder()
          .setContactId("sh8013")
          .setRepoId("2FF-ROID")
          .setStatusValues(ImmutableSet.of(StatusValue.CLIENT_DELETE_PROHIBITED))
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
          .setPersistedCurrentSponsorClientId("TheRegistrar")
          .setCreationClientId("NewRegistrar")
          .setLastEppUpdateClientId("NewRegistrar")
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

  @TestSqlOnly
  void getAllHistoryEntries_returnsEmptyList() {
    // -1 means there is no current row
    jpaTm()
        .transact(
            () ->
                assertThat(
                        action
                            .getAllHistoryEntriesOlderThan(minMonthsBeforeWipedOut)
                            .getRowNumber())
                    .isEqualTo(-1));
  }

  @TestSqlOnly
  void getAllHistoryEntries_persistOnlyEntitiesThatShouldBeWiped() {
    ImmutableList<ContactHistory> expectedToBeWipedOut =
        persistLotsOfContactHistoryEntities(
            20, minMonthsBeforeWipedOut + 1, 0, defaultContactResource);

    jpaTm()
        .transact(
            () -> {
              ScrollableResults data =
                  action.getAllHistoryEntriesOlderThan(minMonthsBeforeWipedOut);
              int actualNumOfOldEntities = 0;
              while (data.next()) {
                ContactHistory contactHistory = (ContactHistory) data.get(0);
                assertThat(expectedToBeWipedOut.contains(contactHistory)).isTrue();
                actualNumOfOldEntities++;
              }
              assertThat(actualNumOfOldEntities).isEqualTo(expectedToBeWipedOut.size());
            });
  }

  @TestSqlOnly
  void getAllHistoryEntries_persistOnlyEntitiesThatShouldNotBeWiped() {
    ImmutableList<ContactHistory> expectedNotToBeWiped =
        persistLotsOfContactHistoryEntities(20, minMonthsBeforeWipedOut, 0, defaultContactResource);
    jpaTm()
        .transact(
            () -> {
              ScrollableResults data =
                  action.getAllHistoryEntriesOlderThan(minMonthsBeforeWipedOut);
              int actualNumOfOldEntities = 0;
              while (data.next()) {
                ContactHistory contactHistory = (ContactHistory) data.get(0);
                assertThat(expectedNotToBeWiped.contains(contactHistory)).isFalse();
                actualNumOfOldEntities++;
              }
              assertThat(actualNumOfOldEntities).isEqualTo(0);
            });
  }

  @TestSqlOnly
  void getAllHistoryEntries_persistLotsOfEntities_returnsOnlyPartOfTheEntities() {
    ImmutableList<ContactHistory> expectedToBeWipedOut =
        persistLotsOfContactHistoryEntities(40, 20, 0, defaultContactResource);
    ImmutableList<ContactHistory> expectedNotToBeWiped =
        persistLotsOfContactHistoryEntities(15, 17, 5, defaultContactResource);
    jpaTm()
        .transact(
            () -> {
              ScrollableResults data =
                  action.getAllHistoryEntriesOlderThan(minMonthsBeforeWipedOut);
              int actualNumOfOldEntities = 0;
              while (data.next()) {
                ContactHistory contactHistory = (ContactHistory) data.get(0);
                assertThat(expectedToBeWipedOut.contains(contactHistory)).isTrue();
                assertThat(expectedNotToBeWiped.contains(contactHistory)).isFalse();
                actualNumOfOldEntities++;
              }
              assertThat(actualNumOfOldEntities).isEqualTo(expectedToBeWipedOut.size());
            });
  }

  @TestSqlOnly
  void processData_testSmallDataSet_Success() {
    ImmutableList<ContactHistory> expectedToBeWipedOut =
        persistLotsOfContactHistoryEntities(450, 20, 0, defaultContactResource);
    jpaTm()
        .transact(
            () -> {
              ScrollableResults data =
                  action.getAllHistoryEntriesOlderThan(minMonthsBeforeWipedOut);
              assertThat(action.processData(data)).isEqualTo(expectedToBeWipedOut.size());
              ;
            });
  }

  @TestSqlOnly
  void processData_testLargeDataSet_Success() {
    ImmutableList<ContactHistory> expectedToBeWipedOut =
        persistLotsOfContactHistoryEntities(10000, 25, 3, defaultContactResource);
    jpaTm()
        .transact(
            () -> {
              ScrollableResults data =
                  action.getAllHistoryEntriesOlderThan(minMonthsBeforeWipedOut);
              assertThat(action.processData(data)).isEqualTo(expectedToBeWipedOut.size());
              ;
            });
  }

  /**
   * persists a number of hitory entries with the same set up, same contact info but different
   * modification time
   */
  ImmutableList<ContactHistory> persistLotsOfContactHistoryEntities(
      int numOfEntities, int minusMonths, int minusDays, ContactResource contact) {
    ImmutableList.Builder<ContactHistory> expectedEntitesBuilder = new ImmutableList.Builder<>();
    for (int i = 0; i < numOfEntities; i++) {
      expectedEntitesBuilder.add(
          persistResource(
              new ContactHistory()
                  .asBuilder()
                  .setClientId("NewRegistrar")
                  .setModificationTime(clock.nowUtc().minusMonths(minusMonths).minusDays(minusDays))
                  .setType(ContactHistory.Type.CONTACT_DELETE)
                  .setContact(persistResource(contact))
                  .build()));
    }
    return expectedEntitesBuilder.build();
  }

  @TestSqlOnly
  void wipeOutContactHistoryPii_success() {
    ImmutableList.Builder<ContactHistory> data = new ImmutableList.Builder<>();
    ContactHistory contactHistory =
        persistResource(
            new ContactHistory()
                .asBuilder()
                .setClientId("NewRegistrar")
                .setModificationTime(clock.nowUtc().minusMonths(minMonthsBeforeWipedOut + 1))
                .setContact(persistResource(defaultContactResource))
                .setType(ContactHistory.Type.CONTACT_DELETE)
                .build());

    jpaTm().transact(() -> action.wipeOutContactHistoryPii(data.add(contactHistory).build()));

    jpaTm()
        .transact(
            () -> {
              ContactHistory contactHistoryFromDb = jpaTm().loadByKey(contactHistory.createVKey());
              assertThat(contactHistoryFromDb.getParentVKey())
                  .isEqualTo(contactHistory.getParentVKey());
              ContactBase contactResourceFromDb = contactHistoryFromDb.getContactBase().get();
              assertThat(contactResourceFromDb.getEmailAddress()).isNull();
              assertThat(contactResourceFromDb.getFaxNumber()).isNull();
              assertThat(contactResourceFromDb.getInternationalizedPostalInfo()).isNull();
              assertThat(contactResourceFromDb.getLocalizedPostalInfo()).isNull();
              assertThat(contactResourceFromDb.getVoiceNumber()).isNull();
            });
  }
}
