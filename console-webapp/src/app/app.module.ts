// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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
import { BrowserModule } from '@angular/platform-browser';
import { FormsModule } from '@angular/forms';

import { AppRoutingModule } from './app-routing.module';
import { AppComponent } from './app.component';
import { BrowserAnimationsModule } from '@angular/platform-browser/animations';
import { MaterialModule } from './material.module';

import { BackendService } from './shared/services/backend.service';

import { HomeComponent } from './home/home.component';
import { TldsComponent } from './tlds/tlds.component';
import { HeaderComponent } from './header/header.component';
import { SettingsComponent } from './settings/settings.component';
import SettingsContactComponent, {
  ContactDetailsDialogComponent,
} from './settings/contact/contact.component';
import { HttpClientModule } from '@angular/common/http';
import { RegistrarComponent } from './registrar/registrar.component';
import { RegistrarGuard } from './registrar/registrar.guard';

@NgModule({
  declarations: [
    AppComponent,
    HomeComponent,
    TldsComponent,
    HeaderComponent,
    SettingsComponent,
    SettingsContactComponent,
    ContactDetailsDialogComponent,
    RegistrarComponent,
  ],
  imports: [
    HttpClientModule,
    FormsModule,
    MaterialModule,
    BrowserModule,
    AppRoutingModule,
    BrowserAnimationsModule,
  ],
  providers: [BackendService, RegistrarGuard],
  bootstrap: [AppComponent],
})
export class AppModule {}
