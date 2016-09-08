// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

package domains.donuts.config;

import static google.registry.request.RequestParameters.extractOptionalHeader;
import static google.registry.request.RequestParameters.extractRequiredHeader;

import com.google.common.base.Optional;
import dagger.Module;
import dagger.Provides;
import google.registry.request.Header;
import javax.servlet.http.HttpServletRequest;

/** Module overriding HTTP header names for {@code EppTlsAction}. */
@Module
public final class DonutsTlsCredentialsModule {

  @Provides
  @Header("X-GFE-SSL-Certificate")
  static String provideClientCertificateHash(HttpServletRequest req) {
    return extractRequiredHeader(req, "X-SSL-Certificate");
  }

  @Provides
  @Header("X-Forwarded-For")
  static Optional<String> provideForwardedFor(HttpServletRequest req) {
    return extractOptionalHeader(req, "X-Forwarded-For");
  }

  @Provides
  @Header("X-GFE-Requested-Servername-SNI")
  static String provideRequestedServername(HttpServletRequest req) {
    return extractRequiredHeader(req, "X-Requested-Servername-SNI");
  }
}
