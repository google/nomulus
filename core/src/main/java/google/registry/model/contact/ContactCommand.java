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

package google.registry.model.contact;

import static com.google.common.base.Preconditions.checkState;
import static google.registry.util.CollectionUtils.nullToEmpty;

import com.google.common.collect.Maps;
import google.registry.model.ImmutableObject;
import google.registry.model.contact.PostalInfo.Type;
import google.registry.model.eppinput.ResourceCommand.AbstractSingleResourceCommand;
import google.registry.model.eppinput.ResourceCommand.ResourceCheck;
import google.registry.model.eppinput.ResourceCommand.ResourceCreateOrChange;
import google.registry.model.eppinput.ResourceCommand.ResourceUpdate;
import google.registry.model.eppinput.ResourceCommand.SingleResourceCommand;
import java.util.List;
import java.util.Map;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
import javax.xml.bind.annotation.XmlType;
import javax.xml.bind.annotation.adapters.CollapsedStringAdapter;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

/** A collection of {@link ContactResource} commands. */
public class ContactCommand {

  /** The fields on "chgType" from <a href="http://tools.ietf.org/html/rfc5733">RFC5733</a>. */
  @XmlTransient
  public static class ContactCreateOrChange extends ImmutableObject
      implements ResourceCreateOrChange<ContactResource.Builder> {

    /** Postal info for the contact. */
    List<PostalInfo> postalInfo;

     /** Contact’s voice number. */
    ContactPhoneNumber voice;

    /** Contact’s fax number. */
    ContactPhoneNumber fax;

    /** Contact’s email address. */
    @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
    String email;

    /** Authorization info (aka transfer secret) of the contact. */
    ContactAuthInfo authInfo;

    /** Disclosure policy. */
    Disclose disclose;

    /** Helper method to move between the postal infos list and the individual getters. */
    protected Map<Type, PostalInfo> getPostalInfosAsMap() {
      // There can be no more than 2 postalInfos (enforced by the schema), and if there are 2 they
      // must be of different types (not enforced). If the type is repeated, uniqueIndex will throw.
      checkState(nullToEmpty(postalInfo).size() <= 2);
      return Maps.uniqueIndex(nullToEmpty(postalInfo), PostalInfo::getType);
    }

    public ContactPhoneNumber getVoice() {
      return voice;
    }

    public ContactPhoneNumber getFax() {
      return fax;
    }

    public String getEmail() {
      return email;
    }

    public ContactAuthInfo getAuthInfo() {
      return authInfo;
    }

    public Disclose getDisclose() {
      return disclose;
    }

    public PostalInfo getInternationalizedPostalInfo() {
      return getPostalInfosAsMap().get(Type.INTERNATIONALIZED);
    }

    public PostalInfo getLocalizedPostalInfo() {
      return getPostalInfosAsMap().get(Type.LOCALIZED);
    }
  }

  /** An abstract contact command that contains authorization info. */
  @XmlTransient
  public static class AbstractContactAuthCommand extends AbstractSingleResourceCommand {
    /** Authorization info used to validate if client has permissions to perform this operation. */
    ContactAuthInfo authInfo;

    @Override
    public ContactAuthInfo getAuthInfo() {
      return authInfo;
    }
  }

  /**
   * A create command for a {@link ContactResource}, mapping "createType" from <a
   * href="http://tools.ietf.org/html/rfc5733">RFC5733</a>}.
   */
  @XmlType(propOrder = {"contactId", "postalInfo", "voice", "fax", "email", "authInfo", "disclose"})
  @XmlRootElement
  public static class Create extends ContactCreateOrChange
      implements SingleResourceCommand, ResourceCreateOrChange<ContactResource.Builder> {
    /**
     * Unique identifier for this contact.
     *
     * <p>This is only unique in the sense that for any given lifetime specified as the time range
     * from (creationTime, deletionTime) there can only be one contact in Datastore with this
     * id.  However, there can be many contacts with the same id and non-overlapping lifetimes.
     */
    @XmlElement(name = "id")
    String contactId;

    @Override
    public String getTargetId() {
      return contactId;
    }

    @Override
    public ContactAuthInfo getAuthInfo() {
      return authInfo;
    }
  }

  /** A delete command for a {@link ContactResource}. */
  @XmlRootElement
  public static class Delete extends AbstractSingleResourceCommand {}

  /** An info request for a {@link ContactResource}. */
  @XmlRootElement
  @XmlType(propOrder = {"targetId", "authInfo"})
  public static class Info extends AbstractContactAuthCommand {}

  /** A check request for {@link ContactResource}. */
  @XmlRootElement
  public static class Check extends ResourceCheck {}

  /** A transfer operation for a {@link ContactResource}. */
  @XmlRootElement
  @XmlType(propOrder = {"targetId", "authInfo"})
  public static class Transfer extends AbstractContactAuthCommand {}

  /** An update to a {@link ContactResource}. */
  @XmlRootElement
  @XmlType(propOrder = {"targetId", "innerAdd", "innerRemove", "innerChange"})
  public static class Update
      extends ResourceUpdate<Update.AddRemove, ContactResource.Builder, Update.Change> {

    @XmlElement(name = "chg")
    protected Change innerChange;

    @XmlElement(name = "add")
    protected AddRemove innerAdd;

    @XmlElement(name = "rem")
    protected AddRemove innerRemove;

    @Override
    protected Change getNullableInnerChange() {
      return innerChange;
    }

    @Override
    protected AddRemove getNullableInnerAdd() {
      return innerAdd;
    }

    @Override
    protected AddRemove getNullableInnerRemove() {
      return innerRemove;
    }

    /** The inner change type on a contact update command. */
    public static class AddRemove extends ResourceUpdate.AddRemove {}

    /** The inner change type on a contact update command. */
    @XmlType(propOrder = {"postalInfo", "voice", "fax", "email", "authInfo", "disclose"})
    public static class Change extends ContactCreateOrChange {}
  }
}
