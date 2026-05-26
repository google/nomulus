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

package google.registry.model.host;

import static google.registry.util.CollectionUtils.isNullOrEmpty;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;

import com.google.common.collect.ImmutableSet;
import google.registry.model.Buildable;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppinput.ResourceCommand.AbstractSingleResourceCommand;
import google.registry.model.eppinput.ResourceCommand.ResourceCheck;
import google.registry.model.eppinput.ResourceCommand.ResourceCreateOrChange;
import google.registry.model.eppinput.ResourceCommand.ResourceUpdate;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlTransient;
import jakarta.xml.bind.annotation.XmlType;
import java.net.InetAddress;
import java.util.Set;

/** A collection of {@link Host} commands. */
public class HostCommand {

  /** The fields on "chgType" from <a href="https://tools.ietf.org/html/rfc5732">RFC5732</a>. */
  @XmlTransient
  @XmlAccessorType(XmlAccessType.FIELD)
  public abstract static class HostCreateOrChange extends AbstractSingleResourceCommand
      implements ResourceCreateOrChange<Host.Builder> {

    @XmlElement(name = "name")
    String name;

    @Override
    public String getTargetId() {
      return name;
    }

    @Override
    public void setTargetId(String targetId) {
      this.name = targetId;
    }

    public String getHostName() {
      return name;
    }
  }

  /**
   * A create command for a {@link Host}, mapping "createType" from <a
   * href="https://tools.ietf.org/html/rfc5732">RFC5732</a>.
   */
  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(propOrder = {"name", "inetAddresses"})
  @XmlRootElement
  public static class Create extends HostCreateOrChange {
    /** IP Addresses for this host. Can be null if this is an external host. */
    @XmlElement(name = "addr")
    Set<InetAddress> inetAddresses;

    public ImmutableSet<InetAddress> getInetAddresses() {
      return nullToEmptyImmutableCopy(inetAddresses);
    }

    /** Builder for {@link Create}. */
    public static class Builder extends Buildable.Builder<Create> {
      public Builder setTargetId(String targetId) {
        getInstance().setTargetId(targetId);
        return this;
      }

      public Builder setInetAddresses(ImmutableSet<InetAddress> inetAddresses) {
        getInstance().inetAddresses = inetAddresses;
        return this;
      }
    }
  }

  /** A delete command for a {@link Host}. */
  @XmlRootElement
  @XmlAccessorType(XmlAccessType.FIELD)
  public static class Delete extends AbstractSingleResourceCommand {
    @XmlElement(name = "name")
    String name;

    @Override
    public String getTargetId() {
      return name;
    }

    @Override
    public void setTargetId(String targetId) {
      this.name = targetId;
    }
  }

  /** An info request for a {@link Host}. */
  @XmlRootElement
  @XmlAccessorType(XmlAccessType.FIELD)
  public static class Info extends AbstractSingleResourceCommand {
    @XmlElement(name = "name")
    String name;

    @Override
    public String getTargetId() {
      return name;
    }

    @Override
    public void setTargetId(String targetId) {
      this.name = targetId;
    }
  }

  /** A check request for {@link Host}. */
  @XmlRootElement
  public static class Check extends ResourceCheck {}

  /** An update to a {@link Host}. */
  @XmlRootElement
  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(propOrder = {"name", "innerAdd", "innerRemove", "innerChange"})
  public static class Update
      extends ResourceUpdate<Update.HostAddRemove, Host.Builder, Update.Change> {

    @XmlElement(name = "name")
    String name;

    @Override
    public String getTargetId() {
      return name;
    }

    @Override
    public void setTargetId(String targetId) {
      this.name = targetId;
    }

    @XmlElement(name = "chg")
    protected Change innerChange;

    @XmlElement(name = "add")
    protected HostAddRemove innerAdd;

    @XmlElement(name = "rem")
    protected HostAddRemove innerRemove;

    @Override
    protected Change getNullableInnerChange() {
      return innerChange;
    }

    @Override
    protected HostAddRemove getNullableInnerAdd() {
      return innerAdd;
    }

    @Override
    protected HostAddRemove getNullableInnerRemove() {
      return innerRemove;
    }

    /** The add/remove type on a host update command. */
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"inetAddresses", "statusValues"})
    public static class HostAddRemove extends ResourceUpdate.AddRemove {
      /** IP Addresses for this host. Can be null if this is an external host. */
      @XmlElement(name = "addr")
      Set<InetAddress> inetAddresses;

      @XmlElement(name = "status")
      Set<StatusValue> statusValues;

      @Override
      public void setStatusValues(ImmutableSet<StatusValue> statusValues) {
        this.statusValues = statusValues;
      }

      @Override
      public ImmutableSet<StatusValue> getStatusValues() {
        return nullToEmptyImmutableCopy(statusValues);
      }

      public ImmutableSet<InetAddress> getInetAddresses() {
        return nullToEmptyImmutableCopy(inetAddresses);
      }

      /** Builder for {@link HostAddRemove}. */
      public static class Builder extends Buildable.Builder<HostAddRemove> {
        public Builder setInetAddresses(ImmutableSet<InetAddress> inetAddresses) {
          getInstance().inetAddresses = isNullOrEmpty(inetAddresses) ? null : inetAddresses;
          return this;
        }

        public Builder setStatusValues(ImmutableSet<StatusValue> statusValues) {
          getInstance().statusValues = isNullOrEmpty(statusValues) ? null : statusValues;
          return this;
        }
      }
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    public static class Change extends HostCreateOrChange {}
  }
}
