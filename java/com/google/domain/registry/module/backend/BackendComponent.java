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

import com.google.domain.registry.bigquery.BigqueryModule;
import com.google.domain.registry.config.ConfigModule;
import com.google.domain.registry.dns.writer.api.VoidDnsWriterModule;
import com.google.domain.registry.export.DriveModule;
import com.google.domain.registry.export.sheet.SpreadsheetServiceModule;
import com.google.domain.registry.gcs.GcsServiceModule;
import com.google.domain.registry.groups.DirectoryModule;
import com.google.domain.registry.groups.GroupsModule;
import com.google.domain.registry.groups.GroupssettingsModule;
import com.google.domain.registry.keyring.api.KeyModule;
import com.google.domain.registry.keyring.api.VoidKeyringModule;
import com.google.domain.registry.rde.JSchModule;
import com.google.domain.registry.request.Modules.AppIdentityCredentialModule;
import com.google.domain.registry.request.Modules.DatastoreServiceModule;
import com.google.domain.registry.request.Modules.GoogleCredentialModule;
import com.google.domain.registry.request.Modules.Jackson2Module;
import com.google.domain.registry.request.Modules.ModulesServiceModule;
import com.google.domain.registry.request.Modules.URLFetchServiceModule;
import com.google.domain.registry.request.Modules.UrlFetchTransportModule;
import com.google.domain.registry.request.Modules.UseAppIdentityCredentialForGoogleApisModule;
import com.google.domain.registry.request.RequestModule;
import com.google.domain.registry.util.SystemClock.SystemClockModule;
import com.google.domain.registry.util.SystemSleeper.SystemSleeperModule;

import dagger.Component;

import javax.inject.Singleton;

/** Dagger component with instance lifetime for "backend" App Engine module. */
@Singleton
@Component(
    modules = {
        AppIdentityCredentialModule.class,
        BackendModule.class,
        BigqueryModule.class,
        ConfigModule.class,
        DatastoreServiceModule.class,
        DirectoryModule.class,
        DriveModule.class,
        GcsServiceModule.class,
        GoogleCredentialModule.class,
        GroupsModule.class,
        GroupssettingsModule.class,
        JSchModule.class,
        Jackson2Module.class,
        KeyModule.class,
        ModulesServiceModule.class,
        SpreadsheetServiceModule.class,
        SystemClockModule.class,
        SystemSleeperModule.class,
        URLFetchServiceModule.class,
        UrlFetchTransportModule.class,
        UseAppIdentityCredentialForGoogleApisModule.class,
        VoidDnsWriterModule.class,
        VoidKeyringModule.class,
    })
interface BackendComponent {
  BackendRequestComponent startRequest(RequestModule requestModule);
}
