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

import static google.registry.util.DomainNameUtils.ACE_PREFIX;
import static google.registry.util.DomainNameUtils.canonicalizeHostname;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Ascii;
import google.registry.util.Idn;
import java.util.List;

/** Command to convert IDN labels to/from punycode. */
@Parameters(commandDescription = "Convert IDNs to/from punycode")
final class ConvertIdnCommand implements Command {

  @Parameter(
      description = "Labels to convert",
      required = true)
  private List<String> mainParameters;

  @Override
  public void run() {
    for (String label : mainParameters) {
      if (label.startsWith(ACE_PREFIX)) {
        System.out.println(Idn.toUnicode(Ascii.toLowerCase(label)));
      } else {
        System.out.println(canonicalizeHostname(label));
      }
    }
  }
}
