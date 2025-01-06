// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.bsa;

import static com.google.common.base.Throwables.getStackTraceAsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import google.registry.bsa.api.BsaReportSender;
import google.registry.bsa.persistence.RefreshScheduler;
import google.registry.groups.GmailClient;
import google.registry.request.Response;
import google.registry.testing.FakeClock;
import google.registry.util.EmailMessage;
import jakarta.mail.internet.InternetAddress;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link BsaRefreshAction}. */
@ExtendWith(MockitoExtension.class)
public class BsaRefreshActionTest {

  FakeClock fakeClock = new FakeClock(DateTime.parse("2023-11-09T02:08:57.880Z"));

  @Mock RefreshScheduler scheduler;

  @Mock GmailClient gmailClient;

  @Mock private InternetAddress emailRecipient;

  @Mock Response response;

  @Mock private BsaLock bsaLock;

  @Mock private GcsClient gcsClient;

  @Mock private BsaReportSender bsaReportSender;

  BsaRefreshAction action;

  @BeforeEach
  void setup() {
    action =
        new BsaRefreshAction(
            scheduler,
            gcsClient,
            bsaReportSender,
            /* transactionBatchSize= */ 5,
            /* domainCreateTxnCommitTimeLag= */ Duration.millis(1),
            new BsaEmailSender(gmailClient, emailRecipient),
            bsaLock,
            fakeClock,
            response);
  }

  @Test
  void notificationSent_cannotAcquireLock() {
    when(bsaLock.executeWithLock(any())).thenReturn(false);
    action.run();
    verify(gmailClient, times(1))
        .sendEmail(
            EmailMessage.create(
                "BSA refresh did not run: another BSA related task is running",
                "",
                emailRecipient));
  }

  @Test
  void notificationSent_abortedByException() {
    RuntimeException throwable = new RuntimeException("Error");
    when(bsaLock.executeWithLock(any())).thenThrow(throwable);
    action.run();
    verify(gmailClient, times(1))
        .sendEmail(
            EmailMessage.create(
                "BSA refresh aborted", getStackTraceAsString(throwable), emailRecipient));
  }

  @Test
  void notification_notSent_whenNoError() {
    when(bsaLock.executeWithLock(any()))
        .thenAnswer(
            args -> {
              return true;
            });
    action.run();
    verify(gmailClient, never()).sendEmail(any());
  }
}
