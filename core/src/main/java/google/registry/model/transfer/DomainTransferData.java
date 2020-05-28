// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.transfer;

import com.googlecode.objectify.annotation.Ignore;
import javax.persistence.Column;
import javax.persistence.Embeddable;

@Embeddable
public class DomainTransferData {
  @Ignore
  @Column(name = "transfer_server_approve_billing_event_id")
  Long billingEventId;

  @Ignore
  @Column(name = "transfer_server_approve_billing_recurrence_id")
  Long billingRecurrenceId;

  @Ignore
  @Column(name = "transfer_server_approve_billing_cancellation_id")
  Long billingCancellationId;

  @Ignore
  @Column(name = "transfer_server_approve_autorenew_poll_message_id")
  Long gainingAutorenewPollMessageId;
}
