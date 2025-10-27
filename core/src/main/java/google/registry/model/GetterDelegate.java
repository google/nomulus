// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * A delegate getter method to be used when getting the value of an {@link ImmutableObject} field.
 *
 * <p>This is useful because Hibernate has limitations on what kinds of types can be used to
 * represent a field value, the most relevant being that it must be mutable. Since we use Guava's
 * ImmutableCollections widely, this means that a frequent pattern is to e.g. have a field be
 * declared as a Set (with a HashSet implementation), but then implement a getter method for that
 * field that returns the desired ImmutableSet or ImmutableSortedSet. For purposes where it matters
 * that the field be represented using the appropriate type, such as for outputting in sorted order
 * via toString, then declare a getter delegate as follows:
 *
 * <pre>{@code
 * @GetterDelegate(methodName = "getAllowedTlds")
 * Set<String> allowedTlds;
 *
 * public ImmutableSortedSet<String> getAllowedTlds() {
 *   return nullToEmptyImmutableSortedCopy(allowedTlds);
 * }
 * }</pre>
 */
@Documented
@Retention(RUNTIME)
@Target(FIELD)
public @interface GetterDelegate {
  String methodName();
}
