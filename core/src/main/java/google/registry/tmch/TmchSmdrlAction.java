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

import com.google.common.flogger.FluentLogger;
import google.registry.keyring.api.KeyModule.Key;
import google.registry.model.smd.SignedMarkRevocationList;
import google.registry.request.Action;
import google.registry.request.auth.Auth;
import java.io.IOException;
import java.security.SignatureException;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;
import org.bouncycastle.openpgp.PGPException;

/** Action to download the latest signed mark revocation list from MarksDB. */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/task/tmchSmdrl",
    method = POST,
    automaticallyPrintOk = true,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public final class TmchSmdrlAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final String SMDRL_CSV_PATH = "/smdrl/smdrl-latest.csv";
  private static final String SMDRL_SIG_PATH = "/smdrl/smdrl-latest.sig";

  @Inject Marksdb marksdb;
  @Inject @Key("marksdbSmdrlLoginAndPassword") Optional<String> marksdbSmdrlLoginAndPassword;
  @Inject TmchSmdrlAction() {}

  /** Synchronously fetches latest signed mark revocation list and saves it to Datastore. */
  @Override
  public void run() {
    List<String> lines;
    try {
      lines = marksdb.fetchSignedCsv(marksdbSmdrlLoginAndPassword, SMDRL_CSV_PATH, SMDRL_SIG_PATH);
    } catch (SignatureException | IOException | PGPException e) {
      throw new RuntimeException(e);
    }
    SignedMarkRevocationList smdrl = SmdrlCsvParser.parse(lines);
    smdrl.save();
    logger.atInfo().log(
        "Inserted %,d smd revocations into Datastore, created at %s",
        smdrl.size(), smdrl.getCreationTime());
  }
}
