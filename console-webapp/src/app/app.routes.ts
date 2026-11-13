// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

import { Route } from '@angular/router';

export interface RouteWithIcon extends Route {
  iconName?: string;
}

export const PATHS = {
  BillingInfoComponent: 'billingInfo',
  ContactComponent: 'contact',
  DomainListComponent: 'domain-list',
  HistoryComponent: 'history',
  HomeComponent: 'home',
  NewOteComponent: 'new-ote',
  OteStatusComponent: 'ote-status/:registrarId',
  PasswordResetVerifyComponent: 'password-reset-verify',
  RdapComponent: 'rdap',
  RegistrarComponent: 'registrars',
  RegistrarDetailsComponent: 'registrars/:id',
  RegistryLockVerifyComponent: 'registry-lock-verify',
  ResourcesComponent: 'resources',
  SecurityComponent: 'security',
  SettingsComponent: 'settings',
  SupportComponent: 'support',
  UsersComponent: 'users',
};

export const routes: RouteWithIcon[] = [
  { path: '', redirectTo: '/home', pathMatch: 'full' },
  {
    path: PATHS.PasswordResetVerifyComponent,
    loadComponent: () =>
      import(
        './shared/components/passwordReset/passwordResetVerify.component'
      ).then((mod) => mod.PasswordResetVerifyComponent),
  },
  {
    path: PATHS.RegistryLockVerifyComponent,
    loadComponent: () =>
      import('./lock/registryLockVerify.component').then(
        (mod) => mod.RegistryLockVerifyComponent
      ),
  },
  {
    path: PATHS.NewOteComponent,
    loadComponent: () =>
      import('./ote/newOte.component').then((mod) => mod.NewOteComponent),
  },
  {
    path: PATHS.OteStatusComponent,
    loadComponent: () =>
      import('./ote/oteStatus.component').then((mod) => mod.OteStatusComponent),
  },
  {
    path: 'registrars',
    loadComponent: () =>
      import('./registrar/registrarsTable.component').then(
        (mod) => mod.RegistrarComponent
      ),
  },
  {
    path: PATHS.HomeComponent,
    loadComponent: () =>
      import('./home/home.component').then((mod) => mod.HomeComponent),
    title: 'Dashboard',
    iconName: 'view_comfy_alt',
  },
  {
    path: PATHS.DomainListComponent,
    loadComponent: () =>
      import('./domains/domainList.component').then(
        (mod) => mod.DomainListComponent
      ),
    title: 'Domains',
    iconName: 'view_list',
  },
  {
    path: PATHS.HistoryComponent,
    loadComponent: () =>
      import('./history/history.component').then((mod) => mod.HistoryComponent),
    // title: 'History',
    // iconName: 'history',
  },
  {
    path: PATHS.SettingsComponent,
    loadComponent: () =>
      import('./settings/settings.component').then(
        (mod) => mod.SettingsComponent
      ),
    title: 'Settings',
    iconName: 'settings',
    children: [
      {
        path: '',
        redirectTo: PATHS.ContactComponent,
        pathMatch: 'full',
      },
      {
        path: PATHS.ContactComponent,
        loadComponent: () =>
          import('./settings/contact/contact.component').then(
            (mod) => mod.ContactComponent
          ),
        title: 'Contacts',
      },
      {
        path: PATHS.RdapComponent,
        loadComponent: () =>
          import('./settings/rdap/rdap.component').then(
            (mod) => mod.RdapComponent
          ),
        title: 'RDAP Info',
      },
      {
        path: PATHS.SecurityComponent,
        loadComponent: () =>
          import('./settings/security/security.component').then(
            (mod) => mod.SecurityComponent
          ),
        title: 'Security',
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
    path: PATHS.RegistrarComponent,
    loadComponent: () =>
      import('./registrar/registrarsTable.component').then(
        (mod) => mod.RegistrarComponent
      ),
    title: 'Registrars',
    iconName: 'account_circle',
  },
  {
    path: PATHS.RegistrarDetailsComponent,
    loadComponent: () =>
      import('./registrar/registrarDetails.component').then(
        (mod) => mod.RegistrarDetailsComponent
      ),
  },
  {
    path: PATHS.BillingInfoComponent,
    loadComponent: () =>
      import('./billingInfo/billingInfo.component').then(
        (mod) => mod.BillingInfoComponent
      ),
    title: 'Billing Info',
    iconName: 'credit_card',
  },
  {
    path: PATHS.ResourcesComponent,
    loadComponent: () =>
      import('./resources/resources.component').then(
        (mod) => mod.ResourcesComponent
      ),
    title: 'Resources',
    iconName: 'description',
  },
  {
    path: PATHS.UsersComponent,
    title: 'Users',
    iconName: 'manage_accounts',
    loadComponent: () =>
      import('./users/users.component').then((mod) => mod.UsersComponent),
  },
  {
    path: PATHS.SupportComponent,
    loadComponent: () =>
      import('./support/support.component').then((mod) => mod.SupportComponent),
    title: 'Support',
    iconName: 'help',
  },
];
