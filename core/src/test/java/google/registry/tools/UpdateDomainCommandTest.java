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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.eppcommon.StatusValue.SERVER_UPDATE_PROHIBITED;
import static google.registry.testing.DatastoreHelper.createTlds;
import static google.registry.testing.DatastoreHelper.newContactResource;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistResource;
import static org.junit.Assert.assertThrows;

import com.beust.jcommander.ParameterException;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.persistence.VKey;
import org.junit.Test;

/** Unit tests for {@link UpdateDomainCommand}. */
public class UpdateDomainCommandTest extends EppToolCommandTestCase<UpdateDomainCommand> {

  @Test
  public void testSuccess_complete() throws Exception {
    runCommandForced(
        "--client=NewRegistrar",
        "--add_nameservers=ns1.zdns.google,ns2.zdns.google",
        "--add_admins=crr-admin2",
        "--add_techs=crr-tech2",
        "--add_statuses=serverDeleteProhibited",
        "--add_ds_records=1 2 3 abcd,4 5 6 EF01",
        "--remove_nameservers=ns3.zdns.google,ns4.zdns.google",
        "--remove_admins=crr-admin1",
        "--remove_techs=crr-tech1",
        "--remove_statuses=serverHold",
        "--remove_ds_records=7 8 9 12ab,6 5 4 34CD",
        "--registrant=crr-admin",
        "--password=2fooBAR",
        "example.tld");
    eppVerifier.verifySent("domain_update_complete.xml");
  }

  @Test
  public void testSuccess_completeWithSquareBrackets() throws Exception {
    runCommandForced(
        "--client=NewRegistrar",
        "--add_nameservers=ns[1-2].zdns.google",
        "--add_admins=crr-admin2",
        "--add_techs=crr-tech2",
        "--add_statuses=serverDeleteProhibited",
        "--add_ds_records=1 2 3 abcd,4 5 6 EF01",
        "--remove_nameservers=ns[3-4].zdns.google",
        "--remove_admins=crr-admin1",
        "--remove_techs=crr-tech1",
        "--remove_statuses=serverHold",
        "--remove_ds_records=7 8 9 12ab,6 5 4 34CD",
        "--registrant=crr-admin",
        "--password=2fooBAR",
        "example.tld");
    eppVerifier.verifySent("domain_update_complete.xml");
  }

  @Test
  public void testSuccess_multipleDomains() throws Exception {
    runCommandForced(
        "--client=NewRegistrar",
        "--add_nameservers=ns1.zdns.google,ns2.zdns.google",
        "--add_admins=crr-admin2",
        "--add_techs=crr-tech2",
        "--add_statuses=serverDeleteProhibited",
        "--add_ds_records=1 2 3 abcd,4 5 6 EF01",
        "--remove_nameservers=ns[3-4].zdns.google",
        "--remove_admins=crr-admin1",
        "--remove_techs=crr-tech1",
        "--remove_statuses=serverHold",
        "--remove_ds_records=7 8 9 12ab,6 5 4 34CD",
        "--registrant=crr-admin",
        "--password=2fooBAR",
        "example.tld",
        "example.abc");
    eppVerifier
        .verifySent("domain_update_complete.xml")
        .verifySent("domain_update_complete_abc.xml");
  }

  @Test
  public void testSuccess_multipleDomains_setNameservers() throws Exception {
    runTest_multipleDomains_setNameservers("-n ns1.foo.fake,ns2.foo.fake");
  }

  @Test
  public void testSuccess_multipleDomains_setNameserversWithSquareBrackets() throws Exception {
    runTest_multipleDomains_setNameservers("-n ns[1-2].foo.fake");
  }

