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

package google.registry.model;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.RegistrarBillingEntry;
import google.registry.model.billing.RegistrarCredit;
import google.registry.model.billing.RegistrarCreditBalance;
import google.registry.model.common.Cursor;
import google.registry.model.common.EntityGroupRoot;
import google.registry.model.common.GaeUserIdConverter;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainApplication;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.LrpTokenEntity;
import google.registry.model.export.LogsExportCursor;
import google.registry.model.host.HostResource;
import google.registry.model.icann.IcannDnsReportingFields;
import google.registry.model.icann.IcannRegistrarReportingFields;
import google.registry.model.icann.IcannTldReportingFields;
import google.registry.model.index.DomainApplicationIndex;
import google.registry.model.index.EppResourceIndex;
import google.registry.model.index.EppResourceIndexBucket;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.model.ofy.CommitLogBucket;
import google.registry.model.ofy.CommitLogCheckpoint;
import google.registry.model.ofy.CommitLogCheckpointRoot;
import google.registry.model.ofy.CommitLogManifest;
import google.registry.model.ofy.CommitLogMutation;
import google.registry.model.permission.PermissionGroup;
import google.registry.model.permission.UserPermissionGroups;
import google.registry.model.poll.PollMessage;
import google.registry.model.pricing.PricingCategory;
import google.registry.model.rde.RdeRevision;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.model.registry.Registry;
import google.registry.model.registry.label.BasePremiumList;
import google.registry.model.registry.label.CategorizedPremiumList;
import google.registry.model.registry.label.PremiumList;
import google.registry.model.registry.label.ReservedList;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.server.Lock;
import google.registry.model.server.ServerSecret;
import google.registry.model.smd.SignedMarkRevocationList;
import google.registry.model.tmch.ClaimsListShard;
import google.registry.model.tmch.ClaimsListShard.ClaimsListRevision;
import google.registry.model.tmch.ClaimsListShard.ClaimsListSingleton;
import google.registry.model.tmch.TmchCrl;
import google.registry.model.user.User;

/** Sets of classes of the Objectify-registered entities in use throughout the model. */
public final class EntityClasses {

  /** Set of entity classes. */
  @SuppressWarnings("unchecked") // varargs
  public static final ImmutableSet<Class<? extends ImmutableObject>> ALL_CLASSES =
      ImmutableSet.<Class<? extends ImmutableObject>>of(
          BasePremiumList.PremiumListRevision.class,
          BillingEvent.Cancellation.class,
          BillingEvent.Modification.class,
          BillingEvent.OneTime.class,
          BillingEvent.Recurring.class,
          CategorizedPremiumList.class,
          CategorizedPremiumList.CategorizedListEntry.class,
          ClaimsListShard.class,
          ClaimsListRevision.class,
          ClaimsListSingleton.class,
          CommitLogBucket.class,
          CommitLogCheckpoint.class,
          CommitLogCheckpointRoot.class,
          CommitLogManifest.class,
          CommitLogMutation.class,
          ContactResource.class,
          Cursor.class,
          DomainApplication.class,
          DomainApplicationIndex.class,
          DomainBase.class,
          DomainResource.class,
          EntityGroupRoot.class,
          EppResourceIndex.class,
          EppResourceIndexBucket.class,
          ForeignKeyIndex.ForeignKeyContactIndex.class,
          ForeignKeyIndex.ForeignKeyDomainIndex.class,
          ForeignKeyIndex.ForeignKeyHostIndex.class,
          GaeUserIdConverter.class,
          HistoryEntry.class,
          HostResource.class,
          IcannDnsReportingFields.class,
          IcannRegistrarReportingFields.class,
          IcannTldReportingFields.class,
          Lock.class,
          LogsExportCursor.class,
          LrpTokenEntity.class,
          PermissionGroup.class,
          PollMessage.class,
          PollMessage.Autorenew.class,
          PollMessage.OneTime.class,
          PremiumList.class,
          PremiumList.PremiumListEntry.class,
          PricingCategory.class,
          RdeRevision.class,
          Registrar.class,
          RegistrarBillingEntry.class,
          RegistrarContact.class,
          RegistrarCredit.class,
          RegistrarCreditBalance.class,
          Registry.class,
          ReservedList.class,
          ServerSecret.class,
          SignedMarkRevocationList.class,
          TmchCrl.class,
          User.class,
          UserPermissionGroups.class);

  /**
   * Function that converts an Objectify-registered class to its datastore kind name.
   *
   * <p>Note that this mapping is not one-to-one, since polymorphic subclasses of an entity all
   * have the same datastore kind.  (In theory, two distinct top-level entities could also map to
   * the same kind since it's just {@code class.getSimpleName()}, but we test against that.)
   */
  public static final Function<Class<? extends ImmutableObject>, String> CLASS_TO_KIND_FUNCTION =
      new Function<Class<? extends ImmutableObject>, String>() {
        @Override
        public String apply(Class<? extends ImmutableObject> clazz) {
          return Key.getKind(clazz);
        }
      };

  private EntityClasses() {}
}
