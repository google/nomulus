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

package google.registry.model.domain.launch;

import static com.google.common.base.MoreObjects.firstNonNull;

import google.registry.model.ImmutableObject;
import google.registry.model.eppinput.EppInput.CommandExtension;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlEnumValue;
import jakarta.xml.bind.annotation.XmlRootElement;

/**
 * An XML data object that represents a launch extension that may be present on EPP domain check
 * commands.
 *
 * <p>This object holds XML data which JAXB will unmarshal from an EPP domain check command
 * extension.  The XML will have the following enclosing structure:
 *
 * <pre> {@code
 *   <epp>
 *     <command>
 *       <create>
 *         <!-- domain check XML data -->
 *       </create>
 *       <extension>
 *         <launch:check>
 *           <!-- launch check XML payload data -->
 *         </launch:check>
 *       </extension>
 *     </command>
 *   </epp>
 * } </pre>
 *
 * @see CommandExtension
 */
@XmlRootElement(name = "check")
public class LaunchCheckExtension extends ImmutableObject implements CommandExtension {

  /** The default check type is "claims" if not specified. */
  private static final CheckType DEFAULT_CHECK_TYPE = CheckType.CLAIMS;

  /** Type of domain check being requested. */
  public enum CheckType {
    /** A check to see if the specified domain names are available to be provisioned. */
    @XmlEnumValue("avail")
    AVAILABILITY,

    /** A check to see if there are matching trademarks on the specified domain names. */
    @XmlEnumValue("claims")
    CLAIMS
  }

  /**
   * The launch phase this command is intended to run against. If it does not match the server's
   * current launch phase, the command will be rejected.
   */
  LaunchPhase phase;

  @XmlAttribute
  CheckType type;

  public CheckType getCheckType() {
    return firstNonNull(type, DEFAULT_CHECK_TYPE);
  }

  public LaunchPhase getPhase() {
    return phase;
  }
}
