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
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.model.common.GaeUserIdConverter;
import google.registry.model.contact.Contact;
import google.registry.model.contact.ContactHistory;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.host.Host;
import google.registry.model.host.HostHistory;
import google.registry.model.index.EppResourceIndex;
import google.registry.model.index.EppResourceIndexBucket;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.server.ServerSecret;

/** Sets of classes of the Objectify-registered entities in use throughout the model. */
@DeleteAfterMigration
public final class EntityClasses {

  /** Set of entity classes. */
  public static final ImmutableSet<Class<? extends ImmutableObject>> ALL_CLASSES =
      ImmutableSet.of(
          Contact.class,
          ContactHistory.class,
          Domain.class,
          DomainHistory.class,
          EppResourceIndex.class,
          EppResourceIndexBucket.class,
          ForeignKeyIndex.ForeignKeyContactIndex.class,
          ForeignKeyIndex.ForeignKeyDomainIndex.class,
          ForeignKeyIndex.ForeignKeyHostIndex.class,
          GaeUserIdConverter.class,
          HistoryEntry.class,
          Host.class,
          HostHistory.class,
          ServerSecret.class);

  private EntityClasses() {}
}
