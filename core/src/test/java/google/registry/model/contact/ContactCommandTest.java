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

import static google.registry.model.eppcommon.EppXmlTransformer.marshalInput;
import static google.registry.model.eppcommon.EppXmlTransformer.validateInput;
import static google.registry.xml.ValidationMode.LENIENT;
import static google.registry.xml.XmlTestUtils.assertXmlEquals;
import static java.nio.charset.StandardCharsets.UTF_8;

import google.registry.testing.AppEngineRule;
import google.registry.testing.EppLoader;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Test xml roundtripping of commands. */
@RunWith(JUnit4.class)
public class ContactCommandTest {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder().withDatastoreAndCloudSql().build();

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
  public void testCreate() throws Exception {
    doXmlRoundtripTest("contact_create.xml");
  }

  @Test
  public void testDelete() throws Exception {
    doXmlRoundtripTest("contact_delete.xml");
  }

  @Test
  public void testUpdate() throws Exception {
    doXmlRoundtripTest("contact_update.xml");
  }

  @Test
  public void testInfo() throws Exception {
    doXmlRoundtripTest("contact_info.xml");
  }

  @Test
  public void testCheck() throws Exception {
    doXmlRoundtripTest("contact_check.xml");
  }

  @Test
  public void testTransferApprove() throws Exception {
    doXmlRoundtripTest("contact_transfer_approve.xml");
  }

  @Test
  public void testTransferReject() throws Exception {
    doXmlRoundtripTest("contact_transfer_reject.xml");
  }

  @Test
  public void testTransferCancel() throws Exception {
    doXmlRoundtripTest("contact_transfer_cancel.xml");
  }

  @Test
  public void testTransferQuery() throws Exception {
    doXmlRoundtripTest("contact_transfer_query.xml");
  }

  @Test
  public void testTransferRequest() throws Exception {
    doXmlRoundtripTest("contact_transfer_request.xml");
  }
}
