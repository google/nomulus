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

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import google.registry.keyring.api.KeyModule.Key;
import google.registry.rde.Ghostryde;
import google.registry.tools.params.PathParameter;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.annotation.Nullable;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;

/** Command to encrypt/decrypt {@code .ghostryde} files. */
@Parameters(separators = " =", commandDescription = "Encrypt/decrypt a ghostryde file.")
final class GhostrydeCommand implements Command {

  @Parameter(
      names = {"-e", "--encrypt"},
      description = "Encrypt mode.")
  private boolean encrypt;

  @Parameter(
      names = {"-d", "--decrypt"},
      description = "Decrypt mode.")
  private boolean decrypt;

  @Parameter(
      names = {"-i", "--input"},
      description = "Input file.",
      validateWith = PathParameter.InputFile.class)
  private Path input = Paths.get("/dev/stdin");

  @Parameter(
      names = {"-o", "--output"},
      description =
          "Output file. If this is a directory: (a) in --encrypt mode, the output "
              + "filename will be the input filename with '.ghostryde' appended, and will have an "
              + "extra '<filename>.length' file with the original file's length; (b) In --decrypt "
              + "mode, the output filename will be the input filename with '.decrypt' appended. "
              + "Defaults to stdout in --decrypt mode.",
      validateWith = PathParameter.class)
  @Nullable
  private Path output;

  @Inject
  @Key("rdeStagingEncryptionKey")
  Provider<PGPPublicKey> rdeStagingEncryptionKey;

  @Inject
  @Key("rdeStagingDecryptionKey")
  Provider<PGPPrivateKey> rdeStagingDecryptionKey;

  @Override
  public void run() throws Exception {
    checkArgument(encrypt ^ decrypt, "Please specify either --encrypt or --decrypt");
    if (encrypt) {
      checkArgumentNotNull(output, "--output path is required in --encrypt mode");
      runEncrypt();
    } else {
      runDecrypt();
    }
  }

  private void runEncrypt() throws IOException {
    boolean isOutputToDir = output.toFile().isDirectory();
    Path outFile = isOutputToDir ? output.resolve(input.getFileName() + ".ghostryde") : output;
    Path lenOutFile = isOutputToDir ? output.resolve(input.getFileName() + ".length") : null;
    try (OutputStream out = Files.asByteSink(outFile.toFile()).openBufferedStream();
        OutputStream lenOut =
            (lenOutFile == null)
                ? null
                : Files.asByteSink(lenOutFile.toFile()).openBufferedStream();
        OutputStream ghostrydeEncoder =
            Ghostryde.encoder(out, rdeStagingEncryptionKey.get(), lenOut);
        InputStream in = Files.asByteSource(input.toFile()).openBufferedStream()) {
      ByteStreams.copy(in, ghostrydeEncoder);
    }
  }

  private void runDecrypt() throws IOException {
    try (InputStream in = Files.asByteSource(input.toFile()).openBufferedStream();
        InputStream ghostDecoder = Ghostryde.decoder(in, rdeStagingDecryptionKey.get())) {
      if (output == null) {
        ByteStreams.copy(ghostDecoder, System.out);
      } else {
        Path outFile =
            output.toFile().isDirectory()
                ? output.resolve(input.getFileName() + ".decrypt")
                : output;
        Files.asByteSink(outFile.toFile()).writeFrom(ghostDecoder);
      }
    }
  }
}
