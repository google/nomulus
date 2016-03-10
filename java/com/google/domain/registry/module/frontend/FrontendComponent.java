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

package com.google.domain.registry.module.frontend;

import com.google.domain.registry.braintree.BraintreeModule;
import com.google.domain.registry.config.ConfigModule;
import com.google.domain.registry.keyring.api.KeyModule;
import com.google.domain.registry.keyring.api.VoidKeyringModule;
import com.google.domain.registry.request.Modules.UserServiceModule;
import com.google.domain.registry.request.RequestModule;
import com.google.domain.registry.ui.ConsoleConfigModule;
import com.google.domain.registry.util.SystemClock.SystemClockModule;

import dagger.Component;

import javax.inject.Singleton;

/** Dagger component with instance lifetime for "default" App Engine module. */
@Singleton
@Component(
    modules = {
        BraintreeModule.class,
        ConfigModule.class,
        ConsoleConfigModule.class,
        FrontendModule.class,
        KeyModule.class,
        SystemClockModule.class,
        UserServiceModule.class,
        VoidKeyringModule.class,
    })
interface FrontendComponent {
  FrontendRequestComponent startRequest(RequestModule requestModule);
}
