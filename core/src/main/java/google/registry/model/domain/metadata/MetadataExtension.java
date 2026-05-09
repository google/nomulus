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

package google.registry.model.domain.metadata;

import static com.google.common.base.MoreObjects.firstNonNull;

import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import google.registry.model.eppinput.EppInput.CommandExtension;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;
import javax.annotation.Nullable;

/**
 * Extension for EPP commands that provides metadata.
 *
 * @see <a href="https://www.google.com/search?q=EPP+metadata+extension">EPP Metadata Extension</a>
 */
@XmlRootElement(name = "metadata")
@XmlType(propOrder = {"reason", "requestedByRegistrar", "isAnchorTenant"})
public class MetadataExtension extends ImmutableObject implements CommandExtension {

  /** Reason for the command. */
  @XmlElement @Nullable String reason;

  /** Whether the command was requested by a registrar. */
  @XmlElement Boolean requestedByRegistrar;

  /** Whether this is an anchor tenant. */
  @XmlElement(name = "anchorTenant")
  Boolean isAnchorTenant;

  public String getReason() {
    return reason;
  }

  public Boolean getRequestedByRegistrar() {
    return requestedByRegistrar;
  }

  public Boolean getIsAnchorTenant() {
    return firstNonNull(isAnchorTenant, false);
  }

  /** Builder for {@link MetadataExtension}. */
  public static class Builder extends Buildable.Builder<MetadataExtension> {
    public Builder setReason(String reason) {
      getInstance().reason = reason;
      return this;
    }

    public Builder setRequestedByRegistrar(Boolean requestedByRegistrar) {
      getInstance().requestedByRegistrar = requestedByRegistrar;
      return this;
    }

    public Builder setAnchorTenant(Boolean isAnchorTenant) {
      getInstance().isAnchorTenant = isAnchorTenant;
      return this;
    }
  }
}
