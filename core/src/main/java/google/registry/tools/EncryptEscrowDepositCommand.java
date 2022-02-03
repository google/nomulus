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

import static google.registry.util.DomainNameUtils.canonicalizeDomainName;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import google.registry.model.rde.RdeMode;
import google.registry.tools.params.PathParameter;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.inject.Inject;

/** Command to encrypt an escrow deposit. */
@Parameters(separators = " =", commandDescription = "Encrypt an escrow deposit")
class EncryptEscrowDepositCommand implements CommandWithRemoteApi {

  @Parameter(
      names = {"-t", "--tld"},
      description = "Top level domain.",
      required = true)
  private String tld;

  @Parameter(
      names = {"-i", "--input"},
      description = "Input XML file that was outputted by GenerateEscrowDepositCommand.",
      validateWith = PathParameter.InputFile.class,
      required = true)
  private Path input;

  @Parameter(
      names = {"-o", "--outdir"},
      description = "Specify output directory. Default is current directory.",
      validateWith = PathParameter.OutputDirectory.class)
  private Path outdir = Paths.get(".");

  @Parameter(
      names = {"-m", "--mode"},
      description = "Specify the escrow mode, FULL for RDE and THIN for BRDA.")
  private RdeMode mode = RdeMode.FULL;

  @Parameter(
      names = {"-r", "--revision"},
      description = "Specify the revision.")
  private int revision = 0;

  @Inject EscrowDepositEncryptor encryptor;

  @Override
  public final void run() throws Exception {
    encryptor.encrypt(mode, canonicalizeDomainName(tld), revision, input, outdir);
  }
}
