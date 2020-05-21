// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

import com.googlecode.objectify.Key;
import google.registry.model.EppResource;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import javax.persistence.Column;
import javax.persistence.Entity;

@Entity
@javax.persistence.Table(
    indexes = {
      @javax.persistence.Index(columnList = "creationTime"),
      @javax.persistence.Index(columnList = "registrarId"),
    })
public class HostHistory extends HistoryEntry {

  // Store HostBase instead of HostResource so we don't pick up its @Id
  HostBase hostBase;

  @Column(nullable = false)
  VKey<HostResource> hostResource;

  /** The state of the {@link HostBase} object at this point in time. */
  public HostBase getHostBase() {
    return hostBase;
  }

  /** The key to the {@link google.registry.model.host.HostResource} this is based off of. */
  public VKey<HostResource> getHostResource() {
    return hostResource;
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  public static class Builder extends HistoryEntry.Builder<HostHistory, Builder> {

    public Builder() {}

    public Builder(HostHistory instance) {
      super(instance);
    }

    public Builder setHostBase(HostBase hostBase) {
      getInstance().hostBase = hostBase;
      return this;
    }

    public Builder setHostResource(VKey<HostResource> hostResource) {
      getInstance().hostResource = hostResource;
      hostResource.maybeGetOfyKey().ifPresent(parent -> getInstance().parent = parent);
      return this;
    }

    // We can remove this once all HistoryEntries are converted to History objects
    @Override
    public Builder setParent(Key<? extends EppResource> parent) {
      super.setParent(parent);
      getInstance().hostResource =
          VKey.createOfy(HostResource.class, (Key<HostResource>) parent);
      return this;
    }
  }
}
