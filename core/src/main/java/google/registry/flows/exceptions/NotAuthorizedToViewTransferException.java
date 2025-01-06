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

import google.registry.flows.EppException.AuthorizationErrorException;

/** Registrar is not authorized to view transfer status. */
public class NotAuthorizedToViewTransferException extends AuthorizationErrorException {
  public NotAuthorizedToViewTransferException() {
    super("Registrar is not authorized to view transfer status");
  }
}
