// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.model.EppResourceUtils.loadDomainApplication;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.cloneAndSetAutoTimestamps;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.newDomainApplication;
import static google.registry.testing.DatastoreHelper.newHostResource;
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.FullFieldsTestEntityHelper.makeHistoryEntry;
import static google.registry.testing.TestDataHelper.loadFileWithSubstitutions;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.VoidWork;
import google.registry.model.EntityTestCase;
import google.registry.model.domain.launch.ApplicationStatus;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.domain.launch.LaunchPhase;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostResource;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.smd.EncodedSignedMark;
import google.registry.testing.ExceptionRule;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** Unit tests for {@link DomainApplication}. */
public class DomainApplicationTest extends EntityTestCase {

  @Rule
  public final ExceptionRule thrown = new ExceptionRule();

  DomainApplication domainApplication;

  @Before
  public void setUp() throws Exception {
    createTld("com");
    // Set up a new persisted domain application entity.
    domainApplication = cloneAndSetAutoTimestamps(
        new DomainApplication.Builder()
            .setFullyQualifiedDomainName("example.com")
            .setRepoId("1-COM")
            .setCreationClientId("a registrar")
            .setLastEppUpdateTime(clock.nowUtc())
            .setLastEppUpdateClientId("another registrar")
            .setStatusValues(ImmutableSet.of(
                StatusValue.CLIENT_DELETE_PROHIBITED,
                StatusValue.SERVER_DELETE_PROHIBITED,
                StatusValue.SERVER_TRANSFER_PROHIBITED,
                StatusValue.SERVER_UPDATE_PROHIBITED,
                StatusValue.SERVER_RENEW_PROHIBITED,
                StatusValue.SERVER_HOLD))
            .setRegistrant(Key.create(persistActiveContact("contact_id1")))
            .setContacts(ImmutableSet.of(DesignatedContact.create(
                DesignatedContact.Type.ADMIN,
                Key.create(persistActiveContact("contact_id2")))))
            .setNameservers(
                ImmutableSet.of(Key.create(persistActiveHost("ns1.example.com"))))
            .setCurrentSponsorClientId("a third registrar")
            .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("password")))
            .setDsData(ImmutableSet.of(DelegationSignerData.create(1, 2, 3, new byte[] {0, 1, 2})))
            .setLaunchNotice(
                LaunchNotice.create("tcnid", "validatorId", START_OF_TIME, START_OF_TIME))
            .setCreationTrid(Trid.create("client creation trid"))
            .setPhase(LaunchPhase.LANDRUSH)
            // TODO(b/32447342): set period
            .setEncodedSignedMarks(ImmutableList.of(EncodedSignedMark.create("base64", "abcdefg=")))
            .setApplicationStatus(ApplicationStatus.ALLOCATED)
            .setAuctionPrice(Money.of(USD, 11))
            .build());
    persistResource(domainApplication);
  }

  @Test
  public void testPersistence() throws Exception {
    assertThat(loadDomainApplication(domainApplication.getForeignKey(), clock.nowUtc()))
        .isEqualTo(domainApplication);
  }

  @Test
  public void testIndexing() throws Exception {
    domainApplication = persistResource(
        domainApplication.asBuilder().setPeriod(Period.create(5, Period.Unit.YEARS)).build());
    verifyIndexing(
        domainApplication,
        "allContacts.contact",
        "fullyQualifiedDomainName",
        "nsHosts",
        "deletionTime",
        "currentSponsorClientId",
        "tld");
  }

  private DomainApplication.Builder emptyBuilder() {
    return newDomainApplication("example.com").asBuilder();
  }

  @Test
  public void testEmptyStringsBecomeNull() {
    assertThat(emptyBuilder().setCurrentSponsorClientId(null).build()
        .getCurrentSponsorClientId()).isNull();
    assertThat(emptyBuilder().setCurrentSponsorClientId("").build()
        .getCurrentSponsorClientId()).isNull();
    assertThat(emptyBuilder().setCurrentSponsorClientId(" ").build()
        .getCurrentSponsorClientId()).isNotNull();
  }

  @Test
  public void testEmptySetsAndArraysBecomeNull() {
    assertThat(emptyBuilder().setNameservers(null).build().nsHosts).isNull();
    assertThat(emptyBuilder()
        .setNameservers(ImmutableSet.<Key<HostResource>>of())
        .build()
        .nsHosts)
            .isNull();
    assertThat(emptyBuilder()
        .setNameservers(ImmutableSet.of(Key.create(newHostResource("foo.example.tld"))))
        .build()
        .nsHosts)
            .isNotNull();
    // This behavior should also hold true for ImmutableObjects nested in collections.
    assertThat(emptyBuilder()
        .setDsData(ImmutableSet.of(DelegationSignerData.create(1, 1, 1, null)))
        .build()
        .getDsData().asList().get(0).getDigest())
            .isNull();
    assertThat(emptyBuilder()
        .setDsData(ImmutableSet.of(DelegationSignerData.create(1, 1, 1, new byte[]{})))
        .build()
        .getDsData().asList().get(0).getDigest())
            .isNull();
    assertThat(emptyBuilder()
        .setDsData(ImmutableSet.of(DelegationSignerData.create(1, 1, 1, new byte[]{1})))
        .build()
        .getDsData().asList().get(0).getDigest())
            .isNotNull();
  }

  @Test
  public void testToHydratedString_notCircular() {
    // If there are circular references, this will overflow the stack.
    domainApplication.toHydratedString();
  }

  // TODO(b/32447342): remove this once the period has been populated on all DomainApplications
  private void triggerTheOnLoadMethod() {
    ofy().transact(new VoidWork() {
      @Override
      public void vrun() {
        domainApplication = ofy().load().fromEntity(ofy().save().toEntity(domainApplication));
      }
    });
  }

  // TODO(b/32447342): remove this once the period has been populated on all DomainApplications
  @Test
  public void testPeriodIsNullByDefault() {
    triggerTheOnLoadMethod();
    assertThat(domainApplication.getPeriod()).isNull();
  }

  // TODO(b/32447342): remove this once the period has been populated on all DomainApplications
  @Test
  public void testPeriodIsOneYearBecauseHistoryEntryHasNoPeriod() {
    persistResource(makeHistoryEntry(
        domainApplication,
        HistoryEntry.Type.DOMAIN_APPLICATION_CREATE,
        Period.create(5, Period.Unit.YEARS),
        "testing",
        DateTime.now(UTC),
        loadFileWithSubstitutions(
            getClass(), "domain_create_landrush.xml", ImmutableMap.<String, String>of())));
    triggerTheOnLoadMethod();
    assertThat(domainApplication.getPeriod()).isNotNull();
    assertThat(domainApplication.getPeriod()).isEqualTo(Period.create(1, Period.Unit.YEARS));
  }

  // TODO(b/32447342): remove this once the period has been populated on all DomainApplications
  @Test
  public void testPeriodDefaultedFromHistoryEntry() {
    persistResource(makeHistoryEntry(
        domainApplication,
        HistoryEntry.Type.DOMAIN_APPLICATION_CREATE,
        Period.create(5, Period.Unit.YEARS),
        "testing",
        DateTime.now(UTC),
        loadFileWithSubstitutions(
            getClass(), "domain_create_landrush_with_period.xml", ImmutableMap.of("PERIOD", "5"))));
    triggerTheOnLoadMethod();
    assertThat(domainApplication.getPeriod()).isNotNull();
    assertThat(domainApplication.getPeriod()).isEqualTo(Period.create(5, Period.Unit.YEARS));
  }

  // TODO(b/32447342): remove this once the period has been populated on all DomainApplications
  @Test
  public void testPeriodAlreadySet() {
    domainApplication = persistResource(
        domainApplication.asBuilder().setPeriod(Period.create(1, Period.Unit.YEARS)).build());
    persistResource(makeHistoryEntry(
        domainApplication,
        HistoryEntry.Type.DOMAIN_APPLICATION_CREATE,
        Period.create(5, Period.Unit.YEARS),
        "testing",
        DateTime.now(UTC),
        loadFileWithSubstitutions(
            getClass(), "domain_create_landrush_with_period.xml", ImmutableMap.of("PERIOD", "5"))));
    triggerTheOnLoadMethod();
    assertThat(domainApplication.getPeriod()).isNotNull();
    assertThat(domainApplication.getPeriod()).isEqualTo(Period.create(1, Period.Unit.YEARS));
  }
}
