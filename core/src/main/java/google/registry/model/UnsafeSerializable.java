// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

import java.io.Serializable;

/**
 * Marker interface for Nomulus entities whose serialization are implemented in a fragile way. These
 * entities are made {@link Serializable} so that they can be passed between JVMs. The intended use
 * case is BEAM pipeline-based cross-database data validation between Datastore and Cloud SQL during
 * the migration. Note that only objects loaded from the SQL database need serialization support.
 * Objects exported from Datastore can already be serialized as protocol buffers.
 *
 * <p>All entities implementing this interface take advantage of the fact that all Java collection
 * classes we use, either directly or indirectly, including those in Java libraries, Guava,
 * Objectify, and Hibernate are {@code Serializable}.
 *
 * <p>The {@code serialVersionUID} field has also been omitted in the implementing classes, since
 * they are not used for persistence.
 */
// TODO(b/203609782): either remove this interface or fix implementors post migration.
public interface UnsafeSerializable extends Serializable {}
