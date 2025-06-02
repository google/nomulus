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

package google.registry.ui.server.console;

import static google.registry.testing.DatabaseHelper.createAdminUser;
import static google.registry.testing.DatabaseHelper.createTld;

import com.google.gson.Gson;
import google.registry.model.console.User;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.auth.AuthResult;
import google.registry.testing.ConsoleApiParamsUtils;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.tools.GsonUtils;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

public abstract class ConsoleActionBaseTestCase {

  protected static final Gson GSON = GsonUtils.provideGson();

  protected final FakeClock clock = new FakeClock(DateTime.parse("2024-04-15T00:00:00.000Z"));

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  protected ConsoleApiParams consoleApiParams;
  protected FakeResponse response;
  protected User fteUser;

  @BeforeEach
  void beforeEachBaseTestCase() {
    createTld("tld");
    fteUser = createAdminUser("fte@email.tld");
    AuthResult authResult = AuthResult.createUser(fteUser);
    consoleApiParams = ConsoleApiParamsUtils.createFake(authResult);
    response = (FakeResponse) consoleApiParams.response();
  }
}
