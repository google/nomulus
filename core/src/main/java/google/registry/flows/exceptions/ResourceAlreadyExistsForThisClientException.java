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

package google.registry.flows.exceptions;

import google.registry.flows.EppException.ObjectAlreadyExistsException;

/** Resource with this id already exists. */
// This is different from ResourceCreateContentionException in that this is used in situations where
// the requesting client already owns this resource. Javadoc and exception message are the same for
// backcompat purposes.
public class ResourceAlreadyExistsForThisClientException extends ObjectAlreadyExistsException {
  public ResourceAlreadyExistsForThisClientException(String resourceId) {
    super(String.format("Object with given ID (%s) already exists", resourceId));
  }
}
