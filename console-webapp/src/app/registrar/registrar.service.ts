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

import { Injectable, computed, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

import { MatSnackBar } from '@angular/material/snack-bar';
import { Router } from '@angular/router';
import { BackendService } from '../shared/services/backend.service';
import {
  GlobalLoader,
  GlobalLoaderService,
} from '../shared/services/globalLoader.service';

export interface IpAllowListItem {
  value: string;
}

export interface Address {
  city?: string;
  countryCode?: string;
  state?: string;
  street?: string[];
  zip?: string;
}

export interface SecuritySettingsBackendModel {
  clientCertificate?: string;
  failoverClientCertificate?: string;
  ipAddressAllowList?: Array<string>;
  // TODO: @ptkach At some point we want to add a back-end support for this
  eppPasswordLastUpdated?: string;
}

export interface SecuritySettings
  extends Omit<SecuritySettingsBackendModel, 'ipAddressAllowList'> {
  ipAddressAllowList?: Array<IpAllowListItem>;
}

export interface WhoisRegistrarFields {
  ianaIdentifier?: number;
  icannReferralEmail: string;
  localizedAddress: Address;
  registrarId: string;
  url: string;
  whoisServer: string;
}

export interface Registrar
  extends WhoisRegistrarFields,
    SecuritySettingsBackendModel {
  allowedTlds?: string[];
  billingAccountMap?: object;
  driveFolderId?: string;
  emailAddress?: string;
  faxNumber?: string;
  phoneNumber?: string;
  registrarId: string;
  registrarName: string;
  registryLockAllowed?: boolean;
}

@Injectable({
  providedIn: 'root',
})
export class RegistrarService implements GlobalLoader {
  registrarId = signal<string>(
    new URLSearchParams(document.location.hash.split('?')[1]).get(
      'registrarId'
    ) || ''
  );
  registrars = signal<Registrar[]>([]);
  registrar = computed<Registrar | undefined>(() =>
    this.registrars().find((r) => r.registrarId === this.registrarId())
  );

  inNewRegistrarMode = signal(false);

  constructor(
    private backend: BackendService,
    private globalLoader: GlobalLoaderService,
    private _snackBar: MatSnackBar,
    private router: Router
  ) {
    this.loadRegistrars().subscribe((r) => {
      this.globalLoader.stopGlobalLoader(this);
    });
    this.globalLoader.startGlobalLoader(this);
  }

  public updateSelectedRegistrar(registrarId: string) {
    if (registrarId !== this.registrarId()) {
      this.registrarId.set(registrarId);
      // add registrarId to url query params, so that we can pick it up after page refresh
      this.router.navigate([], {
        queryParams: { registrarId },
        queryParamsHandling: 'merge',
      });
    }
  }

  public loadRegistrars(): Observable<Registrar[]> {
    return this.backend.getRegistrars().pipe(
      tap((registrars) => {
        if (registrars) {
          this.registrars.set(registrars);
        }
      })
    );
  }

  saveRegistrar(registrar: Registrar) {
    return this.backend.postRegistrar(registrar).pipe(
      tap((registrar) => {
        if (registrar) {
          this.registrars.set(
            this.registrars().map((r) => {
              if (r.registrarId === registrar.registrarId) {
                return registrar;
              }
              return r;
            })
          );
        }
      })
    );
  }

  loadingTimeout() {
    this._snackBar.open('Timeout loading registrars');
  }
}
