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

import { Injectable } from '@angular/core';
import { tap } from 'rxjs';
import { RegistrarService } from 'src/app/registrar/registrar.service';
import { BackendService } from 'src/app/shared/services/backend.service';

interface ipAllowListItem {
  value: string;
}
export interface SecuritySettings {
  clientCertificate?: string;
  failoverClientCertificate?: string;
  ipAddressAllowList?: Array<ipAllowListItem>;
}

export interface SecuritySettingsBackendModel {
  clientCertificate?: string;
  failoverClientCertificate?: string;
  ipAddressAllowList?: Array<string>;
}

@Injectable()
export class SecurityService {
  securitySettings: SecuritySettings = {};

  constructor(
    private backend: BackendService,
    private registrarService: RegistrarService
  ) {}

  fetchSecurityDetails() {
    return this.backend
      .getSecuritySettings(this.registrarService.activeRegistrarId)
      .pipe(
        tap((securitySettings: SecuritySettingsBackendModel) => {
          if(securitySettings) {
            this.securitySettings = Object.assign({}, securitySettings, {
              ipAddressAllowList: (securitySettings.ipAddressAllowList || []).map(
                (value) => ({ value })
              ),
            });
          } 
        })
      );
  }

  saveChanges(newSecuritySettings: SecuritySettings) {
    return this.backend.postSecuritySettings(
      this.registrarService.activeRegistrarId,
      Object.assign({}, newSecuritySettings, {
        ipAddressAllowList: (newSecuritySettings.ipAddressAllowList || [])
        .filter(s => s.value)
        .map(
          (ipAllowItem: ipAllowListItem) => ipAllowItem.value
        ),
      })
    )
    .pipe(
      tap((_) => {
        this.securitySettings = newSecuritySettings;
      })
    );
  }
}
