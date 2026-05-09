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

package google.registry.model.eppinput;

import static google.registry.util.CollectionUtils.nullSafeImmutableCopy;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.Buildable;
import google.registry.model.EppResource;
import google.registry.model.ImmutableObject;
import google.registry.model.eppcommon.AuthInfo;
import google.registry.model.eppcommon.StatusValue;
import google.registry.util.TypeUtils.TypeInstantiator;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElements;
import jakarta.xml.bind.annotation.XmlTransient;
import java.util.List;

/** Commands for EPP resources. */
public interface ResourceCommand {

  /** Interface for EPP commands that operate on a single resource. */
  interface SingleResourceCommand extends ResourceCommand {
    @Override
    String getTargetId();

    @Override
    AuthInfo getAuthInfo();
  }

  /** Returns the target ID for single-resource commands, or null otherwise. */
  default String getTargetId() {
    return null;
  }

  /** Returns the auth info for single-resource commands, or null otherwise. */
  default AuthInfo getAuthInfo() {
    return null;
  }

  /** Abstract implementation of {@link ResourceCommand}. */
  @XmlTransient
  @XmlAccessorType(XmlAccessType.FIELD)
  public abstract class AbstractSingleResourceCommand extends ImmutableObject
      implements SingleResourceCommand {

    @XmlTransient public String targetId;

    public void setTargetId(String targetId) {
      this.targetId = targetId;
    }

    @Override
    @XmlTransient
    public String getTargetId() {
      return targetId;
    }

    @Override
    public AuthInfo getAuthInfo() {
      return null;
    }
  }

  /** A check command for an {@link EppResource}. */
  @XmlTransient
  @XmlAccessorType(XmlAccessType.FIELD)
  public class ResourceCheck extends ImmutableObject implements ResourceCommand {
    @XmlElements({@XmlElement(name = "id"), @XmlElement(name = "name")})
    public List<String> targetUniqueIds;

    public void setTargetIds(ImmutableList<String> targetUniqueIds) {
      this.targetUniqueIds = targetUniqueIds;
    }

    public ImmutableList<String> getTargetIds() {
      return nullSafeImmutableCopy(targetUniqueIds);
    }
  }

  /** A create command, or the inner change (as opposed to add or remove) part of an update. */
  public interface ResourceCreateOrChange<B extends Buildable.Builder<?>> {}

  /**
   * An update command for an {@link EppResource}.
   *
   * @param <A> the add-remove type
   * @param <C> the change type
   */
  @XmlTransient
  public abstract class ResourceUpdate<
          A extends ResourceUpdate.AddRemove,
          B extends EppResource.Builder<?, ?>,
          C extends ResourceCreateOrChange<B>>
      extends AbstractSingleResourceCommand {

    /** Part of an update command that specifies set values to add or remove. */
    @XmlTransient
    @XmlAccessorType(XmlAccessType.FIELD)
    public abstract static class AddRemove extends ImmutableObject {
      public abstract void setStatusValues(ImmutableSet<StatusValue> statusValues);

      public abstract ImmutableSet<StatusValue> getStatusValues();
    }

    protected abstract C getNullableInnerChange();

    protected abstract A getNullableInnerAdd();

    protected abstract A getNullableInnerRemove();

    // Don't use MoreObjects.firstNonNull in these methods because it will result in an unneeded
    // reflective instantiation when the object isn't null.

    public C getInnerChange() {
      C change = getNullableInnerChange();
      return change == null ? new TypeInstantiator<C>(getClass()){}.instantiate() : change;
    }

    public A getInnerAdd() {
      A add = getNullableInnerAdd();
      return add == null ? new TypeInstantiator<A>(getClass()){}.instantiate() : add;
    }

    public A getInnerRemove() {
      A remove = getNullableInnerRemove();
      return remove == null ? new TypeInstantiator<A>(getClass()){}.instantiate() : remove;
    }
  }
}
