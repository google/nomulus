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
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;
import static google.registry.util.PreconditionsUtils.checkArgumentPresent;
import static google.registry.util.X509Utils.getCertificateHash;
import static google.registry.util.X509Utils.loadCertificate;
import static java.nio.charset.StandardCharsets.US_ASCII;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import google.registry.flows.TlsCredentials;
import google.registry.model.registrar.Registrar;
import google.registry.tools.params.PathParameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;

/** A command to test registrar login credentials. */
@Parameters(separators = " =", commandDescription = "Test registrar login credentials")
final class ValidateLoginCredentialsCommand implements CommandWithRemoteApi {

  @Parameter(
      names = {"-c", "--client"},
      description = "Client identifier of the registrar to test",
      required = true)
  private String clientId;

  @Parameter(
      names = {"-p", "--password"},
      description = "Password of the registrar to test",
      required = true)
  private String password;

  @Nullable
  @Parameter(
      names = {"-f", "--cert_file"},
      description = "File containing the client certificate",
      validateWith = PathParameter.InputFile.class)
  private Path clientCertificatePath;

  @Nullable
  @Parameter(
      names = {"-h", "--cert_hash"},
      description = "Hash of the client certificate.")
  private String clientCertificateHash;

  @Nullable
  @Parameter(
      names = {"-i", "--ip_address"},
      description = "Client ip address to pretend to use")
  private String clientIpAddress = "10.0.0.1";

  @Override
  public void run() throws Exception {
    checkArgument(
        clientCertificatePath == null || isNullOrEmpty(clientCertificateHash),
        "Can't specify both --cert_hash and --cert_file");
    if (clientCertificatePath != null) {
      clientCertificateHash = getCertificateHash(
          loadCertificate(new String(Files.readAllBytes(clientCertificatePath), US_ASCII)));
    }
    Registrar registrar =
        checkArgumentPresent(
            Registrar.loadByClientId(clientId), "Registrar %s not found", clientId);
    new TlsCredentials(
            true, Optional.ofNullable(clientCertificateHash), Optional.ofNullable(clientIpAddress))
        .validate(registrar, password);
    checkState(
        registrar.isLive(), "Registrar %s has non-live state: %s", clientId, registrar.getState());
  }
}
