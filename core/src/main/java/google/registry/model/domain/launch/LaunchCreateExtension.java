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

import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;

import com.google.common.collect.ImmutableList;
import google.registry.model.eppinput.EppInput.CommandExtension;
import google.registry.model.smd.AbstractSignedMark;
import google.registry.model.smd.EncodedSignedMark;
import google.registry.model.smd.SignedMark;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElementRef;
import jakarta.xml.bind.annotation.XmlElementRefs;
import jakarta.xml.bind.annotation.XmlEnumValue;
import jakarta.xml.bind.annotation.XmlRootElement;
import java.util.List;

/**
 * An XML data object that represents a launch extension that may be present on EPP domain create
 * commands.
 *
 * <p>This object holds XML data which JAXB will unmarshal from an EPP domain create command
 * extension.  The XML will have the following enclosing structure:
 *
 * <pre> {@code
 *   <epp>
 *     <command>
 *       <create>
 *         <!-- domain create XML data -->
 *       </create>
 *       <extension>
 *         <launch:create>
 *           <!-- launch create XML payload data -->
 *         </launch:create>
 *       </extension>
 *     </command>
 *   </epp>
 * } </pre>
 *
 * @see CommandExtension
 */
@XmlRootElement(name = "create")
public class LaunchCreateExtension extends LaunchExtension implements CommandExtension {

  /** Type of domain creation being requested. */
  public enum CreateType {
    /**
     * A Launch Application refers to a registration made during a launch phase when the server
     * accepts multiple applications for the same domain name.
     *
     * <p>This is no longer used, but is retained so incoming commands with this value error out
     * with a descriptive message rather than failing XML marshalling.
     */
    @XmlEnumValue("application")
    APPLICATION,

    /**
     * A Launch Registration refers to a registration made during a launch phase when the server
     * uses a "first-come, first-served" model.
     */
    @XmlEnumValue("registration")
    REGISTRATION
  }

  @XmlAttribute
  CreateType type;

  /**
   * A list of signed marks or encoded signed marks which assert the client's ability to register
   * the specified domain name.  Each one contains both a Mark object with information about its
   * claim(s), and an XML signature over that mark object which is cryptographically signed. This is
   * used in the "signed mark" validation model.
   */
  @XmlElementRefs({
      @XmlElementRef(type = EncodedSignedMark.class),
      @XmlElementRef(type = SignedMark.class)})
  List<AbstractSignedMark> signedMarks;

  /**
   * A CodeMark is an abstract entity which contains either a secret code or a mark (or both) to
   * assert its ability to register a particular mark. It is used in the "code", "mark", and "code
   * with mark" validation models, none of which are supported by this codebase at this time. As
   * such, it is stored only as an Object to mark its existence, but not further unmarshaled.
   */
  List<Object> codeMark;

  /** The claims notice for this create, required if creating a domain with a claimed label. */
  LaunchNotice notice;

  public CreateType getCreateType() {
    return type;
  }

  public ImmutableList<AbstractSignedMark> getSignedMarks() {
    return nullToEmptyImmutableCopy(signedMarks);
  }

  public boolean hasCodeMarks() {
    return codeMark != null && !codeMark.isEmpty();
  }

  public LaunchNotice getNotice() {
    return notice;
  }
}
