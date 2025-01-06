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

package google.registry.tmch;

import static com.google.common.base.CharMatcher.whitespace;

import com.google.common.io.ByteSource;
import google.registry.model.smd.EncodedSignedMark;
import java.io.IOException;
import java.io.InputStream;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.bc.BcPGPPublicKeyRing;

/** Helper class for common data loaded from the jar and SQL at runtime. */
public final class TmchData {

  static final String BEGIN_ENCODED_SMD = "-----BEGIN ENCODED SMD-----";
  static final String END_ENCODED_SMD = "-----END ENCODED SMD-----";

  private TmchData() {}

  static PGPPublicKey loadPublicKey(ByteSource pgpPublicKeyFile) {
    try (InputStream input = pgpPublicKeyFile.openStream();
        InputStream decoder = PGPUtil.getDecoderStream(input)) {
      return new BcPGPPublicKeyRing(decoder).getPublicKey();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** Extracts encoded SMD from an ASCII-armored string. */
  public static EncodedSignedMark readEncodedSignedMark(String data) {
    int beginTagIndex = data.indexOf(BEGIN_ENCODED_SMD);
    int endTagIndex = data.indexOf(END_ENCODED_SMD);
    if (beginTagIndex >= 0 && endTagIndex >= 0) {
      data = data.substring(beginTagIndex + BEGIN_ENCODED_SMD.length(), endTagIndex);
    }
    return EncodedSignedMark.create("base64", whitespace().removeFrom(data));
  }
}
