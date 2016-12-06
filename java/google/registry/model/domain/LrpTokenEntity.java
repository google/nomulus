// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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
import com.googlecode.objectify.annotation.EmbedMap;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;
import google.registry.model.BackupGroupRoot;
import google.registry.model.Buildable;
import google.registry.model.annotations.ReportedOn;
import google.registry.model.reporting.HistoryEntry;
import java.util.Map;
import java.util.Set;

/** An entity representing a token distributed to eligible LRP registrants. */
@ReportedOn
@Entity
public class LrpTokenEntity extends BackupGroupRoot implements Buildable {

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
   * The key of the history entry for which the token was used.
   */
  Key<HistoryEntry> redemptionHistoryEntry;

  /**
   * A set of key-value properties associated with the LRP token. This map can be used to store
   * additional metadata about the assignee or space of domain names for which this token can be
   * valid.
   */
  @EmbedMap
  Map<String, String> metadata;

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

  public Map<String, String> getMetadata() {
    return nullToEmptyImmutableCopy(metadata);
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** A builder for constructing {@link LrpTokenEntity} objects, since they are immutable. */
  public static class Builder extends Buildable.Builder<LrpTokenEntity> {
    public Builder() {}

    private Builder(LrpTokenEntity instance) {
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

    public Builder setMetadata(Map<String, String> metadata) {
      getInstance().metadata = metadata;
      return this;
    }
  }
}
