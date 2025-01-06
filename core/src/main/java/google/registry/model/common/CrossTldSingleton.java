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

package google.registry.model.common;

import google.registry.model.ImmutableObject;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

/** A singleton entity in the database. */
@MappedSuperclass
public abstract class CrossTldSingleton extends ImmutableObject {

  public static final long SINGLETON_ID = 1; // There is always exactly one of these.

  @Id long id = SINGLETON_ID;
}
