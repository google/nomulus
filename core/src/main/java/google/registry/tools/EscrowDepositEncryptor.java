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

import static google.registry.model.rde.RdeMode.FULL;

import com.google.common.io.ByteStreams;
import google.registry.keyring.api.KeyModule.Key;
import google.registry.model.rde.RdeMode;
import google.registry.model.rde.RdeNamingUtils;
import google.registry.rde.RdeUtil;
import google.registry.rde.RydeEncoder;
import google.registry.xml.XmlException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.inject.Inject;
import javax.inject.Provider;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.joda.time.DateTime;

/** Utility for encrypting an RDE RyDE deposit on the Java 7 NIO file system. */
final class EscrowDepositEncryptor {

  private static final int PEEK_BUFFER_SIZE = 64 * 1024;

  @Inject @Key("rdeSigningKey") Provider<PGPKeyPair> rdeSigningKey;
  @Inject @Key("rdeReceiverKey") Provider<PGPPublicKey> rdeReceiverKey;

  @Inject
  @Key("brdaSigningKey")
  Provider<PGPKeyPair> brdaSigningKey;

  @Inject
  @Key("brdaReceiverKey")
  Provider<PGPPublicKey> brdaReceiverKey;

  @Inject EscrowDepositEncryptor() {}

  /** Creates a {@code .ryde} and {@code .sig} file, provided an XML deposit file. */
  void encrypt(RdeMode mode, String tld, Integer revision, Path xmlFile, Path outdir)
      throws IOException, XmlException {
    try (InputStream xmlFileInput = Files.newInputStream(xmlFile);
        BufferedInputStream xmlInput = new BufferedInputStream(xmlFileInput, PEEK_BUFFER_SIZE)) {
      DateTime watermark = RdeUtil.peekWatermark(xmlInput);
      String name = RdeNamingUtils.makeRydeFilename(tld, watermark, mode, 1, revision);
      Path rydePath = outdir.resolve(name + ".ryde");
      Path sigPath = outdir.resolve(name + ".sig");
      Path pubPath = outdir.resolve(tld + ".pub");
      PGPKeyPair signingKey;
      PGPPublicKey receiverKey;
      if (mode == FULL) {
        signingKey = rdeSigningKey.get();
        receiverKey = rdeReceiverKey.get();
      } else {
        signingKey = brdaSigningKey.get();
        receiverKey = brdaReceiverKey.get();
      }
      try (OutputStream rydeOutput = Files.newOutputStream(rydePath);
          OutputStream sigOutput = Files.newOutputStream(sigPath);
          RydeEncoder rydeEncoder =
              new RydeEncoder.Builder()
                  .setRydeOutput(rydeOutput, receiverKey)
                  .setSignatureOutput(sigOutput, signingKey)
                  .setFileMetadata(name, Files.size(xmlFile), watermark)
                  .build()) {
        ByteStreams.copy(xmlInput, rydeEncoder);
      }
      try (OutputStream pubOutput = Files.newOutputStream(pubPath);
          ArmoredOutputStream ascOutput = new ArmoredOutputStream(pubOutput)) {
        signingKey.getPublicKey().encode(ascOutput);
      }
    }
  }
}
