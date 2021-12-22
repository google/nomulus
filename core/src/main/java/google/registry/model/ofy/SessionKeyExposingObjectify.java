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

package google.registry.model.ofy;

import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.ObjectifyFactory;
import com.googlecode.objectify.impl.ObjectifyImpl;
import google.registry.model.annotations.DeleteAfterMigration;

/** Registry-specific Objectify subclass that exposes the keys used in the current session. */
@DeleteAfterMigration
public class SessionKeyExposingObjectify extends ObjectifyImpl<SessionKeyExposingObjectify> {

  public SessionKeyExposingObjectify(ObjectifyFactory factory) {
    super(factory);
  }

  /** Expose the protected method that provides the keys read, saved or deleted in a session. */
  ImmutableSet<Key<?>> getSessionKeys() {
    return ImmutableSet.copyOf(getSession().keys());
  }
}
