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

package google.registry.model.common;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import google.registry.testing.AppEngineRule;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link GaeUserIdConverter}. */
@RunWith(JUnit4.class)
public class GaeUserIdConverterTest {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .build();

  @After
  public void verifyNoLingeringEntities() {
    assertThat(ofy().load().type(GaeUserIdConverter.class).count()).isEqualTo(0);
  }

  @Test
  public void testSuccess() {
    assertThat(GaeUserIdConverter.convertEmailAddressToGaeUserId("example@example.com"))
        .matches("[0-9]+");
  }

  @Test
  public void testSuccess_inTransaction() {
    tm()
        .transactNew(
            () ->
                assertThat(GaeUserIdConverter.convertEmailAddressToGaeUserId("example@example.com"))
                    .matches("[0-9]+"));
  }
}