  private void runTest_multipleDomains_setNameservers(String nsParam) throws Exception {
    createTlds("abc", "tld");
    HostResource host1 = persistActiveHost("foo.bar.tld");
    HostResource host2 = persistActiveHost("baz.bar.tld");
    persistResource(
        newDomainBase("example.abc")
            .asBuilder()
            .setNameservers(ImmutableSet.of(host1.createKey()))
            .build());
    persistResource(
        newDomainBase("example.tld")
            .asBuilder()
            .setNameservers(ImmutableSet.of(host2.createKey()))
            .build());
    runCommandForced(
        "--client=NewRegistrar", nsParam, "example.abc", "example.tld");
    eppVerifier
        .verifySent(
            "domain_update_add_two_hosts_remove_one.xml",
            ImmutableMap.of("DOMAIN", "example.abc", "REMOVEHOST", "foo.bar.tld"))
        .verifySent(
            "domain_update_add_two_hosts_remove_one.xml",
            ImmutableMap.of("DOMAIN", "example.tld", "REMOVEHOST", "baz.bar.tld"));
  }

  @Test
  public void testSuccess_add() throws Exception {
    runCommandForced(
        "--client=NewRegistrar",
        "--add_nameservers=ns2.zdns.google,ns3.zdns.google",
        "--add_admins=crr-admin2",
        "--add_techs=crr-tech2",
        "--add_statuses=serverDeleteProhibited",
        "--add_ds_records=1 2 3 abcd,4 5 6 EF01",
        "example.tld");
    eppVerifier.verifySent("domain_update_add.xml");
  }

  @Test
  public void testSuccess_remove() throws Exception {
    runCommandForced(
        "--client=NewRegistrar",
        "--remove_nameservers=ns4.zdns.google",
        "--remove_admins=crr-admin1",
        "--remove_techs=crr-tech1",
        "--remove_statuses=serverHold",
        "--remove_ds_records=7 8 9 12ab,6 5 4 34CD",
        "example.tld");
    eppVerifier.verifySent("domain_update_remove.xml");
  }

  @Test
  public void testSuccess_change() throws Exception {
    runCommandForced(
        "--client=NewRegistrar", "--registrant=crr-admin", "--password=2fooBAR", "example.tld");
    eppVerifier.verifySent("domain_update_change.xml");
  }

  @Test
  public void testSuccess_setNameservers() throws Exception {
    HostResource host1 = persistActiveHost("ns1.zdns.google");
    HostResource host2 = persistActiveHost("ns2.zdns.google");
    ImmutableSet<VKey<HostResource>> nameservers =
        ImmutableSet.of(host1.createKey(), host2.createKey());
    persistResource(
        newDomainBase("example.tld").asBuilder().setNameservers(nameservers).build());
    runCommandForced(
        "--client=NewRegistrar", "--nameservers=ns2.zdns.google,ns3.zdns.google", "example.tld");
    eppVerifier.verifySent("domain_update_set_nameservers.xml");
  }

  @Test
  public void testSuccess_setContacts() throws Exception {
    ContactResource adminContact1 = persistResource(newContactResource("crr-admin1"));
    ContactResource adminContact2 = persistResource(newContactResource("crr-admin2"));
    ContactResource techContact1 = persistResource(newContactResource("crr-tech1"));
    ContactResource techContact2 = persistResource(newContactResource("crr-tech2"));
    Key<ContactResource> adminResourceKey1 = Key.create(adminContact1);
    Key<ContactResource> adminResourceKey2 = Key.create(adminContact2);
    Key<ContactResource> techResourceKey1 = Key.create(techContact1);
    Key<ContactResource> techResourceKey2 = Key.create(techContact2);

    persistResource(
        newDomainBase("example.tld")
            .asBuilder()
            .setContacts(
                ImmutableSet.of(
                    DesignatedContact.create(DesignatedContact.Type.ADMIN, adminResourceKey1),
                    DesignatedContact.create(DesignatedContact.Type.ADMIN, adminResourceKey2),
                    DesignatedContact.create(DesignatedContact.Type.TECH, techResourceKey1),
                    DesignatedContact.create(DesignatedContact.Type.TECH, techResourceKey2)))
            .build());

    runCommandForced(
        "--client=NewRegistrar",
        "--admins=crr-admin2,crr-admin3",
        "--techs=crr-tech2,crr-tech3",
        "example.tld");
    eppVerifier.verifySent("domain_update_set_contacts.xml");
  }

