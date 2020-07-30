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
import static google.registry.testing.TestDataHelper.loadFile;
import static google.registry.xjc.XjcXmlTransformer.unmarshal;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.xjc.epp.XjcEpp;
import google.registry.xjc.rde.XjcRdeDeposit;
import google.registry.xml.XmlException;
import java.io.ByteArrayInputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit tests that ensure {@link XjcObject} is able to unmarshal XML in {@code testdata/} and ensure
 * they conform to the XML schema definitions.
 */
class XmlTestdataTest {

  @ParameterizedTest
  @MethodSource("provideTestCombinations")
  void testValid(String filename, Class<? extends XjcObject> expectedClazz) throws Exception {
    XjcObject xml = unmarshal(XjcObject.class, loadAsStream(filename));
    assertThat(xml).isInstanceOf(expectedClazz);
  }

  @Test
  void testInvalid() {
    ByteArrayInputStream badData = loadAsStream("invalid_greeting.xml");
    XmlException thrown =
        assertThrows(XmlException.class, () -> unmarshal(XjcObject.class, badData));
    assertThat(thrown).hasMessageThat().contains("dcp}' is expected");
  }

  @SuppressWarnings("unused")
  static Stream<Arguments> provideTestCombinations() {
    return Stream.of(
        Arguments.of("contact_check_response.xml", XjcEpp.class),
        Arguments.of("contact_check.xml", XjcEpp.class),
        Arguments.of("contact_create_response_offline_review_completed.xml", XjcEpp.class),
        Arguments.of("contact_create_response_offline_review.xml", XjcEpp.class),
        Arguments.of("contact_create_response.xml", XjcEpp.class),
        Arguments.of("contact_create.xml", XjcEpp.class),
        Arguments.of("contact_delete_response.xml", XjcEpp.class),
        Arguments.of("contact_delete.xml", XjcEpp.class),
        Arguments.of("contact_info_response.xml", XjcEpp.class),
        Arguments.of("contact_info.xml", XjcEpp.class),
        Arguments.of("contact_transfer_query_response.xml", XjcEpp.class),
        Arguments.of("contact_transfer_query.xml", XjcEpp.class),
        Arguments.of("contact_transfer_request_response.xml", XjcEpp.class),
        Arguments.of("contact_transfer_request.xml", XjcEpp.class),
        Arguments.of("contact_update.xml", XjcEpp.class),
        Arguments.of("domain_check_response.xml", XjcEpp.class),
        Arguments.of("domain_check.xml", XjcEpp.class),
        Arguments.of("domain_create_response_offline_review_completed.xml", XjcEpp.class),
        Arguments.of("domain_create_response_offline_review.xml", XjcEpp.class),
        Arguments.of("domain_create_response.xml", XjcEpp.class),
        Arguments.of("domain_create.xml", XjcEpp.class),
        Arguments.of("domain_delete.xml", XjcEpp.class),
        Arguments.of("domain_info_response_addperiod.xml", XjcEpp.class),
        Arguments.of("domain_info_response_pendingdelete.xml", XjcEpp.class),
        Arguments.of("domain_info_response_pendingrestore.xml", XjcEpp.class),
        Arguments.of("domain_info_response_redemptionperiod.xml", XjcEpp.class),
        Arguments.of("domain_info_response_unauthorized.xml", XjcEpp.class),
        Arguments.of("domain_info_response.xml", XjcEpp.class),
        Arguments.of("domain_info_with_auth.xml", XjcEpp.class),
        Arguments.of("domain_info.xml", XjcEpp.class),
        Arguments.of("domain_renew_response.xml", XjcEpp.class),
        Arguments.of("domain_renew.xml", XjcEpp.class),
        Arguments.of("domain_transfer_query_response.xml", XjcEpp.class),
        Arguments.of("domain_transfer_query.xml", XjcEpp.class),
        Arguments.of("domain_transfer_request_response.xml", XjcEpp.class),
        Arguments.of("domain_transfer_request.xml", XjcEpp.class),
        Arguments.of("domain_update_restore_report.xml", XjcEpp.class),
        Arguments.of("domain_update_restore_request.xml", XjcEpp.class),
        Arguments.of("domain_update.xml", XjcEpp.class),
        Arguments.of("generic_success_response.xml", XjcEpp.class),
        Arguments.of("greeting.xml", XjcEpp.class),
        Arguments.of("host_check_response.xml", XjcEpp.class),
        Arguments.of("host_check.xml", XjcEpp.class),
        Arguments.of("host_create_response_offline_review_complete.xml", XjcEpp.class),
        Arguments.of("host_create_response_offline_review.xml", XjcEpp.class),
        Arguments.of("host_create_response.xml", XjcEpp.class),
        Arguments.of("host_create.xml", XjcEpp.class),
        Arguments.of("host_delete_response.xml", XjcEpp.class),
        Arguments.of("host_delete.xml", XjcEpp.class),
        Arguments.of("host_info_response.xml", XjcEpp.class),
        Arguments.of("host_info.xml", XjcEpp.class),
        Arguments.of("host_update.xml", XjcEpp.class),
        Arguments.of("login.xml", XjcEpp.class),
        Arguments.of("logout_response.xml", XjcEpp.class),
        Arguments.of("logout.xml", XjcEpp.class),
        Arguments.of("poll_ack_response.xml", XjcEpp.class),
        Arguments.of("poll_ack.xml", XjcEpp.class),
        Arguments.of("poll_response_empty.xml", XjcEpp.class),
        Arguments.of("poll_response_mixed.xml", XjcEpp.class),
        Arguments.of("poll.xml", XjcEpp.class),
        Arguments.of("rde_deposit_differential.xml", XjcRdeDeposit.class),
        Arguments.of("rde_deposit_full.xml", XjcRdeDeposit.class),
        Arguments.of("restore_request_response.xml", XjcEpp.class));
  }

  private static ByteArrayInputStream loadAsStream(String filename) {
    return new ByteArrayInputStream(loadFile(XmlTestdataTest.class, filename).getBytes(UTF_8));
  }
}
