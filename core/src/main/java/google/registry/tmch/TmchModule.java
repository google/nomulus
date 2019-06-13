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

import static com.google.common.io.Resources.asByteSource;
import static com.google.common.io.Resources.getResource;
import static google.registry.request.RequestParameters.extractRequiredHeader;
import static google.registry.request.RequestParameters.extractRequiredParameter;

import dagger.Module;
import dagger.Provides;
import google.registry.keyring.api.KeyModule.Key;
import google.registry.request.Header;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.Parameter;
import java.net.MalformedURLException;
import java.net.URL;
import javax.servlet.http.HttpServletRequest;
import org.bouncycastle.openpgp.PGPPublicKey;

/** Dagger module for TMCH package. */
@Module
public final class TmchModule {

  private static final PGPPublicKey MARKSDB_PUBLIC_KEY = TmchData
      .loadPublicKey(asByteSource(getResource(TmchModule.class, "marksdb-public-key.asc")));

  @Provides
  @Key("marksdbPublicKey")
  static PGPPublicKey getMarksdbPublicKey() {
    return MARKSDB_PUBLIC_KEY;
  }

  @Provides
  @Parameter(NordnUploadAction.LORDN_PHASE_PARAM)
  static String provideLordnPhase(HttpServletRequest req) {
    return extractRequiredParameter(req, NordnUploadAction.LORDN_PHASE_PARAM);
  }

  @Provides
  @Header(NordnVerifyAction.URL_HEADER)
  static URL provideUrl(HttpServletRequest req) {
    try {
      return new URL(extractRequiredHeader(req, NordnVerifyAction.URL_HEADER));
    } catch (MalformedURLException e) {
      throw new BadRequestException("Bad URL: " + NordnVerifyAction.URL_HEADER);
    }
  }

  @Provides
  @Header(NordnVerifyAction.HEADER_ACTION_LOG_ID)
  static String provideActionLogId(HttpServletRequest req) {
    return extractRequiredHeader(req, NordnVerifyAction.HEADER_ACTION_LOG_ID);
  }
}
