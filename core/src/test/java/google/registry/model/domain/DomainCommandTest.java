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

package google.registry.model.domain;

import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.DatastoreHelper.persistActiveHost;

import google.registry.model.ResourceCommandTestCase;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppinput.EppInput.ResourceCommandWrapper;
import google.registry.model.eppinput.ResourceCommand;
import google.registry.testing.EppLoader;
import org.junit.Test;

/** Tests for DomainCommand. */
public class DomainCommandTest extends ResourceCommandTestCase {

  @Test
  public void testCreate() throws Exception {
    doXmlRoundtripTest("domain_create.xml");
  }

  @Test
  public void testCreate_sunriseSignedMark() throws Exception {
    doXmlRoundtripTest("domain_create_sunrise_signed_mark.xml");
  }

  @Test
  public void testCreate_sunriseCode() throws Exception {
    doXmlRoundtripTest("domain_create_sunrise_code.xml");
  }

  @Test
  public void testCreate_sunriseMark() throws Exception {
    doXmlRoundtripTest("domain_create_sunrise_mark.xml");
  }

  @Test
  public void testCreate_sunriseCodeWithMark() throws Exception {
    doXmlRoundtripTest("domain_create_sunrise_code_with_mark.xml");
  }

  @Test
  public void testCreate_sunriseEncodedSignedMark() throws Exception {
    doXmlRoundtripTest("domain_create_sunrise_encoded_signed_mark.xml");
  }

  @Test
  public void testCreate_fee() throws Exception {
    doXmlRoundtripTest("domain_create_fee.xml");
  }

  @Test
  public void testCreate_emptyCommand() throws Exception {
    // This EPP command wouldn't be allowed for policy reasons, but should marshal/unmarshal fine.
    doXmlRoundtripTest("domain_create_empty.xml");
  }

  @Test
  public void testCreate_missingNonRegistrantContacts() throws Exception {
    // This EPP command wouldn't be allowed for policy reasons, but should marshal/unmarshal fine.
    doXmlRoundtripTest("domain_create_missing_non_registrant_contacts.xml");
  }

  @Test
  public void testCreate_cloneAndLinkReferences() throws Exception {
    persistActiveHost("ns1.example.net");
    persistActiveHost("ns2.example.net");
    persistActiveContact("sh8013");
    persistActiveContact("jd1234");
    DomainCommand.Create create =
        (DomainCommand.Create) loadEppResourceCommand("domain_create.xml");
    create.cloneAndLinkReferences(fakeClock.nowUtc());
  }

  @Test
  public void testCreate_emptyCommand_cloneAndLinkReferences() throws Exception {
    // This EPP command wouldn't be allowed for policy reasons, but should clone-and-link fine.
    DomainCommand.Create create =
        (DomainCommand.Create) loadEppResourceCommand("domain_create_empty.xml");
    create.cloneAndLinkReferences(fakeClock.nowUtc());
  }

  @Test
  public void testCreate_missingNonRegistrantContacts_cloneAndLinkReferences() throws Exception {
    persistActiveContact("jd1234");
    // This EPP command wouldn't be allowed for policy reasons, but should clone-and-link fine.
    DomainCommand.Create create =
        (DomainCommand.Create)
            loadEppResourceCommand("domain_create_missing_non_registrant_contacts.xml");
    create.cloneAndLinkReferences(fakeClock.nowUtc());
  }

  @Test
  public void testDelete() throws Exception {
    doXmlRoundtripTest("domain_delete.xml");
  }

  @Test
  public void testUpdate() throws Exception {
    doXmlRoundtripTest("domain_update.xml");
  }

  @Test
  public void testUpdate_fee() throws Exception {
    doXmlRoundtripTest("domain_update_fee.xml");
  }

  @Test
  public void testUpdate_emptyCommand() throws Exception {
    // This EPP command wouldn't be allowed for policy reasons, but should marshal/unmarshal fine.
    doXmlRoundtripTest("domain_update_empty.xml");
  }

  @Test
  public void testUpdate_cloneAndLinkReferences() throws Exception {
    persistActiveHost("ns1.example.com");
    persistActiveHost("ns2.example.com");
    persistActiveContact("mak21");
    persistActiveContact("sh8013");
    DomainCommand.Update update =
        (DomainCommand.Update) loadEppResourceCommand("domain_update.xml");
    update.cloneAndLinkReferences(fakeClock.nowUtc());
  }

  @Test
  public void testUpdate_emptyCommand_cloneAndLinkReferences() throws Exception {
    // This EPP command wouldn't be allowed for policy reasons, but should clone-and-link fine.
    DomainCommand.Update update =
        (DomainCommand.Update) loadEppResourceCommand("domain_update_empty.xml");
    update.cloneAndLinkReferences(fakeClock.nowUtc());
  }

  @Test
  public void testInfo() throws Exception {
    doXmlRoundtripTest("domain_info.xml");
  }

  @Test
  public void testInfo_sunrise() throws Exception {
    doXmlRoundtripTest("domain_info_sunrise.xml");
  }

  @Test
  public void testInfo_feeExtension() throws Exception {
    doXmlRoundtripTest("domain_info_fee.xml");
  }

  @Test
  public void testCheck() throws Exception {
    doXmlRoundtripTest("domain_check.xml");
  }

  @Test
  public void testCheck_avail() throws Exception {
    doXmlRoundtripTest("domain_check_avail.xml");
  }

  @Test
  public void testCheck_claims() throws Exception {
    doXmlRoundtripTest("domain_check_claims.xml");
  }

  @Test
  public void testCheck_fee() throws Exception {
    doXmlRoundtripTest("domain_check_fee.xml");
  }

  @Test
  public void testTransferApprove() throws Exception {
    doXmlRoundtripTest("domain_transfer_approve.xml");
  }

  @Test
  public void testTransferReject() throws Exception {
    doXmlRoundtripTest("domain_transfer_reject.xml");
  }

  @Test
  public void testTransferCancel() throws Exception {
    doXmlRoundtripTest("domain_transfer_cancel.xml");
  }

  @Test
  public void testTransferQuery() throws Exception {
    doXmlRoundtripTest("domain_transfer_query.xml");
  }

  @Test
  public void testTransferRequest() throws Exception {
    doXmlRoundtripTest("domain_transfer_request.xml");
  }

  @Test
  public void testTransferRequest_fee() throws Exception {
    doXmlRoundtripTest("domain_transfer_request_fee.xml");
  }

  @Test
  public void testRenew() throws Exception {
    doXmlRoundtripTest("domain_renew.xml");
  }

  @Test
  public void testRenew_fee() throws Exception {
    doXmlRoundtripTest("domain_renew_fee.xml");
  }

  /** Loads the EppInput from the given filename and returns its ResourceCommand element. */
  private ResourceCommand loadEppResourceCommand(String filename) throws Exception {
    EppInput eppInput = new EppLoader(this, filename).getEpp();
    return ((ResourceCommandWrapper) eppInput.getCommandWrapper().getCommand())
        .getResourceCommand();
  }
}
