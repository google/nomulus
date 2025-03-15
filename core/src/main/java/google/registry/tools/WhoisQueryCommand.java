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

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Joiner;
import google.registry.whois.Whois;
import jakarta.inject.Inject;
import java.util.List;

/** Command to execute a WHOIS query. */
@Parameters(separators = " =", commandDescription = "Manually perform a WHOIS query")
final class WhoisQueryCommand implements Command {

  @Parameter(
      description = "WHOIS query string",
      required = true)
  private List<String> mainParameters;

  @Parameter(
      names = "--unicode",
      description = "When set, output will be Unicode")
  private boolean unicode;

  @Parameter(names = "--full_output", description = "When set, the full output will be displayed")
  private boolean fullOutput;

  @Inject
  Whois whois;

  @Override
  public void run() {
    System.out.println(whois.lookup(Joiner.on(' ').join(mainParameters), unicode, fullOutput));
  }
}
