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

package google.registry.model.contact;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.eppcommon.EppXmlTransformer.marshalInput;
import static google.registry.model.eppcommon.EppXmlTransformer.validateInput;
import static google.registry.xml.ValidationMode.LENIENT;
import static google.registry.xml.XmlTestUtils.assertXmlEquals;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import google.registry.model.contact.ContactCommand.Update;
import google.registry.model.contact.ContactCommand.Update.Change;
import google.registry.model.eppinput.EppInput.ResourceCommandWrapper;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.EppLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Test xml roundtripping of commands. */
public class ContactCommandTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  private void doXmlRoundtripTest(String inputFilename) throws Exception {
    EppLoader eppLoader = new EppLoader(this, inputFilename);
    // JAXB can unmarshal a "name" or an "id" into the "targetId" field, but when marshaling it
    // chooses "name" always since it is last on the list of @XmlElement choices on that field. This
    // is fine because we never marshal an input command... except for this test which verifies
    // roundtripping, so we hack the output here. Since the marshal step won't validate, we use
    // the non-validating lenient marshal, do the change, and then do the validate afterwards.
    String marshaled = new String(marshalInput(eppLoader.getEpp(), LENIENT), UTF_8).replaceAll(
        "<contact:name>(sh8013|sah8013|8013sah)</contact:name>",
        "<contact:id>$1</contact:id>");
    validateInput(marshaled);
    assertXmlEquals(eppLoader.getEppXml(), marshaled);
  }

  @Test
  void testCreate() throws Exception {
    doXmlRoundtripTest("contact_create.xml");
  }

  @Test
  void testDelete() throws Exception {
    doXmlRoundtripTest("contact_delete.xml");
  }

  @Test
  void testUpdate() throws Exception {
    doXmlRoundtripTest("contact_update.xml");
  }

  @Test
  void testUpdate_individualStreetFieldsGetPopulatedCorrectly() throws Exception {
    EppLoader eppLoader = new EppLoader(this, "contact_update.xml");
    Update command =
        (Update)
            ((ResourceCommandWrapper) (eppLoader.getEpp().getCommandWrapper().getCommand()))
                .getResourceCommand();
    Change change = command.getInnerChange();
    assertThat(change.getInternationalizedPostalInfo().getAddress())
        .isEqualTo(
            new ContactAddress.Builder()
                .setCity("Dulles")
                .setCountryCode("US")
                .setState("VA")
                .setZip("20166-6503")
                .setStreet(
                    ImmutableList.of(
                        "124 Example Dr.",
                        "Suite 200")) // streetLine1 and streetLine2 get set inside the builder
                .build());
  }

  @Test
  void testInfo() throws Exception {
    doXmlRoundtripTest("contact_info.xml");
  }

  @Test
  void testCheck() throws Exception {
    doXmlRoundtripTest("contact_check.xml");
  }

  @Test
  void testTransferApprove() throws Exception {
    doXmlRoundtripTest("contact_transfer_approve.xml");
  }

  @Test
  void testTransferReject() throws Exception {
    doXmlRoundtripTest("contact_transfer_reject.xml");
  }

  @Test
  void testTransferCancel() throws Exception {
    doXmlRoundtripTest("contact_transfer_cancel.xml");
  }

  @Test
  void testTransferQuery() throws Exception {
    doXmlRoundtripTest("contact_transfer_query.xml");
  }

  @Test
  void testTransferRequest() throws Exception {
    doXmlRoundtripTest("contact_transfer_request.xml");
  }
}
