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
import google.registry.model.tmch.ClaimsListDao;
import google.registry.model.tmch.ClaimsListShard;
import google.registry.request.Action;
import google.registry.request.auth.Auth;
import google.registry.schema.tmch.ClaimsList;
import java.io.IOException;
import java.security.SignatureException;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;
import org.bouncycastle.openpgp.PGPException;

/** Action to download the latest domain name list (aka claims list) from MarksDB. */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/task/tmchDnl",
    method = POST,
    automaticallyPrintOk = true,
    auth = Auth.AUTH_INTERNAL_ONLY)
public final class TmchDnlAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final String DNL_CSV_PATH = "/dnl/dnl-latest.csv";
  private static final String DNL_SIG_PATH = "/dnl/dnl-latest.sig";

  @Inject Marksdb marksdb;
  @Inject @Key("marksdbDnlLoginAndPassword") Optional<String> marksdbDnlLoginAndPassword;
  @Inject TmchDnlAction() {}

  /** Synchronously fetches latest domain name list and saves it to Datastore. */
  @Override
  public void run() {
    List<String> lines;
    try {
      lines = marksdb.fetchSignedCsv(marksdbDnlLoginAndPassword, DNL_CSV_PATH, DNL_SIG_PATH);
    } catch (SignatureException | IOException | PGPException e) {
      throw new RuntimeException(e);
    }
    ClaimsList claims = ClaimsListParser.parse(lines);
    ClaimsListShard claimsListShard =
        ClaimsListShard.create(claims.getTmdbGenerationTime(), claims.getLabelsToKeys());
    claimsListShard.save();
    logger.atInfo().log(
        "Inserted %,d claims into Datastore, created at %s",
        claimsListShard.size(), claimsListShard.getCreationTime());

    ClaimsListDao.trySave(claims);
  }
}
