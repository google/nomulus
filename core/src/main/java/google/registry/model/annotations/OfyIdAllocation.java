// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.annotations;

import google.registry.model.IdService;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to indicate an ID field that needs to be allocated by Ofy.
 *
 * <p>This annotation is only used for the field of a {@link google.registry.model.Buildable} class
 * that was previously annotated by both Ofy's and JPA's {@code @Id} annotations, of which the Ofy
 * annotation has been removed. The field still needs to be allocated automatically by the builder,
 * via the {@link IdService#allocateId()}.
 *
 * <p>It should be removed after we switch to using SQL to directly allocate IDs.
 */
@DeleteAfterMigration
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface OfyIdAllocation {}
