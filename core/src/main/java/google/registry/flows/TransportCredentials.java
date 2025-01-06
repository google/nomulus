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

package google.registry.flows;

import google.registry.flows.EppException.AuthenticationErrorException;
import google.registry.model.registrar.Registrar;

/** Interface for objects containing registrar credentials provided via an EPP transport. */
public interface TransportCredentials {
  /**
   * Check that these credentials are valid for the registrar and optionally check the password.
   *
   * <p>Called by {@link google.registry.flows.session.LoginFlow LoginFlow} to check the transport
   * credentials against the stored registrar's credentials. If they do not match, throw an {@link
   * AuthenticationErrorException}.
   */
  void validate(Registrar registrar, String password) throws AuthenticationErrorException;

  /** Registrar password is incorrect. */
  class BadRegistrarPasswordException extends AuthenticationErrorException {
    public BadRegistrarPasswordException() {
      super("Registrar password is incorrect");
    }
  }
}
