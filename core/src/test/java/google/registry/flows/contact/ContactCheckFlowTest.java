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

package google.registry.flows.contact;

import static google.registry.model.eppoutput.CheckData.ContactCheck.create;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistDeletedContact;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.flows.EppException;
import google.registry.flows.ResourceCheckFlowTestCase;
import google.registry.flows.exceptions.TooManyResourceChecksException;
import google.registry.model.contact.ContactResource;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.ReplayExtension;
import google.registry.testing.TestOfyAndSql;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link ContactCheckFlow}. */
@DualDatabaseTest
class ContactCheckFlowTest extends ResourceCheckFlowTestCase<ContactCheckFlow, ContactResource> {

  @Order(value = Order.DEFAULT - 2)
  @RegisterExtension
  final ReplayExtension replayExtension = ReplayExtension.createWithCompare(clock);

  ContactCheckFlowTest() {
    setEppInput("contact_check.xml");
  }

  @TestOfyAndSql
  void testNothingExists() throws Exception {
    // These ids come from the check xml.
    doCheckTest(
        create(true, "sh8013", null),
        create(true, "sah8013", null),
        create(true, "8013sah", null));
  }

  @TestOfyAndSql
  void testOneExists() throws Exception {
    persistActiveContact("sh8013");
    // These ids come from the check xml.
    doCheckTest(
        create(false, "sh8013", "In use"),
        create(true, "sah8013", null),
        create(true, "8013sah", null));
  }

  @TestOfyAndSql
  void testOneExistsButWasDeleted() throws Exception {
    persistDeletedContact("sh8013", clock.nowUtc().minusDays(1));
    // These ids come from the check xml.
    doCheckTest(
        create(true, "sh8013", null),
        create(true, "sah8013", null),
        create(true, "8013sah", null));
  }

  @TestOfyAndSql
  void testXmlMatches() throws Exception {
    persistActiveContact("sah8013");
    runFlowAssertResponse(loadFile("contact_check_response.xml"));
  }

  @TestOfyAndSql
  void test50IdsAllowed() throws Exception {
    // Make sure we don't have a regression that reduces the number of allowed checks.
    setEppInput("contact_check_50.xml");
    runFlow();
  }

  @TestOfyAndSql
  void testTooManyIds() {
    setEppInput("contact_check_51.xml");
    EppException thrown = assertThrows(TooManyResourceChecksException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testIcannActivityReportField_getsLogged() throws Exception {
    runFlow();
    assertIcannReportingActivityFieldLogged("srs-cont-check");
  }
}
