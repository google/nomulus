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

import { Injectable, computed, signal } from '@angular/core';
import { Observable, switchMap, tap } from 'rxjs';

import { MatSnackBar } from '@angular/material/snack-bar';
import { Router } from '@angular/router';
import { OteCreateResponse } from '../ote/newOte.component';
import { OteStatusResponse } from '../ote/oteStatus.component';
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
  type?: string;
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

  registrarsLoaded: Promise<void>;

  constructor(
    private backend: BackendService,
    private globalLoader: GlobalLoaderService,
    private _snackBar: MatSnackBar,
    private router: Router
  ) {
    this.registrarsLoaded = new Promise((resolve) => {
      this.loadRegistrars().subscribe((r) => {
        this.globalLoader.stopGlobalLoader(this);
        resolve();
      });
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

  createRegistrar(registrar: Registrar) {
    return this.backend
      .createRegistrar(registrar)
      .pipe(switchMap((_) => this.loadRegistrars()));
  }

  updateRegistrar(updatedRegistrar: Registrar) {
    return this.backend.updateRegistrar(updatedRegistrar).pipe(
      tap(() => {
        this.registrars.set(
          this.registrars().map((r) => {
            if (r.registrarId === updatedRegistrar.registrarId) {
              return updatedRegistrar;
            }
            return r;
          })
        );
      })
    );
  }

  loadingTimeout() {
    this._snackBar.open('Timeout loading registrars');
  }

  generateOte(
    oteForm: Object,
    registrarId: string
  ): Observable<OteCreateResponse> {
    return this.backend
      .generateOte(oteForm, registrarId)
      .pipe(tap((_) => this.loadRegistrars()));
  }

  oteStatus(registrarId: string): Observable<OteStatusResponse[]> {
    return this.backend.getOteStatus(registrarId);
  }
}
