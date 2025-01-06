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

package google.registry.model.host;

import static google.registry.util.CollectionUtils.nullSafeImmutableCopy;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;

import com.google.common.collect.ImmutableSet;
import google.registry.model.eppinput.ResourceCommand.AbstractSingleResourceCommand;
import google.registry.model.eppinput.ResourceCommand.ResourceCheck;
import google.registry.model.eppinput.ResourceCommand.ResourceCreateOrChange;
import google.registry.model.eppinput.ResourceCommand.ResourceUpdate;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlTransient;
import jakarta.xml.bind.annotation.XmlType;
import java.net.InetAddress;
import java.util.Set;

/** A collection of {@link Host} commands. */
public class HostCommand {

  /** The fields on "chgType" from <a href="http://tools.ietf.org/html/rfc5732">RFC5732</a>. */
  @XmlTransient
  abstract static class HostCreateOrChange extends AbstractSingleResourceCommand
      implements ResourceCreateOrChange<Host.Builder> {
    public String getHostName() {
      return getTargetId();
    }
  }

  /**
   * A create command for a {@link Host}, mapping "createType" from <a
   * href="http://tools.ietf.org/html/rfc5732">RFC5732</a>.
   */
  @XmlType(propOrder = {"targetId", "inetAddresses"})
  @XmlRootElement
  public static class Create extends HostCreateOrChange
      implements ResourceCreateOrChange<Host.Builder> {
    /** IP Addresses for this host. Can be null if this is an external host. */
    @XmlElement(name = "addr")
    Set<InetAddress> inetAddresses;

    public ImmutableSet<InetAddress> getInetAddresses() {
      return nullSafeImmutableCopy(inetAddresses);
    }
  }

  /** A delete command for a {@link Host}. */
  @XmlRootElement
  public static class Delete extends AbstractSingleResourceCommand {}

  /** An info request for a {@link Host}. */
  @XmlRootElement
  public static class Info extends AbstractSingleResourceCommand {}

  /** A check request for {@link Host}. */
  @XmlRootElement
  public static class Check extends ResourceCheck {}

  /** An update to a {@link Host}. */
  @XmlRootElement
  @XmlType(propOrder = {"targetId", "innerAdd", "innerRemove", "innerChange"})
  public static class Update extends ResourceUpdate<Update.AddRemove, Host.Builder, Update.Change> {

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

    /** The add/remove type on a host update command. */
    @XmlType(propOrder = { "inetAddresses", "statusValues" })
    public static class AddRemove extends ResourceUpdate.AddRemove {
      /** IP Addresses for this host. Can be null if this is an external host. */
      @XmlElement(name = "addr")
      Set<InetAddress> inetAddresses;

      public ImmutableSet<InetAddress> getInetAddresses() {
        return nullToEmptyImmutableCopy(inetAddresses);
      }
    }

    /** The inner change type on a host update command. */
    public static class Change extends HostCreateOrChange {}
  }
}
