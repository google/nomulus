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

export interface Address {
  city?: string;
  countryCode?: string;
  state?: string;
  street?: string[];
  zip?: string;
}

export interface WhoisRegistrarFields {
  ianaIdentifier?: number;
  icannReferralEmail: string;
  localizedAddress: Address;
  registrarId: string;
  url: string;
  whoisServer: string;
}

export interface Registrar extends WhoisRegistrarFields {
  allowedTlds?: string[];
  billingAccountMap?: object;
  driveFolderId?: string;
  emailAddress?: string;
  faxNumber?: string;
  ipAddressAllowList?: string[];
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
      whoisServer: '127.0.0.1',
      url: 'https://good.web',
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
      whoisServer: '127.0.0.1',
      url: 'https://good.web',
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
      whoisServer: '127.0.0.1',
      url: 'https://good.web',
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
      whoisServer: '127.0.0.1',
      url: 'https://good.web',
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
      whoisServer: '127.0.0.1',
      url: 'https://good.web',
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
      whoisServer: '127.0.0.1',
      url: 'https://good.web',
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
      whoisServer: '127.0.0.1',
      url: 'https://good.web',
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
      whoisServer: '127.0.0.1',
      url: 'https://good.web',
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
      whoisServer: '127.0.0.1',
      url: 'https://good.web',
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
      whoisServer: '127.0.0.1',
      url: 'https://good.web',
    },
  ]);
  registrar = computed<Registrar | undefined>(() =>
    this.registrars().find((r) => r.registrarId === this.registrarId())
  );

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
