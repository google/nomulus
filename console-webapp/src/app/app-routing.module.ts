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
import { RouterModule, Routes } from '@angular/router';
import { TldsComponent } from './tlds/tlds.component';
import { HomeComponent } from './home/home.component';
import { SettingsComponent } from './settings/settings.component';
import SettingsContactComponent from './settings/contact/contact.component';
import SettingsRegistrarsComponent from './settings/registrars/registrars.component';
import SettingsWhoisComponent from './settings/whois/whois.component';
import SettingsUsersComponent from './settings/users/users.component';
import SettingsSecurityComponent from './settings/security/security.component';

const routes: Routes = [
  { path: '',   redirectTo: '/settings/contact', pathMatch: 'full' },
  { path: 'home', component: HomeComponent },
  { path: 'tlds', component: TldsComponent },
  {
    path: 'settings',
    component: SettingsComponent,
    children: [
      {
        path:'',
        redirectTo: 'contact',
        pathMatch: 'full'
      },
      {
        path: 'contact',
        component: SettingsContactComponent,
      },
      {
        path: 'whois',
        component: SettingsWhoisComponent,
      },
      {
        path: 'security',
        component: SettingsSecurityComponent,
      },
      {
        path: 'epp-password',
        component: SettingsSecurityComponent,
      },
      {
        path: 'users',
        component: SettingsUsersComponent,
      },
      {
        path: 'registrars',
        component: SettingsRegistrarsComponent,
      },
    ],
  },
];

@NgModule({
  imports: [RouterModule.forRoot(routes)],
  exports: [RouterModule]
})
export class AppRoutingModule { }
