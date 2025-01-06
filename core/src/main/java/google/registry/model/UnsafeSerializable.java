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

import java.io.Serializable;

/**
 * Marker interface for Nomulus entities whose serialization are implemented in a fragile way. These
 * entities are made {@link Serializable} so that they can be passed between JVMs.
 *
 * <p>All entities implementing this interface take advantage of the fact that all Java collection
 * classes we use, either directly or indirectly, including those in Java libraries, Guava, and
 * Hibernate are {@code Serializable}.
 *
 * <p>The {@code serialVersionUID} field has also been omitted in the implementing classes, since
 * they are not used for persistence.
 */
public interface UnsafeSerializable extends Serializable {}
