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

package google.registry.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.util.X509Utils.getCertificateHash;
import static google.registry.util.X509Utils.loadCertificate;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Joiner;
import java.io.IOException;
import java.nio.file.Paths;
import java.security.cert.CertificateParsingException;
import java.util.ArrayList;
import java.util.List;

/** Command to hash a client certificate. */
@Parameters(commandDescription = "Hash a client certificate")
final class HashCertificateCommand implements Command {

  @Parameter(description = "Certificate filename")
  List<String> mainParameters = new ArrayList<>();

  @Override
  public void run() throws IOException, CertificateParsingException {
    checkArgument(mainParameters.size() <= 1,
        "Expected at most one argument with the certificate filename. Actual: %s",
        Joiner.on(' ').join(mainParameters));
    if (mainParameters.isEmpty()) {
      System.out.println(getCertificateHash(loadCertificate(System.in)));
    } else {
      System.out.println(getCertificateHash(loadCertificate(Paths.get(mainParameters.get(0)))));
    }
  }
}
