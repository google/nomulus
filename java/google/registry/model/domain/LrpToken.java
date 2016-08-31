// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

package google.registry.model.domain;

import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;
import google.registry.model.BackupGroupRoot;
import google.registry.model.Buildable;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.reporting.HistoryEntry.Type;
import java.util.Set;

/**
 * An entity representing a token distributed to eligible LRP registrants.
 */
@Entity
public class LrpToken extends BackupGroupRoot implements Buildable {

  /**
   * The secret token assigned to a registrant for the purposes of LRP registration.
   */
  @Id
  String token;

  /**
   * The token's assignee (additional metadata for identifying the owner of the token, the details
   * of which might differ from TLD to TLD).
   */
  @Index
  String assignee;

  /**
   * A list of TLDs for which this LRP token is valid.
   */
  Set<String> validTlds;

  /**
   * The key of the history entry for which the token was used. Given LRP is a domain application
   * phase, this should always be a {@link Type#DOMAIN_APPLICATION_CREATE}.
   */
  Key<HistoryEntry> redemptionHistoryEntry;
  
  public String getToken() {
    return token;
  }

  public String getAssignee() {
    return assignee;
  }

  public Key<HistoryEntry> getRedemptionHistoryEntry() {
    return redemptionHistoryEntry;
  }

  public boolean isRedeemed() {
    return redemptionHistoryEntry != null;
  }

  public Set<String> getValidTlds() {
    return nullToEmptyImmutableCopy(validTlds);
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** A builder for constructing {@link LrpToken} objects, since they are immutable. */
  public static class Builder extends Buildable.Builder<LrpToken> {
    public Builder() {}

    private Builder(LrpToken instance) {
      super(instance);
    }

    public Builder setAssignee(String assignee) {
      getInstance().assignee = assignee;
      return this;
    }

    public Builder setToken(String token) {
      getInstance().token = checkArgumentNotNull(token);
      return this;
    }
    
    public Builder setRedemptionHistoryEntry(Key<HistoryEntry> redemptionHistoryEntry) {
      getInstance().redemptionHistoryEntry = checkArgumentNotNull(redemptionHistoryEntry);
      return this;
    }

    public Builder setValidTlds(Set<String> validTlds) {
      getInstance().validTlds = validTlds;
      return this;
    }
  }
}
