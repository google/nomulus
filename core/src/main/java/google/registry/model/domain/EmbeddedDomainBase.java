// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

import jakarta.persistence.Embeddable;

/**
 * Embeddable {@link DomainBase} used for storage in history objects.
 *
 * <p>As of version 7.x, Hibernate no longer allows direct storage of mapped (essentially abstract)
 * superclasses as embedded entities. This kind of makes sense because it doesn't make too much
 * sense to store what are supposed to be abstract objects, but it makes our duplication of the base
 * fields in the history class somewhat more annoying. This is a concrete class that can be directly
 * embedded inside the history class to store the fields.
 */
@Embeddable
public class EmbeddedDomainBase extends DomainBase {
  public static class Builder extends DomainBase.Builder<EmbeddedDomainBase, Builder> {}
}
