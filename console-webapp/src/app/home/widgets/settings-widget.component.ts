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

import { Component } from '@angular/core';
import { Router } from '@angular/router';
import { RegistrarComponent } from 'src/app/registrar/registrarsTable.component';
import ContactComponent from 'src/app/settings/contact/contact.component';
import SecurityComponent from 'src/app/settings/security/security.component';
import { SettingsComponent } from 'src/app/settings/settings.component';

@Component({
  selector: '[app-settings-widget]',
  templateUrl: './settings-widget.component.html',
})
export class SettingsWidgetComponent {
  constructor(private router: Router) {}

  openRegistrarsPage() {
    this.navigate(RegistrarComponent.PATH);
  }

  openSecPage() {
    this.navigate(SecurityComponent.PATH);
  }

  openContactsPage() {
    this.navigate(ContactComponent.PATH);
  }

  private navigate(route: string) {
    this.router.navigate([SettingsComponent.PATH, route]);
  }
}
