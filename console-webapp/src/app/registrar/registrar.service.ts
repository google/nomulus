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

import { Injectable, computed, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

import { BackendService } from '../shared/services/backend.service';
import {
  GlobalLoader,
  GlobalLoaderService,
} from '../shared/services/globalLoader.service';
import { MatSnackBar } from '@angular/material/snack-bar';

export interface Address {
  street?: string[];
  city?: string;
  countryCode?: string;
  zip?: string;
  state?: string;
}

export interface Registrar {
  allowedTlds?: string[];
  billingAccountMap?: object;
  driveFolderId?: string;
  emailAddress?: string;
  faxNumber?: string;
  ianaIdentifier?: number;
  icannReferralEmail?: string;
  ipAddressAllowList?: string[];
  localizedAddress?: Address;
  phoneNumber?: string;
  registrarId: string;
  registrarName: string;
  registryLockAllowed?: boolean;
  url?: string;
  whoisServer?: string;
}

@Injectable({
  providedIn: 'root',
})
export class RegistrarService implements GlobalLoader {
  registrarId = signal<string>('');
  registrars = signal<Registrar[]>([]);
  registrar = computed<Registrar | undefined>(() =>
    this.registrars().find((r) => r.registrarId === this.registrarId())
  );

  constructor(
    private backend: BackendService,
    private globalLoader: GlobalLoaderService,
    private _snackBar: MatSnackBar
  ) {
    this.loadRegistrars().subscribe((r) => {
      this.globalLoader.stopGlobalLoader(this);
    });
    this.globalLoader.startGlobalLoader(this);
  }

  public updateSelectedRegistrar(registrarId: string) {
    this.registrarId.set(registrarId);
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

  loadingTimeout() {
    this._snackBar.open('Timeout loading registrars');
  }
}
