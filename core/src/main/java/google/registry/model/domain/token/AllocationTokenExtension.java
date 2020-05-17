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

package google.registry.model.domain.token;

import google.registry.model.ImmutableObject;
import google.registry.model.eppinput.EppInput.CommandExtension;
import google.registry.xml.TrimWhitespaceAdapter;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlValue;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

/**
 * An allocation token extension that may be present on EPP domain commands.
 *
 * @see <a href="https://tools.ietf.org/html/draft-ietf-regext-allocation-token-04">the IETF
 *     draft</a>
 */
@XmlRootElement(name = "allocationToken")
public class AllocationTokenExtension extends ImmutableObject implements CommandExtension {

  /** The allocation token for the command. */
  @XmlValue
  @XmlJavaTypeAdapter(TrimWhitespaceAdapter.class)
  String allocationToken;

  public String getAllocationToken() {
    return allocationToken;
  }
}
