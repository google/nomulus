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

package google.registry.xjc;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.xjc.XjcXmlTransformer.unmarshal;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import google.registry.testing.TestDataHelper;
import google.registry.xjc.epp.XjcEpp;
import google.registry.xjc.rde.XjcRdeDeposit;
import java.io.ByteArrayInputStream;
import org.junit.experimental.theories.DataPoints;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.runner.RunWith;

/**
 * Unit tests that ensure {@link XjcObject} is able to unmarshal XML in {@code testdata/} and
 * ensure they conform to the XML schema definitions.
 */
@RunWith(Theories.class)
public class XmlTestdataTest {
  private static class Example {
    final ByteArrayInputStream xmlStream;

    private Example(String filename) {
      this.xmlStream = new ByteArrayInputStream(
          TestDataHelper.loadFile(XmlTestdataTest.class, filename).getBytes(UTF_8));
    }
  }

  private static class Good extends Example {
    final Class<?> clazz;

    private Good(String filename, Class<?> clazz) {
      super(filename);
      this.clazz = clazz;
    }
  }

  private static class Evil extends Example {
    final String error;

    private Evil(String filename, String error) {
      super(filename);
      this.error = error;
    }
  }

  @DataPoints
  public static final Good[] GOOD = new Good[] {
    new Good("contact_check_response.xml", XjcEpp.class),
    new Good("contact_check.xml", XjcEpp.class),
    new Good("contact_create_response_offline_review_completed.xml", XjcEpp.class),
    new Good("contact_create_response_offline_review.xml", XjcEpp.class),
    new Good("contact_create_response.xml", XjcEpp.class),
    new Good("contact_create.xml", XjcEpp.class),
    new Good("contact_delete_response.xml", XjcEpp.class),
    new Good("contact_delete.xml", XjcEpp.class),
    new Good("contact_info_response.xml", XjcEpp.class),
    new Good("contact_info.xml", XjcEpp.class),
    new Good("contact_transfer_query_response.xml", XjcEpp.class),
    new Good("contact_transfer_query.xml", XjcEpp.class),
    new Good("contact_transfer_request_response.xml", XjcEpp.class),
    new Good("contact_transfer_request.xml", XjcEpp.class),
    new Good("contact_update.xml", XjcEpp.class),
    new Good("domain_check_response.xml", XjcEpp.class),
    new Good("domain_check.xml", XjcEpp.class),
    new Good("domain_create_response_offline_review_completed.xml", XjcEpp.class),
    new Good("domain_create_response_offline_review.xml", XjcEpp.class),
    new Good("domain_create_response.xml", XjcEpp.class),
    new Good("domain_create.xml", XjcEpp.class),
    new Good("domain_delete.xml", XjcEpp.class),
    new Good("domain_info_response_addperiod.xml", XjcEpp.class),
    new Good("domain_info_response_pendingdelete.xml", XjcEpp.class),
    new Good("domain_info_response_pendingrestore.xml", XjcEpp.class),
    new Good("domain_info_response_redemptionperiod.xml", XjcEpp.class),
    new Good("domain_info_response_unauthorized.xml", XjcEpp.class),
    new Good("domain_info_response.xml", XjcEpp.class),
    new Good("domain_info_with_auth.xml", XjcEpp.class),
    new Good("domain_info.xml", XjcEpp.class),
    new Good("domain_renew_response.xml", XjcEpp.class),
    new Good("domain_renew.xml", XjcEpp.class),
    new Good("domain_transfer_query_response.xml", XjcEpp.class),
    new Good("domain_transfer_query.xml", XjcEpp.class),
    new Good("domain_transfer_request_response.xml", XjcEpp.class),
    new Good("domain_transfer_request.xml", XjcEpp.class),
    new Good("domain_update_restore_report.xml", XjcEpp.class),
    new Good("domain_update_restore_request.xml", XjcEpp.class),
    new Good("domain_update.xml", XjcEpp.class),
    new Good("generic_success_response.xml", XjcEpp.class),
    new Good("greeting.xml", XjcEpp.class),
    new Good("host_check_response.xml", XjcEpp.class),
    new Good("host_check.xml", XjcEpp.class),
    new Good("host_create_response_offline_review_complete.xml", XjcEpp.class),
    new Good("host_create_response_offline_review.xml", XjcEpp.class),
    new Good("host_create_response.xml", XjcEpp.class),
    new Good("host_create.xml", XjcEpp.class),
    new Good("host_delete_response.xml", XjcEpp.class),
    new Good("host_delete.xml", XjcEpp.class),
    new Good("host_info_response.xml", XjcEpp.class),
    new Good("host_info.xml", XjcEpp.class),
    new Good("host_update.xml", XjcEpp.class),
    new Good("login.xml", XjcEpp.class),
    new Good("logout_response.xml", XjcEpp.class),
    new Good("logout.xml", XjcEpp.class),
    new Good("poll_ack_response.xml", XjcEpp.class),
    new Good("poll_ack.xml", XjcEpp.class),
    new Good("poll_response_empty.xml", XjcEpp.class),
    new Good("poll_response_mixed.xml", XjcEpp.class),
    new Good("poll.xml", XjcEpp.class),
    new Good("rde_deposit_differential.xml", XjcRdeDeposit.class),
    new Good("rde_deposit_full.xml", XjcRdeDeposit.class),
    new Good("restore_request_response.xml", XjcEpp.class),
  };

  @DataPoints
  public static final Evil[] EVIL = new Evil[] {
    new Evil("invalid_greeting.xml", "dcp}' is expected"),
  };

  @Theory
  public void testValid(Good v) throws Exception {
    XjcObject xml = unmarshal(XjcObject.class, v.xmlStream);
    assertThat(xml).isInstanceOf(v.clazz);
  }

  @Theory
  public void testInvalid(Evil v) {
    Throwable thrown = assertThrows(Throwable.class, () -> unmarshal(XjcObject.class, v.xmlStream));
    assertThat(thrown).hasMessageThat().contains(v.error);
  }
}
