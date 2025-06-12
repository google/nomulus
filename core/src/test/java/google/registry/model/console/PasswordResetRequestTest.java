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

package google.registry.model.console;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.junit.Assert.assertThrows;

import google.registry.model.EntityTestCase;
import google.registry.persistence.VKey;
import google.registry.testing.DatabaseHelper;
import org.junit.jupiter.api.Test;

/** Tests for {@link PasswordResetRequest}. */
public class PasswordResetRequestTest extends EntityTestCase {

  PasswordResetRequestTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @Test
  void testSuccess_persistence() {
    PasswordResetRequest request =
        new PasswordResetRequest.Builder()
            .setRequester("requestor@email.tld")
            .setDestinationEmail("destination@email.tld")
            .setType(PasswordResetRequest.Type.EPP)
            .setRegistrarId("TheRegistrar")
            .build();
    String verificationCode = request.getVerificationCode();
    assertThat(verificationCode).isNotEmpty();
    persistResource(request);
    PasswordResetRequest fromDatabase =
        DatabaseHelper.loadByKey(VKey.create(PasswordResetRequest.class, verificationCode));
    assertAboutImmutableObjects().that(fromDatabase).isEqualExceptFields(request, "requestTime");
    assertThat(fromDatabase.getRequestTime()).isEqualTo(fakeClock.nowUtc());
  }

  @Test
  void testFailure_nullFields() {
    PasswordResetRequest.Builder builder = new PasswordResetRequest.Builder();
    assertThrows(IllegalArgumentException.class, builder::build);
    builder.setType(PasswordResetRequest.Type.EPP);
    assertThrows(IllegalArgumentException.class, builder::build);
    builder.setRequester("foobar@email.tld");
    assertThrows(IllegalArgumentException.class, builder::build);
    builder.setDestinationEmail("email@email.tld");
    assertThrows(IllegalArgumentException.class, builder::build);
    builder.setRegistrarId("TheRegistrar");
    builder.build();
  }
}
