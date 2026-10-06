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

package google.registry.tools;

import com.beust.jcommander.Parameters;
import com.google.api.client.auth.oauth2.StoredCredential;
import com.google.api.client.util.store.AbstractDataStoreFactory;
import com.google.common.flogger.FluentLogger;
import jakarta.inject.Inject;
import java.io.IOException;

/** Logout (invalidates OAuth credentials). */
@Parameters(commandDescription = "Remove local OAuth credentials")
class LogoutCommand implements Command {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject AbstractDataStoreFactory dataStoreFactory;

  @Override
  public void run() throws IOException {
    StoredCredential.getDefaultDataStore(dataStoreFactory).clear();
    logger.atInfo().log("Logged out - credentials have been removed.");
  }
}
