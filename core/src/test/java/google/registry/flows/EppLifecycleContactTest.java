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

package google.registry.flows;

import static google.registry.model.eppoutput.Result.Code.SUCCESS;
import static google.registry.model.eppoutput.Result.Code.SUCCESS_WITH_ACK_MESSAGE;
import static google.registry.model.eppoutput.Result.Code.SUCCESS_WITH_ACTION_PENDING;
import static google.registry.model.eppoutput.Result.Code.SUCCESS_WITH_NO_MESSAGES;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.EppMetricSubject.assertThat;

import com.google.common.collect.ImmutableMap;
import google.registry.model.eppoutput.Result;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.TestOfyAndSql;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for contact lifecycle. */
@DualDatabaseTest
class EppLifecycleContactTest extends EppTestCase {

  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder()
          .withDatastoreAndCloudSql()
          .withClock(clock)
          .withTaskQueue()
          .build();

  @TestOfyAndSql
  void testContactLifecycle() throws Exception {
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    assertThatCommand("contact_create_sh8013.xml")
        .atTime("2000-06-01T00:00:00Z")
        .hasResponse(
            "contact_create_response_sh8013.xml",
            ImmutableMap.of("CRDATE", "2000-06-01T00:00:00Z"));
    assertThat(getRecordedEppMetric())
        .hasClientId("NewRegistrar")
        .and()
        .hasNoTld()
        .and()
        .hasCommandName("ContactCreate")
        .and()
        .hasStatus(SUCCESS);
    assertThatCommand("contact_info.xml")
        .atTime("2000-06-01T00:01:00Z")
        .hasResponse("contact_info_from_create_response.xml");
    assertThat(getRecordedEppMetric())
        .hasClientId("NewRegistrar")
        .and()
        .hasCommandName("ContactInfo")
        .and()
        .hasStatus(SUCCESS);
    Result.Code resultCode;
    if (tm().isOfy()) {
      assertThatCommand("contact_delete_sh8013.xml")
          .hasResponse("contact_delete_response_sh8013_pending.xml");
      resultCode = SUCCESS_WITH_ACTION_PENDING;
    } else {
      assertThatCommand("contact_delete_sh8013.xml")
          .hasResponse("contact_delete_response_sh8013.xml");
      resultCode = SUCCESS;
    }
    assertThat(getRecordedEppMetric())
        .hasClientId("NewRegistrar")
        .and()
        .hasCommandName("ContactDelete")
        .and()
        .hasStatus(resultCode);
    assertThatLogoutSucceeds();
  }

  @TestOfyAndSql
  void testContactTransferPollMessage() throws Exception {
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    assertThatCommand("contact_create_sh8013.xml")
        .atTime("2000-06-01T00:00:00Z")
        .hasResponse(
            "contact_create_response_sh8013.xml",
            ImmutableMap.of("CRDATE", "2000-06-01T00:00:00Z"));
    assertThatLogoutSucceeds();

    // Initiate a transfer of the newly created contact.
    assertThatLoginSucceeds("TheRegistrar", "password2");
    assertThatCommand("contact_transfer_request.xml")
        .atTime("2000-06-08T22:00:00Z")
        .hasResponse("contact_transfer_request_response_alternate.xml");
    assertThatLogoutSucceeds();

    // Log back in with the losing registrar, read the poll message, and then ack it.
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    assertThatCommand("poll.xml")
        .atTime("2000-06-08T22:01:00Z")
        .hasResponse("poll_response_contact_transfer.xml");
    assertThat(getRecordedEppMetric())
        .hasClientId("NewRegistrar")
        .and()
        .hasCommandName("PollRequest")
        .and()
        .hasStatus(SUCCESS_WITH_ACK_MESSAGE);
    assertThatCommand("poll_ack.xml", ImmutableMap.of("ID", "2-1-ROID-3-6-2000"))
        .atTime("2000-06-08T22:02:00Z")
        .hasResponse("poll_ack_response_empty.xml");
    assertThat(getRecordedEppMetric())
        .hasClientId("NewRegistrar")
        .and()
        .hasCommandName("PollAck")
        .and()
        .hasStatus(SUCCESS_WITH_NO_MESSAGES);
    assertThatLogoutSucceeds();
  }
}
