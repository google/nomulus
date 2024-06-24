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
import { Route, RouterModule } from '@angular/router';
import { BillingInfoComponent } from './billingInfo/billingInfo.component';
import { DomainListComponent } from './domains/domainList.component';
import { HomeComponent } from './home/home.component';
import { RegistrarDetailsComponent } from './registrar/registrarDetails.component';
import { RegistrarComponent } from './registrar/registrarsTable.component';
import { ResourcesComponent } from './resources/resources.component';
import ContactComponent from './settings/contact/contact.component';
import SecurityComponent from './settings/security/security.component';
import { SettingsComponent } from './settings/settings.component';
import UsersComponent from './settings/users/users.component';
import WhoisComponent from './settings/whois/whois.component';
import { SupportComponent } from './support/support.component';
import { RegistryLockVerifyComponent } from './lock/registryLockVerify.component';

export interface RouteWithIcon extends Route {
  iconName?: string;
}

export const routes: RouteWithIcon[] = [
  { path: '', redirectTo: '/home', pathMatch: 'full' },
  {
    path: RegistryLockVerifyComponent.PATH,
    component: RegistryLockVerifyComponent,
  },
  { path: 'registrars', component: RegistrarComponent },
  {
    path: 'home',
    component: HomeComponent,
    title: 'Dashboard',
    iconName: 'view_comfy_alt',
  },
  // { path: 'tlds', component: TldsComponent, title: "TLDs", iconName: "event_list" },
  {
    path: DomainListComponent.PATH,
    component: DomainListComponent,
    title: 'Domains',
    iconName: 'view_list',
  },
  {
    path: SettingsComponent.PATH,
    component: SettingsComponent,
    title: 'Settings',
    iconName: 'settings',
    children: [
      {
        path: '',
        redirectTo: ContactComponent.PATH,
        pathMatch: 'full',
      },
      {
        path: ContactComponent.PATH,
        component: ContactComponent,
        title: 'Contacts',
      },
      {
        path: WhoisComponent.PATH,
        component: WhoisComponent,
        title: 'WHOIS Info',
      },
      {
        path: SecurityComponent.PATH,
        component: SecurityComponent,
        title: 'Security',
      },
      {
        path: UsersComponent.PATH,
        component: UsersComponent,
      },
    ],
  },
  // {
  //   path: EppConsole.PATH,
  //   component: EppConsoleComponent,
  //   title: "EPP Console",
  //   iconName: "upgrade"
  // },
  {
    path: RegistrarComponent.PATH,
    component: RegistrarComponent,
    title: 'Registrars',
    iconName: 'account_circle',
  },
  {
    path: RegistrarDetailsComponent.PATH,
    component: RegistrarDetailsComponent,
  },
  {
    path: BillingInfoComponent.PATH,
    component: BillingInfoComponent,
    title: 'Billing Info',
    iconName: 'credit_card',
  },
  {
    path: ResourcesComponent.PATH,
    component: ResourcesComponent,
    title: 'Resources',
    iconName: 'description',
  },
  {
    path: SupportComponent.PATH,
    component: SupportComponent,
    title: 'Support',
    iconName: 'help',
  },
];

@NgModule({
  imports: [RouterModule.forRoot(routes, { useHash: true })],
  exports: [RouterModule],
})
export class AppRoutingModule {}
