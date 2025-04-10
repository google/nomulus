// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

import { NgModule } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { BrowserModule } from '@angular/platform-browser';

import { BrowserAnimationsModule } from '@angular/platform-browser/animations';
import { AppRoutingModule } from './app-routing.module';
import { AppComponent } from './app.component';
import { MaterialModule } from './material.module';

import { BackendService } from './shared/services/backend.service';

import { provideHttpClient } from '@angular/common/http';
import { MAT_FORM_FIELD_DEFAULT_OPTIONS } from '@angular/material/form-field';
import { BillingInfoComponent } from './billingInfo/billingInfo.component';
import {
  DomainListComponent,
  ReasonDialogComponent,
  ResponseDialogComponent,
} from './domains/domainList.component';
import { RegistryLockComponent } from './domains/registryLock.component';
import { HeaderComponent } from './header/header.component';
import { HomeComponent } from './home/home.component';
import { RegistryLockVerifyComponent } from './lock/registryLockVerify.component';
import { NavigationComponent } from './navigation/navigation.component';
import NewRegistrarComponent from './registrar/newRegistrar.component';
import { RegistrarDetailsComponent } from './registrar/registrarDetails.component';
import { RegistrarSelectorComponent } from './registrar/registrarSelector.component';
import { RegistrarComponent } from './registrar/registrarsTable.component';
import { ResourcesComponent } from './resources/resources.component';
import SettingsContactComponent from './settings/contact/contact.component';
import { ContactDetailsComponent } from './settings/contact/contactDetails.component';
import EppPasswordEditComponent from './settings/security/eppPasswordEdit.component';
import SecurityComponent from './settings/security/security.component';
import SecurityEditComponent from './settings/security/securityEdit.component';
import { SettingsComponent } from './settings/settings.component';
import { NotificationsComponent } from './shared/components/notifications/notifications.component';
import { SelectedRegistrarWrapper } from './shared/components/selectedRegistrarWrapper/selectedRegistrarWrapper.component';
import { LocationBackDirective } from './shared/directives/locationBack.directive';
import { UserLevelVisibility } from './shared/directives/userLevelVisiblity.directive';
import { BreakPointObserverService } from './shared/services/breakPoint.service';
import { GlobalLoaderService } from './shared/services/globalLoader.service';
import { UserDataService } from './shared/services/userData.service';
import { SnackBarModule } from './snackbar.module';
import { SupportComponent } from './support/support.component';
import { TldsComponent } from './tlds/tlds.component';
import { ForceFocusDirective } from './shared/directives/forceFocus.directive';
import RdapComponent from './settings/rdap/rdap.component';
import RdapEditComponent from './settings/rdap/rdapEdit.component';

@NgModule({
  declarations: [SelectedRegistrarWrapper],
  imports: [MaterialModule],
  exports: [SelectedRegistrarWrapper],
  providers: [],
})
export class SelectedRegistrarModule {}

@NgModule({
  declarations: [
    AppComponent,
    BillingInfoComponent,
    ContactDetailsComponent,
    DomainListComponent,
    EppPasswordEditComponent,
    ForceFocusDirective,
    HeaderComponent,
    HomeComponent,
    LocationBackDirective,
    NavigationComponent,
    NewRegistrarComponent,
    NotificationsComponent,
    RdapComponent,
    RdapEditComponent,
    ReasonDialogComponent,
    RegistrarComponent,
    RegistrarDetailsComponent,
    RegistrarSelectorComponent,
    RegistryLockComponent,
    RegistryLockVerifyComponent,
    ResourcesComponent,
    ResponseDialogComponent,
    SecurityComponent,
    SecurityEditComponent,
    SettingsComponent,
    SettingsContactComponent,
    SupportComponent,
    TldsComponent,
    UserLevelVisibility,
  ],
  bootstrap: [AppComponent],
  imports: [
    AppRoutingModule,
    BrowserAnimationsModule,
    BrowserModule,
    FormsModule,
    MaterialModule,
    SelectedRegistrarModule,
    SnackBarModule,
  ],
  providers: [
    BackendService,
    BreakPointObserverService,
    GlobalLoaderService,
    UserDataService,
    {
      provide: MAT_FORM_FIELD_DEFAULT_OPTIONS,
      useValue: {
        subscriptSizing: 'dynamic',
      },
    },
    provideHttpClient(),
  ],
})
export class AppModule {}
