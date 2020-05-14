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

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import google.registry.model.EppResource.ForeignKeyedEppResource;
import google.registry.model.annotations.ExternalMessagingName;
import google.registry.model.annotations.ReportedOn;
import google.registry.model.transfer.TransferData;
import google.registry.persistence.VKey;
import google.registry.persistence.WithStringVKey;
import google.registry.schema.replay.DatastoreAndSqlEntity;

/**
 * A persistable Host resource including mutable and non-mutable fields.
 *
 * <p>A host's {@link TransferData} is stored on the superordinate domain. Non-subordinate hosts
 * don't carry a full set of TransferData; all they have is lastTransferTime.
 *
 * @see <a href="https://tools.ietf.org/html/rfc5732">RFC 5732</a>
 */
@ReportedOn
@Entity
@javax.persistence.Entity
@ExternalMessagingName("host")
@WithStringVKey
public class HostResource extends HostBase
    implements DatastoreAndSqlEntity, ForeignKeyedEppResource {

  /**
   * Unique identifier in the registry for this resource.
   *
   * <p>This is in the (\w|_){1,80}-\w{1,8} format specified by RFC 5730 for roidType.
   *
   * @see <a href="https://tools.ietf.org/html/rfc5730">RFC 5730</a>
   */
  @Id @javax.persistence.Id String repoId;

  @Override
  public String getRepoId() {
    return repoId;
  }

  public VKey<HostResource> createKey() {
    return VKey.createOfy(HostResource.class, Key.create(this));
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** A builder for constructing {@link HostResource}, since it is immutable. */
  public static class Builder extends HostBase.Builder<HostResource, Builder> {
    public Builder() {}

    private Builder(HostResource instance) {
      super(instance);
    }

    public Builder setRepoId(String repoId) {
      getInstance().repoId = repoId;
      return this;
    }
  }
}
