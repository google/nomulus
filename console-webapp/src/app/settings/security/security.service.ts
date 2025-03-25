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

import { Injectable } from '@angular/core';
import { switchMap, timeout } from 'rxjs';
import {
  IpAllowListItem,
  RegistrarService,
  SecuritySettings,
  SecuritySettingsBackendModel,
} from 'src/app/registrar/registrar.service';
import { BackendService } from 'src/app/shared/services/backend.service';

export interface EppPasswordBackendModel {
  registrarId: string;
  oldPassword: string;
  newPassword: string;
  newPasswordRepeat: string;
}

export function apiToUiConverter(
  securitySettings: SecuritySettingsBackendModel = {}
): SecuritySettings {
  return Object.assign({}, securitySettings, {
    ipAddressAllowList: (securitySettings.ipAddressAllowList || []).map(
      (value) => ({ value })
    ),
  });
}

export function uiToApiConverter(
  securitySettings: SecuritySettings
): SecuritySettingsBackendModel {
  return Object.assign({}, securitySettings, {
    ipAddressAllowList: (securitySettings.ipAddressAllowList || [])
      .filter((s) => s.value)
      .map((ipAllowItem: IpAllowListItem) => ipAllowItem.value),
  });
}

@Injectable({
  providedIn: 'root',
})
export class SecurityService {
  securitySettings: SecuritySettings = {};
  isEditingSecurity: boolean = false;
  isEditingPassword: boolean = false;

  constructor(
    private backend: BackendService,
    private registrarService: RegistrarService
  ) {}

  saveChanges(newSecuritySettings: SecuritySettings) {
    return this.backend
      .postSecuritySettings(
        this.registrarService.registrarId(),
        uiToApiConverter(newSecuritySettings)
      )
      .pipe(
        timeout(25000),
        switchMap(() => {
          return this.registrarService.loadRegistrars();
        })
      );
  }

  saveEppPassword(data: EppPasswordBackendModel) {
    return this.backend.postEppPasswordUpdate(data).pipe(
      switchMap(() => {
        return this.registrarService.loadRegistrars();
      })
    );
  }
}
