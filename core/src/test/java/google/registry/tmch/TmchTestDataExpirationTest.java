// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

import static google.registry.testing.TestDataHelper.listFiles;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.ConfigModule.TmchCaMode;
import google.registry.flows.EppException;
import google.registry.flows.domain.DomainFlowTmchUtils;
import google.registry.model.smd.EncodedSignedMark;
import google.registry.testing.AppEngineExtension;
import google.registry.util.ResourceUtils;
import google.registry.util.SystemClock;
import java.nio.file.Path;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests that the ICANN testing signed mark files are valid and not expired. */
class TmchTestDataExpirationTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Test
  void testActiveSignedMarkFiles_areValidAndNotExpired() throws Exception {
    DomainFlowTmchUtils tmchUtils =
        new DomainFlowTmchUtils(
            new TmchXmlSignature(
                new TmchCertificateAuthority(TmchCaMode.PILOT, new SystemClock())));

    for (Path path : listFiles(TmchTestDataExpirationTest.class, "active/")) {
      if (path.toString().endsWith(".smd")) {
        logger.atInfo().log("Verifying: %s", path);
        String tmchData = ResourceUtils.readResourceUtf8(path.toUri().toURL());
        EncodedSignedMark smd = TmchData.readEncodedSignedMark(tmchData);
        try {
          tmchUtils.verifyEncodedSignedMark(smd, DateTime.now(UTC));
        } catch (EppException e) {
          throw new AssertionError("Error verifying signed mark " + path, e);
        }
      } else {
        logger.atInfo().log("Ignored: %s", path);
      }
    }
  }
}