  @Test
  public void testSuccess_setStatuses() throws Exception {
    HostResource host = persistActiveHost("ns1.zdns.google");
    ImmutableSet<VKey<HostResource>> nameservers = ImmutableSet.of(host.createKey());
    persistResource(
        newDomainBase("example.tld")
            .asBuilder()
            .setStatusValues(
                ImmutableSet.of(
                    StatusValue.CLIENT_RENEW_PROHIBITED, StatusValue.SERVER_TRANSFER_PROHIBITED))
            .setNameservers(nameservers)
            .build());

    runCommandForced(
        "--client=NewRegistrar", "--statuses=clientRenewProhibited,serverHold", "example.tld");
    eppVerifier.verifySent("domain_update_set_statuses.xml");
  }

  @Test
  public void testSuccess_setDsRecords() throws Exception {
    runCommandForced(
        "--client=NewRegistrar", "--ds_records=1 2 3 abcd,4 5 6 EF01", "example.tld");
    eppVerifier.verifySent("domain_update_set_ds_records.xml");
  }

  @Test
  public void testSuccess_setDsRecords_withUnneededClear() throws Exception {
    runCommandForced(
        "--client=NewRegistrar",
        "--ds_records=1 2 3 abcd,4 5 6 EF01",
        "--clear_ds_records",
        "example.tld");
    eppVerifier.verifySent("domain_update_set_ds_records.xml");
  }

  @Test
  public void testSuccess_clearDsRecords() throws Exception {
    runCommandForced(
        "--client=NewRegistrar",
        "--clear_ds_records",
        "example.tld");
    eppVerifier.verifySent("domain_update_clear_ds_records.xml");
  }

