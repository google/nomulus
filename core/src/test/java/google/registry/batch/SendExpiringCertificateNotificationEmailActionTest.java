// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.batch;

import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.InjectExtension;
import google.registry.util.SendEmailService;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;

class SendExpiringCertificateNotificationEmailActionTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().withTaskQueue().build();

  @RegisterExtension
  public final InjectExtension inject = new InjectExtension();

  private final FakeClock clock = new FakeClock(DateTime.parse("2021-05-24T20:21:22Z"));
  private final FakeResponse response = new FakeResponse();
  private InternetAddress address;
  private SendExpiringCertificateNotificationEmailAction action;
  @Mock
  private SendEmailService sendEmailService;

  @BeforeEach
  void beforeEach() throws AddressException {
    address = new InternetAddress("test@example.com");
  }

  @Test
  void getEmailAddresses_success_returnsAListOfEmails() {

  }

  @Test
  void getEmailAddresses_success_returnsAnEmptyList() {

  }

  @Test
  void getEmailAddresses_failure_Exception() {

  }

  //
  @Test
  void sendNotification_success() {
    action.run();
  }

  @Test
  void sendNotification_failure() {

  }

  //Test email content
  @Test
  void getEmailBody_containsTwOCertificates () {

  }

  @Test
  void getEmailBody_containsOneCertificate () {

  }

  @Test
  void getEmailBody_returnsEmptyString () {

  }

}
