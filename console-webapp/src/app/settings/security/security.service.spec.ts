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
} from 'src/app/registrar/registrar.service';
import { signal } from '@angular/core';
import { of } from 'rxjs';

describe('SecurityService', () => {
  const uiMockData: SecuritySettings = {
    clientCertificate: 'clientCertificateTest',
    failoverClientCertificate: 'failoverClientCertificateTest',
    ipAddressAllowList: [{ value: '123.123.123.123' }],
  };
  const apiMockData: SecuritySettingsBackendModel = {
    clientCertificate: 'clientCertificateTest',
    failoverClientCertificate: 'failoverClientCertificateTest',
    ipAddressAllowList: ['123.123.123.123'],
  };

  let service: SecurityService;
  let backendService: BackendService;
  let loadRegistrarsSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    loadRegistrarsSpy = vi.fn().mockReturnValue(of([]));
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
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    backendService = TestBed.inject(BackendService);
    service = TestBed.inject(SecurityService);
  });

  it('should convert from api to ui including empty defaults', () => {
    expect(apiToUiConverter(apiMockData)).toEqual(uiMockData);
    expect(apiToUiConverter()).toEqual({ ipAddressAllowList: [] });
  });

  it('should convert from ui to api and filter out empty IP entries', () => {
    expect(
      uiToApiConverter({
        ...uiMockData,
        ipAddressAllowList: [{ value: '123.123.123.123' }, { value: '' }],
      })
    ).toEqual(apiMockData);
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
