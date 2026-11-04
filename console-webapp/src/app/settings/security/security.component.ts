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

import {
  Component,
  effect,
  signal,
  untracked,
  ChangeDetectionStrategy,
} from '@angular/core';
import { MaterialModule } from 'src/app/material.module';
import {
  RegistrarService,
  SecuritySettings,
} from 'src/app/registrar/registrar.service';
import { EppPasswordEditComponent } from './eppPasswordEdit.component';
import { SecurityService, apiToUiConverter } from './security.service';
import { SecurityEditComponent } from './securityEdit.component';

@Component({
  selector: 'app-security',
  imports: [MaterialModule, SecurityEditComponent, EppPasswordEditComponent],
  templateUrl: './security.component.html',
  styleUrls: ['./security.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SecurityComponent {
  public static PATH = 'security';
  dataSource: SecuritySettings = {};
  constructor(
    public securityService: SecurityService,
    public registrarService: RegistrarService
  ) {
    effect(() => {
      const registrar = this.registrarService.registrar();
      if (registrar) {
        untracked(() => {
          this.dataSource = apiToUiConverter(registrar);
          this.securityService.isEditingSecurity.set(false);
          this.securityService.isEditingPassword.set(false);
        });
      }
    });
  }

  editSecurity() {
    this.securityService.isEditingSecurity.set(true);
  }

  editEppPassword() {
    this.securityService.isEditingPassword.set(true);
  }
}
