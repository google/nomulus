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
  registrars = signal<Registrar[]>([
    {
      registrarId: 'larrytest2',
      registrarName: 'larrytest2',
      allowedTlds: ['com'],
      ipAddressAllowList: [
        '123.123.123.123/32',
        '1.0.0.1/32',
        '1.0.0.4/32',
        '1.0.0.5/32',
        '1.0.0.6/32',
      ],
      localizedAddress: {
        street: ['none'],
        city: 'nowhere',
        state: 'NA',
        zip: '12345',
        countryCode: 'us',
      },
      emailAddress: 'larryruili@google.com',
      ianaIdentifier: 43211,
      billingAccountMap: { JPY: '123', USD: '456' },
      icannReferralEmail: 'larryruili@google.com',
      registryLockAllowed: false,
    },
    {
      registrarId: 'larrytest',
      registrarName: 'larrytest',
      allowedTlds: ['app', 'com', 'how', 'dev'],
      ipAddressAllowList: [
        '123.123.123.123/32',
        '1.0.0.1/32',
        '1.0.0.2/32',
        '1.0.0.3/32',
      ],
      localizedAddress: {
        street: ['e-street'],
        city: 'citysville',
        state: 'NY',
        zip: '12345',
        countryCode: 'US',
      },
      emailAddress: 'larryruili@google.com',
      ianaIdentifier: 77788,
      billingAccountMap: { JPY: '123', USD: '456' },
      icannReferralEmail: 'larryruili@google.com',
      registryLockAllowed: false,
    },
    {
      registrarId: 'charlestonroad22',
      registrarName: 'CRR TEST STUFF',
      allowedTlds: ['how-12.test'],
      localizedAddress: {
        street: ['a'],
        city: 'b',
        state: 'ee',
        zip: '12345',
        countryCode: 'dd',
      },
      emailAddress: 'fake@fake.com',
      ianaIdentifier: 4499,
      billingAccountMap: { JPY: '123', USD: '456' },
      icannReferralEmail: 'example@google.com',
      registryLockAllowed: false,
    },
    {
      registrarId: 'NewRegistrar',
      registrarName: 'NewRegistrar',
      ipAddressAllowList: ['123.123.123.123/32', '1.0.0.1/32'],
      localizedAddress: {
        street: ['address details'],
        city: 'Sunnyplace',
        countryCode: 'US',
      },
      ianaIdentifier: 12345,
      billingAccountMap: { USD: '474184cc-ebf0-44d3-b622-269d819d3667' },
      icannReferralEmail: 'admin@registrar.com',
      driveFolderId: '342',
      registryLockAllowed: false,
    },
    {
      registrarId: 'guybentest',
      registrarName: 'guyben test',
      ipAddressAllowList: ['123.0.0.1/32'],
      localizedAddress: {
        street: ['XXX'],
        city: 'XXX',
        state: 'XX',
        zip: '00000',
        countryCode: 'XX',
      },
      ianaIdentifier: 44444,
      billingAccountMap: { JPY: '123', USD: '456' },
      icannReferralEmail: 'icann@example.com',
      registryLockAllowed: false,
    },
    {
      registrarId: 'jianglai-test3',
      registrarName: 'Test registrar3',
      localizedAddress: {
        street: ['123 Main Street'],
        city: 'New York',
        state: 'New York',
        zip: '11111',
        countryCode: 'US',
      },
      ianaIdentifier: 12346,
      billingAccountMap: { JPY: '123', USD: '456' },
      icannReferralEmail: 'test3@test.test',
      registryLockAllowed: false,
    },
    {
      registrarId: 'jianglai-test',
      registrarName: 'Test registrar',
      localizedAddress: {
        street: ['123 Main Street'],
        city: 'New York',
        state: 'New York',
        zip: '11111',
        countryCode: 'US',
      },
      ianaIdentifier: 12345,
      billingAccountMap: { JPY: '123', USD: '456' },
      icannReferralEmail: 'test@test.test',
      registryLockAllowed: false,
    },
    {
      registrarId: 'gbrodman',
      registrarName: 'gbrodman',
      allowedTlds: ['app', 'dev', 'test'],
      localizedAddress: {
        street: ['asdf'],
        city: 'NYC',
        state: 'NY',
        zip: '10011',
        countryCode: 'us',
      },
      emailAddress: 'legina@google.com',
      ianaIdentifier: 12387,
      billingAccountMap: { JPY: '123', USD: '456' },
      icannReferralEmail: 'test@test.test',
      registryLockAllowed: true,
    },
    {
      registrarId: 'contacts-alpha',
      registrarName: 'contacts-alpha',
      ipAddressAllowList: ['123.123.123.123/32'],
      localizedAddress: {
        street: ['76 9th Avenue'],
        city: 'New York',
        state: 'NY',
        zip: '10011',
        countryCode: 'US',
      },
      ianaIdentifier: 7788,
      billingAccountMap: { JPY: '123', USD: '456' },
      icannReferralEmail: 'weiminyu@google.com',
      registryLockAllowed: false,
    },
    {
      registrarId: 'jianglai-test2',
      registrarName: 'Test registrar2',
      localizedAddress: {
        street: ['123 Main Street'],
        city: 'New York',
        state: 'New York',
        zip: '11111',
        countryCode: 'US',
      },
      ianaIdentifier: 12345,
      billingAccountMap: { JPY: '123', USD: '456' },
      icannReferralEmail: 'test@test.test',
      registryLockAllowed: false,
    },
  ]);
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
