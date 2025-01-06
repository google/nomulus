// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

import { Component, effect } from '@angular/core';
import {
  RegistrarService,
  SecuritySettings,
} from 'src/app/registrar/registrar.service';
import { SecurityService, apiToUiConverter } from './security.service';

@Component({
  selector: 'app-security',
  templateUrl: './security.component.html',
  styleUrls: ['./security.component.scss'],
})
export default class SecurityComponent {
  public static PATH = 'security';
  dataSource: SecuritySettings = {};
  constructor(
    public securityService: SecurityService,
    public registrarService: RegistrarService
  ) {
    effect(() => {
      if (this.registrarService.registrar()) {
        this.dataSource = apiToUiConverter(this.registrarService.registrar());
        this.securityService.isEditingSecurity = false;
        this.securityService.isEditingPassword = false;
      }
    });
  }

  editSecurity() {
    this.securityService.isEditingSecurity = true;
  }

  editEppPassword() {
    this.securityService.isEditingPassword = true;
  }
}
