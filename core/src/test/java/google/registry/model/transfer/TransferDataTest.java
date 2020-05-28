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

package google.registry.model.transfer;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.model.billing.BillingEvent;
import google.registry.model.domain.Period;
import google.registry.model.eppcommon.Trid;
import google.registry.model.poll.PollMessage;
import google.registry.model.transfer.TransferData.TransferServerApproveEntity;
import google.registry.persistence.VKey;
import google.registry.testing.AppEngineRule;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link TransferData}. */
@RunWith(JUnit4.class)
public class TransferDataTest {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder().withDatastoreAndCloudSql().build();

  private final DateTime now = DateTime.now(UTC);

  private Key<BillingEvent.OneTime> transferBillingEventKey;
  private Key<BillingEvent.Cancellation> otherServerApproveBillingEventKey;
  private Key<BillingEvent.Recurring> recurringBillingEventKey;
  private Key<PollMessage.Autorenew> autorenewPollMessageKey;
  private Key<PollMessage.OneTime> otherServerApprovePollMessageKey;

  @Before
  public void setUp() {
    transferBillingEventKey = Key.create(BillingEvent.OneTime.class, 12345);
    otherServerApproveBillingEventKey = Key.create(BillingEvent.Cancellation.class, 2468);
    recurringBillingEventKey = Key.create(BillingEvent.Recurring.class, 13579);
    autorenewPollMessageKey = Key.create(PollMessage.Autorenew.class, 67890);
    otherServerApprovePollMessageKey = Key.create(PollMessage.OneTime.class, 314159);
  }

  @Test
  public void test_copyConstantFieldsToBuilder() {
    TransferData constantTransferData =
        new TransferData.Builder()
            .setTransferRequestTrid(Trid.create("server-trid", "client-trid"))
            .setTransferRequestTime(now)
            .setGainingClientId("NewRegistrar")
            .setLosingClientId("TheRegistrar")
            // Test must use a non-1-year period, since that's the default value.
            .setTransferPeriod(Period.create(5, Period.Unit.YEARS))
            .build();
    TransferData fullTransferData =
        constantTransferData
            .asBuilder()
            .setPendingTransferExpirationTime(now)
            .setTransferStatus(TransferStatus.PENDING)
            .setServerApproveEntities(
                ImmutableSet.of(
                        transferBillingEventKey,
                        otherServerApproveBillingEventKey,
                        recurringBillingEventKey,
                        autorenewPollMessageKey,
                        otherServerApprovePollMessageKey)
                    .stream()
                    .map(TransferServerApproveEntity::createVKey)
                    .collect(toImmutableSet()))
            .setServerApproveBillingEvent(
                VKey.createOfy(BillingEvent.OneTime.class, transferBillingEventKey))
            .setServerApproveAutorenewEvent(
                VKey.createOfy(BillingEvent.Recurring.class, recurringBillingEventKey))
            .setServerApproveAutorenewPollMessage(
                VKey.createOfy(PollMessage.Autorenew.class, autorenewPollMessageKey))
            .build();
    // asBuilder() copies over all fields
    assertThat(fullTransferData.asBuilder().build()).isEqualTo(fullTransferData);
    // copyConstantFieldsToBuilder() copies only constant fields
    assertThat(fullTransferData.copyConstantFieldsToBuilder().build())
        .isEqualTo(constantTransferData);
  }
}
