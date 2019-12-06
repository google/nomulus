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

package google.registry.tmch;

import static google.registry.request.Action.Method.POST;
import static java.nio.charset.StandardCharsets.UTF_8;

import google.registry.config.RegistryConfig.Config;
import google.registry.request.Action;
import google.registry.request.auth.Auth;
import java.io.IOException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.util.Optional;
import javax.inject.Inject;

/** Action to download the latest ICANN TMCH CRL from MarksDB. */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/task/tmchCrl",
    method = POST,
    automaticallyPrintOk = true,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public final class TmchCrlAction implements Runnable {

  @Inject Marksdb marksdb;
  @Inject @Config("tmchCrlUrl") URL tmchCrlUrl;
  @Inject TmchCertificateAuthority tmchCertificateAuthority;
  @Inject TmchCrlAction() {}

  /** Synchronously fetches latest ICANN TMCH CRL and saves it to Datastore. */
  @Override
  public void run() {
    try {
      tmchCertificateAuthority.updateCrl(
          new String(marksdb.fetch(tmchCrlUrl, Optional.empty()), UTF_8),
          tmchCrlUrl.toString());
    } catch (IOException | GeneralSecurityException e) {
      throw new RuntimeException("Failed to update ICANN TMCH CRL.", e);
    }
  }
}
