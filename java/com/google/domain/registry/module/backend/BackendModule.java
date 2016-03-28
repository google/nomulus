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

package com.google.domain.registry.module.backend;

import static com.google.domain.registry.model.registry.Registries.assertTldExists;
import static com.google.domain.registry.request.RequestParameters.extractRequiredParameter;

import com.google.common.collect.ImmutableList;
import com.google.domain.registry.request.Parameter;
import com.google.domain.registry.request.RequestParameters;
import com.google.domain.registry.request.Router;

import dagger.Module;
import dagger.Provides;

import javax.servlet.http.HttpServletRequest;

/**
 * Dagger module for injecting common settings for all Backend tasks.
 */
@Module
public class BackendModule {

  @Provides
  @Parameter(RequestParameters.PARAM_TLD)
  static String provideTld(HttpServletRequest req) {
    return assertTldExists(extractRequiredParameter(req, RequestParameters.PARAM_TLD));
  }

  @Provides
  static Router provideRouter() {
    return new Router(ImmutableList.copyOf(BackendRequestComponent.class.getMethods()));
  }
}
