// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

package google.registry.testing;

import com.google.appengine.tools.development.testing.LocalServiceTestHelper;
import com.google.appengine.tools.development.testing.LocalUserServiceTestConfig;
import com.google.apphosting.api.ApiProxy;
import google.registry.model.annotations.DeleteAfterMigration;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** JUnit extension that sets up App Engine User service environment. */
@DeleteAfterMigration
public final class UserServiceExtension implements BeforeEachCallback, AfterEachCallback {

  private final LocalServiceTestHelper helper =
      new LocalServiceTestHelper(new LocalUserServiceTestConfig());
  private final UserInfo userInfo;

  public UserServiceExtension(String email) {
    this.userInfo = UserInfo.create(email);
  }

  public UserServiceExtension(UserInfo userInfo) {
    this.userInfo = userInfo;
  }

  @Override
  public void beforeEach(ExtensionContext context) throws Exception {
    // Set top-level properties on LocalServiceTestConfig for user login.
    helper
        .setEnvIsLoggedIn(userInfo.isLoggedIn())
        .setEnvAuthDomain(userInfo.authDomain())
        .setEnvEmail(userInfo.email())
        .setEnvIsAdmin(userInfo.isAdmin());
    helper.setUp();
  }

  @Override
  public void afterEach(ExtensionContext context) throws Exception {
    helper.tearDown();
    ApiProxy.setEnvironmentForCurrentThread(null);
  }
}
