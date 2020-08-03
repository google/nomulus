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

package google.registry.model;

import com.google.common.collect.ImmutableSet;
import google.registry.model.billing.BillingEvent;
import google.registry.model.common.Cursor;
import google.registry.model.common.DatabaseTransitionSchedule;
import google.registry.model.common.EntityGroupRoot;
import google.registry.model.common.GaeUserIdConverter;
import google.registry.model.contact.ContactHistory;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.host.HostHistory;
import google.registry.model.host.HostResource;
import google.registry.model.index.EppResourceIndex;
import google.registry.model.index.EppResourceIndexBucket;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.model.ofy.CommitLogBucket;
import google.registry.model.ofy.CommitLogCheckpoint;
import google.registry.model.ofy.CommitLogCheckpointRoot;
import google.registry.model.ofy.CommitLogManifest;
import google.registry.model.ofy.CommitLogMutation;
import google.registry.model.poll.PollMessage;
import google.registry.model.rde.RdeRevision;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.model.registry.Registry;
import google.registry.model.registry.label.PremiumList;
import google.registry.model.registry.label.ReservedList;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.server.KmsSecret;
import google.registry.model.server.KmsSecretRevision;
import google.registry.model.server.Lock;
import google.registry.model.server.ServerSecret;
import google.registry.model.smd.SignedMarkRevocationList;
import google.registry.model.tmch.ClaimsListShard;
import google.registry.model.tmch.ClaimsListShard.ClaimsListRevision;
import google.registry.model.tmch.ClaimsListShard.ClaimsListSingleton;
import google.registry.model.tmch.TmchCrl;
import google.registry.schema.replay.LastSqlTransaction;

/** Sets of classes of the Objectify-registered entities in use throughout the model. */
public final class EntityClasses {

  /** Set of entity classes. */
  public static final ImmutableSet<Class<? extends ImmutableObject>> ALL_CLASSES =
      ImmutableSet.of(
          AllocationToken.class,
          BillingEvent.Cancellation.class,
          BillingEvent.Modification.class,
          BillingEvent.OneTime.class,
          BillingEvent.Recurring.class,
          ClaimsListShard.class,
          ClaimsListRevision.class,
          ClaimsListSingleton.class,
          CommitLogBucket.class,
          CommitLogCheckpoint.class,
          CommitLogCheckpointRoot.class,
          CommitLogManifest.class,
          CommitLogMutation.class,
          ContactHistory.class,
          ContactResource.class,
          Cursor.class,
          DatabaseTransitionSchedule.class,
          DomainBase.class,
          DomainHistory.class,
          EntityGroupRoot.class,
          EppResourceIndex.class,
          EppResourceIndexBucket.class,
          ForeignKeyIndex.ForeignKeyContactIndex.class,
          ForeignKeyIndex.ForeignKeyDomainIndex.class,
          ForeignKeyIndex.ForeignKeyHostIndex.class,
          GaeUserIdConverter.class,
          HistoryEntry.class,
          HostHistory.class,
          HostResource.class,
          KmsSecret.class,
          KmsSecretRevision.class,
          LastSqlTransaction.class,
          Lock.class,
          PollMessage.class,
          PollMessage.Autorenew.class,
          PollMessage.OneTime.class,
          PremiumList.class,
          PremiumList.PremiumListEntry.class,
          PremiumList.PremiumListRevision.class,
          RdeRevision.class,
          Registrar.class,
          RegistrarContact.class,
          Registry.class,
          ReservedList.class,
          ServerSecret.class,
          SignedMarkRevocationList.class,
          TmchCrl.class);

  private EntityClasses() {}
}