  @Test
  public void testFailure_cantUpdateRegistryLockedDomainEvenAsSuperuser() {
    HostResource host = persistActiveHost("ns1.zdns.google");
    ImmutableSet<VKey<HostResource>> nameservers = ImmutableSet.of(host.createKey());
    persistResource(
        newDomainBase("example.tld")
            .asBuilder()
            .setStatusValues(ImmutableSet.of(SERVER_UPDATE_PROHIBITED))
            .setNameservers(nameservers)
            .build());

    Exception e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--statuses=clientRenewProhibited,serverHold",
                    "--superuser",
                    "example.tld"));
    assertThat(e)
        .hasMessageThat()
        .containsMatch("The domain 'example.tld' has status SERVER_UPDATE_PROHIBITED");
  }

  @Test
  public void testFailure_duplicateDomains() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--registrant=crr-admin",
                    "--password=2fooBAR",
                    "example.tld",
                    "example.tld"));
    assertThat(thrown).hasMessageThat().contains("Duplicate arguments found");
  }

  @Test
  public void testFailure_missingDomain() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar", "--registrant=crr-admin", "--password=2fooBAR"));
    assertThat(thrown).hasMessageThat().contains("Main parameters are required");
  }

  @Test
  public void testFailure_missingClientId() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () -> runCommandForced("--registrant=crr-admin", "--password=2fooBAR", "example.tld"));
    assertThat(thrown).hasMessageThat().contains("--client");
  }

  @Test
  public void testFailure_addTooManyNameServers() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--add_nameservers=ns1.zdns.google,ns2.zdns.google,ns3.zdns.google,ns4.zdns.google,"
                        + "ns5.zdns.google,ns6.zdns.google,ns7.zdns.google,ns8.zdns.google,"
                        + "ns9.zdns.google,ns10.zdns.google,ns11.zdns.google,ns12.zdns.google,"
                        + "ns13.zdns.google,ns14.zdns.google",
                    "--add_admins=crr-admin2",
                    "--add_techs=crr-tech2",
                    "--add_statuses=serverDeleteProhibited",
                    "example.tld"));
    assertThat(thrown).hasMessageThat().contains("You can add at most 13 nameservers");
  }

  @Test
  public void testFailure_providedNameserversAndAddNameservers() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--add_nameservers=ns1.zdns.google",
                    "--nameservers=ns2.zdns.google,ns3.zdns.google",
                    "example.tld"));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "If you provide the nameservers flag, "
                + "you cannot use the add_nameservers and remove_nameservers flags.");
  }

  @Test
  public void testFailure_providedNameserversAndRemoveNameservers() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--remove_nameservers=ns1.zdns.google",
                    "--nameservers=ns2.zdns.google,ns3.zdns.google",
                    "example.tld"));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "If you provide the nameservers flag, "
                + "you cannot use the add_nameservers and remove_nameservers flags.");
  }

  @Test
  public void testFailure_providedAdminsAndAddAdmins() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--add_admins=crr-admin2",
                    "--admins=crr-admin2",
                    "example.tld"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "If you provide the admins flag, "
                + "you cannot use the add_admins and remove_admins flags.");
  }

  @Test
  public void testFailure_providedAdminsAndRemoveAdmins() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--remove_admins=crr-admin2",
                    "--admins=crr-admin2",
                    "example.tld"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "If you provide the admins flag, "
                + "you cannot use the add_admins and remove_admins flags.");
  }

  @Test
  public void testFailure_providedTechsAndAddTechs() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--add_techs=crr-tech2",
                    "--techs=crr-tech2",
                    "example.tld"));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "If you provide the techs flag, you cannot use the add_techs and remove_techs flags.");
  }

  @Test
  public void testFailure_providedTechsAndRemoveTechs() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--remove_techs=crr-tech2",
                    "--techs=crr-tech2",
                    "example.tld"));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "If you provide the techs flag, you cannot use the add_techs and remove_techs flags.");
  }

  @Test
  public void testFailure_providedStatusesAndAddStatuses() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--add_statuses=serverHold",
                    "--statuses=crr-serverHold",
                    "example.tld"));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "If you provide the statuses flag, "
                + "you cannot use the add_statuses and remove_statuses flags.");
  }

  @Test
  public void testFailure_providedStatusesAndRemoveStatuses() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--remove_statuses=serverHold",
                    "--statuses=crr-serverHold",
                    "example.tld"));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "If you provide the statuses flag, "
                + "you cannot use the add_statuses and remove_statuses flags.");
  }

  @Test
  public void testFailure_provideDsRecordsAndAddDsRecords() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--add_ds_records=1 2 3 abcd",
                    "--ds_records=4 5 6 EF01",
                    "example.tld"));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "If you provide the ds_records or clear_ds_records flags, "
                + "you cannot use the add_ds_records and remove_ds_records flags.");
  }

  @Test
  public void testFailure_provideDsRecordsAndRemoveDsRecords() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--remove_ds_records=7 8 9 12ab",
                    "--ds_records=4 5 6 EF01",
                    "example.tld"));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "If you provide the ds_records or clear_ds_records flags, "
                + "you cannot use the add_ds_records and remove_ds_records flags.");
  }

  @Test
  public void testFailure_clearDsRecordsAndAddDsRecords() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--add_ds_records=1 2 3 abcd",
                    "--clear_ds_records",
                    "example.tld"));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "If you provide the ds_records or clear_ds_records flags, "
                + "you cannot use the add_ds_records and remove_ds_records flags.");
  }

  @Test
  public void testFailure_clearDsRecordsAndRemoveDsRecords() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--remove_ds_records=7 8 9 12ab",
                    "--clear_ds_records",
                    "example.tld"));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "If you provide the ds_records or clear_ds_records flags, "
                + "you cannot use the add_ds_records and remove_ds_records flags.");
  }
}
