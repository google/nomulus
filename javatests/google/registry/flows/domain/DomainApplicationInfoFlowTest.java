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

package google.registry.flows.domain;

import static com.google.common.io.BaseEncoding.base16;
import static google.registry.testing.DatastoreHelper.assertNoBillingEvents;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.TestDataHelper.loadFileWithSubstitutions;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException;
import google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException;
import google.registry.flows.domain.DomainApplicationInfoFlow.ApplicationLaunchPhaseMismatchException;
import google.registry.flows.domain.DomainApplicationInfoFlow.MissingApplicationIdException;
import google.registry.flows.domain.DomainFlowUtils.ApplicationDomainNameMismatchException;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.DesignatedContact.Type;
import google.registry.model.domain.DomainApplication;
import google.registry.model.domain.DomainAuthInfo;
import google.registry.model.domain.TestExtraLogicManager;
import google.registry.model.domain.launch.ApplicationStatus;
import google.registry.model.domain.launch.LaunchCreateExtension;
import google.registry.model.domain.launch.LaunchPhase;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.model.registry.Registry.TldState;
import google.registry.model.smd.EncodedSignedMark;
import google.registry.testing.AppEngineRule;
import google.registry.testing.EppLoader;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link DomainApplicationInfoFlow}. */
public class DomainApplicationInfoFlowTest
    extends ResourceFlowTestCase<DomainApplicationInfoFlow, DomainApplication> {

  private ContactResource registrant;
  private ContactResource contact;
  private HostResource host1;
  private HostResource host2;
  private DomainApplication application;

  private enum MarksState { MARKS_EXIST, NO_MARKS_EXIST }
  private enum HostsState { HOSTS_EXIST, NO_HOSTS_EXIST }

  @Before
  public void resetClientId() {
    setEppInput("domain_info_sunrise.xml");
    sessionMetadata.setClientId("NewRegistrar");
    createTld("tld", TldState.SUNRUSH);
    createTld("flags", TldState.SUNRUSH);
    // For flags extension tests.
    RegistryExtraFlowLogicProxy.setOverride("flags", TestExtraLogicManager.class);
  }

  private void persistTestEntities(HostsState hostsState, MarksState marksState) throws Exception {
    registrant = persistActiveContact("jd1234");
    contact = persistActiveContact("sh8013");
    host1 = persistActiveHost("ns1.example.net");
    host2 = persistActiveHost("ns1.example.tld");
    application = persistResource(new DomainApplication.Builder()
        .setRepoId("123-TLD")
        .setFullyQualifiedDomainName("example.tld")
        .setPhase(LaunchPhase.SUNRUSH)
        .setCurrentSponsorClientId("NewRegistrar")
        .setCreationClientId("TheRegistrar")
        .setLastEppUpdateClientId("NewRegistrar")
        .setCreationTimeForTest(DateTime.parse("1999-04-03T22:00:00.0Z"))
        .setLastEppUpdateTime(DateTime.parse("1999-12-03T09:00:00.0Z"))
        .setRegistrant(Key.create(registrant))
        .setContacts(ImmutableSet.of(
            DesignatedContact.create(Type.ADMIN, Key.create(contact)),
            DesignatedContact.create(Type.TECH, Key.create(contact))))
        .setNameservers(hostsState.equals(HostsState.HOSTS_EXIST) ? ImmutableSet.of(
            Key.create(host1), Key.create(host2)) : null)
        .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("2fooBAR")))
        .addStatusValue(StatusValue.PENDING_CREATE)
        .setApplicationStatus(ApplicationStatus.PENDING_VALIDATION)
        .setEncodedSignedMarks(marksState.equals(MarksState.MARKS_EXIST)
            // If we need to include an encoded signed mark, pull it out of the create xml.
            ? ImmutableList.of((EncodedSignedMark)
                new EppLoader(this, "domain_create_sunrise_encoded_signed_mark.xml")
                    .getEpp()
                    .getSingleExtension(LaunchCreateExtension.class)
                    .getSignedMarks().get(0))
            : null)
        .build());
  }

  private void persistFlagsTestEntities(String domainName, HostsState hostsState) throws Exception {
    registrant = persistActiveContact("jd1234");
    contact = persistActiveContact("sh8013");
    host1 = persistActiveHost("ns1.example.net");
    host2 = persistActiveHost("ns1.example.tld");
    application = persistResource(new DomainApplication.Builder()
        .setRepoId("123-TLD")
        .setFullyQualifiedDomainName(domainName)
        .setPhase(LaunchPhase.SUNRUSH)
        .setCurrentSponsorClientId("NewRegistrar")
        .setCreationClientId("TheRegistrar")
        .setLastEppUpdateClientId("NewRegistrar")
        .setCreationTimeForTest(DateTime.parse("1999-04-03T22:00:00.0Z"))
        .setLastEppUpdateTime(DateTime.parse("1999-12-03T09:00:00.0Z"))
        .setRegistrant(Key.create(registrant))
        .setContacts(ImmutableSet.of(
            DesignatedContact.create(Type.ADMIN, Key.create(contact)),
            DesignatedContact.create(Type.TECH, Key.create(contact))))
        .setNameservers(hostsState.equals(HostsState.HOSTS_EXIST) ? ImmutableSet.of(
            Key.create(host1), Key.create(host2)) : null)
        .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("2fooBAR")))
        .addStatusValue(StatusValue.PENDING_CREATE)
        .setApplicationStatus(ApplicationStatus.PENDING_VALIDATION)
        .setEncodedSignedMarks(null)
        .build());
  }

  private void doSuccessfulTest(String expectedXmlFilename, HostsState hostsState)
      throws Exception {
    assertTransactionalFlow(false);
    String expected = loadFileWithSubstitutions(
        getClass(), expectedXmlFilename, ImmutableMap.of("ROID", "123-TLD"));
    if (hostsState.equals(HostsState.NO_HOSTS_EXIST)) {
      expected = expected.replaceAll("\"ok\"", "\"inactive\"");
    }
    runFlowAssertResponse(expected);
    assertNoHistory();
    assertNoBillingEvents();
  }

  private void doSuccessfulTest(String expectedXmlFilename) throws Exception {
    persistTestEntities(HostsState.HOSTS_EXIST, MarksState.NO_MARKS_EXIST);
    doSuccessfulTest(expectedXmlFilename, HostsState.HOSTS_EXIST);
  }

  private void doSuccessfulTestNoNameservers(String expectedXmlFilename) throws Exception {
    persistTestEntities(HostsState.NO_HOSTS_EXIST, MarksState.NO_MARKS_EXIST);
    doSuccessfulTest(expectedXmlFilename, HostsState.NO_HOSTS_EXIST);
  }

  @Test
  public void testSuccess_quietPeriod() throws Exception {
    createTld("tld", TldState.QUIET_PERIOD);
    doSuccessfulTest("domain_info_sunrise_response.xml");
  }

  @Test
  public void testSuccess_generalAvailability() throws Exception {
    createTld("tld", TldState.GENERAL_AVAILABILITY);
    doSuccessfulTest("domain_info_sunrise_response.xml");
  }

  @Test
  public void testSuccess_requestedDefaultHosts_nameserversExist() throws Exception {
    // Default is "all", which means nameservers since there can't be subordinates.
    doSuccessfulTest("domain_info_sunrise_response.xml");
  }

  @Test
  public void testSuccess_requestedDefaultHosts_noNameserversExist() throws Exception {
    // Default is "all", which means nameservers since there can't be subordinates.
    doSuccessfulTestNoNameservers("domain_info_sunrise_response_no_nameservers.xml");
  }

  @Test
  public void testSuccess_requestedAllHosts_nameserversExist() throws Exception {
    // "All" means nameservers since there can't be subordinates (same as "delegated").
    setEppInput("domain_info_sunrise_all_hosts.xml");
    doSuccessfulTest("domain_info_sunrise_response.xml");
  }

  @Test
  public void testSuccess_requestedAllHosts_noNameserversExist() throws Exception {
    // "All" means nameservers since there can't be subordinates (same as "delegated").
    setEppInput("domain_info_sunrise_all_hosts.xml");
    doSuccessfulTestNoNameservers("domain_info_sunrise_response_no_nameservers.xml");
  }

  @Test
  public void testSuccess_requestedDelegatedHosts_nameserversExist() throws Exception {
    // "Delegated" means nameservers since there can't be subordinates (same as "all").
    setEppInput("domain_info_sunrise_delegated_hosts.xml");
    doSuccessfulTest("domain_info_sunrise_response.xml");
  }

  @Test
  public void testSuccess_requestedDelegatedHosts_noNameserversExist() throws Exception {
    // "Delegated" means nameservers since there can't be subordinates (same as "all").
    setEppInput("domain_info_sunrise_delegated_hosts.xml");
    doSuccessfulTestNoNameservers("domain_info_sunrise_response_no_nameservers.xml");
  }

  @Test
  public void testSuccess_requestedNoneHosts_nameserversExist() throws Exception {
    setEppInput("domain_info_sunrise_none_hosts.xml");
    doSuccessfulTestNoNameservers("domain_info_sunrise_response_no_nameservers.xml");
  }

  @Test
  public void testSuccess_requestedNoneHosts_noNameserversExist() throws Exception {
    setEppInput("domain_info_sunrise_none_hosts.xml");
    doSuccessfulTestNoNameservers("domain_info_sunrise_response_no_nameservers.xml");
  }

  @Test
  public void testSuccess_requestedDefaultMarks_noMarksExist() throws Exception {
    persistTestEntities(HostsState.HOSTS_EXIST, MarksState.NO_MARKS_EXIST);
    doSuccessfulTest("domain_info_sunrise_response.xml", HostsState.HOSTS_EXIST);
  }

  @Test
  public void testSuccess_requestedDefaultMarks_marksExist() throws Exception {
    persistTestEntities(HostsState.HOSTS_EXIST, MarksState.MARKS_EXIST);
    doSuccessfulTest("domain_info_sunrise_response.xml", HostsState.HOSTS_EXIST);
  }

  @Test
  public void testSuccess_requestedNoMarks_marksExist() throws Exception {
    setEppInput("domain_info_sunrise_no_marks.xml");
    persistTestEntities(HostsState.HOSTS_EXIST, MarksState.MARKS_EXIST);
    doSuccessfulTest("domain_info_sunrise_response.xml", HostsState.HOSTS_EXIST);
  }

  @Test
  public void testSuccess_requestedNoMarks_noMarksExist() throws Exception {
    setEppInput("domain_info_sunrise_no_marks.xml");
    persistTestEntities(HostsState.HOSTS_EXIST, MarksState.NO_MARKS_EXIST);
    doSuccessfulTest("domain_info_sunrise_response.xml", HostsState.HOSTS_EXIST);
  }

  @Test
  public void testSuccess_requestedIncludeMarks_marksExist() throws Exception {
    setEppInput("domain_info_sunrise_include_marks.xml");
    persistTestEntities(HostsState.HOSTS_EXIST, MarksState.MARKS_EXIST);
    doSuccessfulTest("domain_info_sunrise_response_with_mark.xml", HostsState.HOSTS_EXIST);
  }

  @Test
  public void testSuccess_requestedIncludeMarks_noMarksExist() throws Exception {
    setEppInput("domain_info_sunrise_include_marks.xml");
    persistTestEntities(HostsState.HOSTS_EXIST, MarksState.NO_MARKS_EXIST);
    doSuccessfulTest("domain_info_sunrise_response.xml", HostsState.HOSTS_EXIST);
  }

  @Test
  public void testSuccess_secDns() throws Exception {
    persistTestEntities(HostsState.NO_HOSTS_EXIST, MarksState.NO_MARKS_EXIST);
    // Add the dsData to the saved resource and change the nameservers to match the sample xml.
    persistResource(application.asBuilder()
        .setDsData(ImmutableSet.of(DelegationSignerData.create(
            12345, 3, 1, base16().decode("49FD46E6C4B45C55D4AC"))))
        .setNameservers(ImmutableSet.of(
            Key.create(host1), Key.create(host2)))
        .build());
    doSuccessfulTest("domain_info_sunrise_response_dsdata.xml", HostsState.NO_HOSTS_EXIST);
  }

  @Test
  public void testSuccess_allocated() throws Exception {
    persistTestEntities(HostsState.HOSTS_EXIST, MarksState.NO_MARKS_EXIST);
    // Update the application status of the saved resource.
    persistResource(application.asBuilder()
        .removeStatusValue(StatusValue.PENDING_CREATE)
        .setApplicationStatus(ApplicationStatus.ALLOCATED)
        .build());
    doSuccessfulTest("domain_info_sunrise_allocated.xml", HostsState.HOSTS_EXIST);
  }

  @Test
  public void testFailure_neverExisted() throws Exception {
    thrown.expect(
        ResourceDoesNotExistException.class,
        String.format("(%s)", getUniqueIdFromCommand()));
    runFlow();
  }

  @Test
  public void testFailure_existedButWasDeleted() throws Exception {
    persistResource(new DomainApplication.Builder()
        .setRepoId("123-COM")
        .setFullyQualifiedDomainName("timber.com")
        .setDeletionTime(clock.nowUtc().minusDays(1))
        .setRegistrant(Key.create(persistActiveContact("jd1234")))
        .build());
    thrown.expect(
        ResourceDoesNotExistException.class,
        String.format("(%s)", getUniqueIdFromCommand()));
    runFlow();
  }

  @Test
  public void testFailure_unauthorized() throws Exception {
    persistResource(
        AppEngineRule.makeRegistrar1().asBuilder().setClientId("ClientZ").build());
    sessionMetadata.setClientId("ClientZ");
    persistTestEntities(HostsState.NO_HOSTS_EXIST, MarksState.NO_MARKS_EXIST);
    thrown.expect(ResourceNotOwnedException.class);
    runFlow();
  }

  @Test
  public void testFailure_applicationIdForDifferentDomain() throws Exception {
    persistResource(new DomainApplication.Builder()
        .setRepoId("123-TLD")
        .setFullyQualifiedDomainName("invalid.tld")
        .setRegistrant(Key.create(persistActiveContact("jd1234")))
        .setPhase(LaunchPhase.SUNRUSH)
        .build());
    thrown.expect(ApplicationDomainNameMismatchException.class);
    runFlow();
  }

  @Test
  public void testFailure_noApplicationId() throws Exception {
    setEppInput("domain_info_sunrise_no_application_id.xml");
    persistTestEntities(HostsState.NO_HOSTS_EXIST, MarksState.NO_MARKS_EXIST);
    thrown.expect(MissingApplicationIdException.class);
    runFlow();
  }

  @Test
  public void testFailure_mismatchedLaunchPhase() throws Exception {
    persistTestEntities(HostsState.NO_HOSTS_EXIST, MarksState.NO_MARKS_EXIST);
    application = persistResource(
        application.asBuilder().setPhase(LaunchPhase.SUNRISE).build());
    thrown.expect(ApplicationLaunchPhaseMismatchException.class);
    runFlow();
  }


  /** Test registry extra logic manager with no flags. */
  @Test
  public void testExtraLogicManager_noFlags() throws Exception {
    setEppInput("domain_info_sunrise_flags_none.xml");
    persistFlagsTestEntities("domain.flags", HostsState.NO_HOSTS_EXIST);
    doSuccessfulTest("domain_info_response_sunrise_flags_none.xml", HostsState.NO_HOSTS_EXIST);
  }

  /** Test registry extra logic manager with two flags. */
  @Test
  public void testExtraLogicManager_twoFlags() throws Exception {
    setEppInput("domain_info_sunrise_flags_two.xml");
    persistFlagsTestEntities("domain-flag1-flag2.flags", HostsState.NO_HOSTS_EXIST);
    doSuccessfulTest("domain_info_response_sunrise_flags_two.xml", HostsState.NO_HOSTS_EXIST);
  }
}
