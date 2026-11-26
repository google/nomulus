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

import { TestBed } from '@angular/core/testing';

import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { BackendService } from 'src/app/shared/services/backend.service';
import {
  EppPasswordBackendModel,
  SecurityService,
  apiToUiConverter,
  uiToApiConverter,
} from './security.service';
import {
  RegistrarService,
  SecuritySettings,
  SecuritySettingsBackendModel,
  StringListItem,
} from 'src/app/registrar/registrar.service';
import {
  UserData,
  UserDataService,
} from 'src/app/shared/services/userData.service';
import { signal, WritableSignal } from '@angular/core';
import { of } from 'rxjs';

describe('SecurityService', () => {
  const uiMockData: SecuritySettings = {
    clientCertificate: 'clientCertificateTest',
    failoverClientCertificate: 'failoverClientCertificateTest',
    ipAddressAllowList: [{ value: '123.123.123.123' }],
    allowedEppClientCertificateHostnames: [{ value: 'epp.registrar.example' }],
    revokedClientCertificateHashes: [{ value: 'abc123revokedhash' }],
  };
  const apiMockData: SecuritySettingsBackendModel = {
    clientCertificate: 'clientCertificateTest',
    failoverClientCertificate: 'failoverClientCertificateTest',
    ipAddressAllowList: ['123.123.123.123'],
    allowedEppClientCertificateHostnames: ['epp.registrar.example'],
    revokedClientCertificateHashes: ['abc123revokedhash'],
  };

  let service: SecurityService;
  let backendService: BackendService;
  let loadRegistrarsSpy: ReturnType<typeof vi.fn>;
  let userDataSignal: WritableSignal<UserData | undefined>;

  beforeEach(() => {
    loadRegistrarsSpy = vi.fn().mockReturnValue(of([]));
    userDataSignal = signal<UserData | undefined>(undefined);
    TestBed.configureTestingModule({
      providers: [
        MatSnackBar,
        SecurityService,
        BackendService,
        {
          provide: RegistrarService,
          useValue: {
            registrarId: signal('reg-1'),
            loadRegistrars: loadRegistrarsSpy,
          },
        },
        {
          provide: UserDataService,
          useValue: { userData: userDataSignal },
        },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    backendService = TestBed.inject(BackendService);
    service = TestBed.inject(SecurityService);
  });

  it('should convert from api to ui including empty defaults', () => {
    expect(apiToUiConverter(apiMockData)).toEqual(uiMockData);
    expect(apiToUiConverter()).toEqual({
      ipAddressAllowList: [],
      allowedEppClientCertificateHostnames: [],
      revokedClientCertificateHashes: [],
    });
  });

  it('should convert from ui to api and trim/filter out empty entries', () => {
    expect(
      uiToApiConverter({
        ...uiMockData,
        ipAddressAllowList: [{ value: '123.123.123.123' }, { value: '' }],
        allowedEppClientCertificateHostnames: [
          { value: ' epp.registrar.example ' },
          { value: '   ' },
          { value: '' },
        ],
        revokedClientCertificateHashes: [
          { value: ' abc123revokedhash ' },
          { value: '  ' },
        ],
      })
    ).toEqual(apiMockData);
  });

  it('should omit hostname and revoked hash fields from api payload when undefined', () => {
    const converted = uiToApiConverter({
      clientCertificate: 'clientCertificateTest',
      ipAddressAllowList: [{ value: '123.123.123.123' }],
    });
    expect(converted).toEqual({
      clientCertificate: 'clientCertificateTest',
      ipAddressAllowList: ['123.123.123.123'],
    });
    expect('allowedEppClientCertificateHostnames' in converted).toBe(false);
    expect('revokedClientCertificateHashes' in converted).toBe(false);
  });

  it('should preserve explicit empty arrays and default missing ipAddressAllowList', () => {
    const emptyDefault = uiToApiConverter({});
    expect(emptyDefault).toEqual({ ipAddressAllowList: [] });
    expect('allowedEppClientCertificateHostnames' in emptyDefault).toBe(false);
    expect('revokedClientCertificateHashes' in emptyDefault).toBe(false);

    const cleared = uiToApiConverter({
      allowedEppClientCertificateHostnames: [],
      revokedClientCertificateHashes: [{ value: '   ' }],
    });
    expect(cleared).toEqual({
      ipAddressAllowList: [],
      allowedEppClientCertificateHostnames: [],
      revokedClientCertificateHashes: [],
    });
    expect('allowedEppClientCertificateHostnames' in cleared).toBe(true);
    expect('revokedClientCertificateHashes' in cleared).toBe(true);
  });

  it('should handle null or undefined item values and null list fields safely', () => {
    const withNullItems = uiToApiConverter({
      ipAddressAllowList: [{ value: '123.123.123.123' }],
      allowedEppClientCertificateHostnames: [
        { value: ' epp.registrar.example ' },
        { value: null as unknown as string },
        { value: undefined as unknown as string },
        null as unknown as StringListItem,
      ],
      revokedClientCertificateHashes: null as unknown as Array<StringListItem>,
    });
    expect(withNullItems).toEqual({
      ipAddressAllowList: ['123.123.123.123'],
      allowedEppClientCertificateHostnames: ['epp.registrar.example'],
    });
    expect('revokedClientCertificateHashes' in withNullItems).toBe(false);

    const withNullHostnames = uiToApiConverter({
      ipAddressAllowList: [{ value: '123.123.123.123' }],
      allowedEppClientCertificateHostnames:
        null as unknown as Array<StringListItem>,
      revokedClientCertificateHashes: [
        { value: ' abc123revokedhash ' },
        { value: null as unknown as string },
      ],
    });
    expect(withNullHostnames).toEqual({
      ipAddressAllowList: ['123.123.123.123'],
      revokedClientCertificateHashes: ['abc123revokedhash'],
    });
    expect('allowedEppClientCertificateHostnames' in withNullHostnames).toBe(
      false
    );
  });

  it('should reflect allowEppClientCertHostnameAuth from UserDataService', () => {
    expect(service.allowEppClientCertHostnameAuth()).toBe(false);
    userDataSignal.set({
      allowEppClientCertHostnameAuth: false,
      globalRole: 'FTE',
      isAdmin: true,
      productName: 'Nomulus',
      supportEmail: 'support@example.com',
      supportPhoneNumber: '+1',
      technicalDocsUrl: 'test',
    });
    expect(service.allowEppClientCertHostnameAuth()).toBe(false);
    userDataSignal.update((u) => ({
      ...u!,
      allowEppClientCertHostnameAuth: true,
    }));
    expect(service.allowEppClientCertHostnameAuth()).toBe(true);
  });

  it('should save security settings via backend and reload registrars', () => {
    const postSpy = vi
      .spyOn(backendService, 'postSecuritySettings')
      .mockReturnValue(of(apiMockData));

    service.saveChanges(uiMockData).subscribe();

    expect(postSpy).toHaveBeenCalledWith('reg-1', apiMockData);
    expect(loadRegistrarsSpy).toHaveBeenCalled();
  });

  it('should save EPP password update via backend and reload registrars', () => {
    const payload: EppPasswordBackendModel = {
      registrarId: 'reg-1',
      oldPassword: 'oldPassword1',
      newPassword: 'newPassword1',
      newPasswordRepeat: 'newPassword1',
    };
    const postSpy = vi
      .spyOn(backendService, 'postEppPasswordUpdate')
      .mockReturnValue(of(payload));

    service.saveEppPassword(payload).subscribe();

    expect(postSpy).toHaveBeenCalledWith(payload);
    expect(loadRegistrarsSpy).toHaveBeenCalled();
  });
});
