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

package google.registry.tools;

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.io.Resources.getResource;
import static com.google.common.io.Resources.toByteArray;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.flows.picker.FlowPicker.getFlowClass;
import static google.registry.model.domain.DesignatedContact.Type.ADMIN;
import static google.registry.model.domain.DesignatedContact.Type.BILLING;
import static google.registry.model.domain.DesignatedContact.Type.TECH;
import static google.registry.model.domain.launch.ApplicationStatus.VALIDATED;
import static google.registry.model.registry.Registry.TldState.QUIET_PERIOD;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.newDomainApplication;
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.ResourceUtils.readResourceBytes;

import com.google.common.collect.ImmutableSet;

import com.beust.jcommander.ParameterException;
import com.googlecode.objectify.Ref;

import google.registry.flows.EppXmlTransformer;
import google.registry.flows.domain.DomainAllocateFlow;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.DomainApplication;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.Trid;
import google.registry.model.eppinput.EppInput;
import google.registry.model.reporting.HistoryEntry;
import google.registry.tools.ServerSideCommand.Connection;

import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.IOException;

/** Unit tests for {@link AllocateDomainCommand}. */
public class AllocateDomainCommandTest extends CommandTestCase<AllocateDomainCommand> {

  @Mock
  Connection connection;

  @Before
  public void init() throws IOException {
    command.setConnection(connection);
    createTld("tld", QUIET_PERIOD);
    createApplication("example-one.tld", "testdata/domain_create_sunrush.xml", "1-TLD");
    createApplication("example-two.tld", "testdata/domain_create_sunrush2.xml", "2-TLD");
  }

  private void createApplication(String name, String xmlFile, String repoId) throws IOException {
    DomainApplication application =
        persistResource(newDomainApplication(name)
            .asBuilder()
            .setRepoId(repoId)
            .setCreationTime(START_OF_TIME)
            .setRegistrant(Ref.create(persistActiveContact("registrant")))
            .setContacts(ImmutableSet.of(
                DesignatedContact.create(
                    ADMIN,
                    Ref.create(persistActiveContact("adminContact"))),
                DesignatedContact.create(
                    BILLING,
                    Ref.create(persistActiveContact("billingContact"))),
                DesignatedContact.create(
                    TECH,
                    Ref.create(persistActiveContact("techContact")))))
            .setNameservers(ImmutableSet.of(
                Ref.create(persistActiveHost("ns1.example.com")),
                Ref.create(persistActiveHost("ns2.example.com"))))
            .setApplicationStatus(VALIDATED)
            .setDsData(ImmutableSet.of(
                DelegationSignerData.create(
                    12345, 3, 1, base16().decode("49FD46E6C4B45C55D4AC")),
                DelegationSignerData.create(
                    56789, 2, 4, base16().decode("69FD46E6C4A45C55D4AC"))))
            .setLaunchNotice(LaunchNotice.create(
                "370d0b7c9223372036854775807",
                "tmch",
                DateTime.parse("2010-08-16T09:00:00.0Z"),
                DateTime.parse("2009-08-16T09:00:00.0Z")))
            .build());
    persistResource(
        new HistoryEntry.Builder()
            .setParent(application)
            .setClientId("NewRegistrar")
            .setModificationTime(application.getCreationTime())
            .setTrid(Trid.create("ABC-123"))
            .setXmlBytes(toByteArray(getResource(AllocateDomainCommandTest.class, xmlFile)))
            .build());
  }

  private EppToolVerifier eppVerifier() {
    return new EppToolVerifier()
        .withConnection(connection)
        .withClientIdentifier("TheRegistrar")
        .asSuperuser();
  }

  @Test
  public void testSuccess() throws Exception {
    runCommand("--ids=1-TLD", "--force", "--superuser");
    // NB: These commands are sent as the sponsoring registrar, in this case "TheRegistrar".
    eppVerifier().verifySent("allocate_domain.xml");
  }

  @Test
  public void testSuccess_multiple() throws Exception {
    runCommand("--ids=1-TLD,2-TLD", "--force", "--superuser");
    eppVerifier().verifySent("allocate_domain.xml", "allocate_domain2.xml");
  }

  @Test
  public void testSuccess_dryRun() throws Exception {
    runCommand("--ids=1-TLD", "--dry_run", "--superuser");
    eppVerifier().asDryRun().verifySent("allocate_domain.xml");
  }

  @Test
  public void testFailure_notAsSuperuser() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    runCommand("--ids=1-TLD", "--force");
  }

  @Test
  public void testFailure_forceAndDryRunIncompatible() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    runCommand("--ids=1-TLD", "--force", "--dry_run", "--superuser");
  }

  @Test
  public void testFailure_unknownFlag() throws Exception {
    thrown.expect(ParameterException.class);
    runCommand("--ids=1-TLD", "--force", "--unrecognized=foo", "--superuser");
  }

  @Test
  public void testXmlInstantiatesFlow() throws Exception {
    assertThat(
        getFlowClass(EppXmlTransformer.<EppInput>unmarshal(
            readResourceBytes(getClass(), "testdata/allocate_domain.xml").read())))
                .isEqualTo(DomainAllocateFlow.class);
  }
}
