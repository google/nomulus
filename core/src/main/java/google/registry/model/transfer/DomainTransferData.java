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

package google.registry.model.transfer;

import com.googlecode.objectify.annotation.Embed;
import com.googlecode.objectify.annotation.Unindex;
import javax.persistence.Embeddable;

/** Transfer data for domain. */
@Embed
@Unindex
@Embeddable
public class DomainTransferData extends TransferData<DomainTransferData.Builder> {
  public static final DomainTransferData EMPTY = new DomainTransferData();

  @Override
  public boolean isEmpty() {
    return EMPTY.equals(this);
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  public static class Builder extends TransferData.Builder<DomainTransferData, Builder> {
    /** Create a {@link DomainTransferData.Builder} wrapping a new instance. */
    public Builder() {}

    /** Create a {@link Builder} wrapping the given instance. */
    private Builder(DomainTransferData instance) {
      super(instance);
    }
  }
}
