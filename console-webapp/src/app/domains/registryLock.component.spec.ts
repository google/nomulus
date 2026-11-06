// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatSnackBar } from '@angular/material/snack-bar';
import { signal } from '@angular/core';
import { of, throwError } from 'rxjs';
import { MaterialModule } from '../material.module';
import { BackendService } from '../shared/services/backend.service';
import { RegistrarService } from '../registrar/registrar.service';
import { UserDataService } from '../shared/services/userData.service';
import { BULK_ACTION_NAME, DomainListService } from './domainList.service';
import { RegistryLockService } from './registryLock.service';
import { RegistryLockComponent } from './registryLock.component';

describe('RegistryLockComponent, DomainListService & RegistryLockService', () => {
  let component: RegistryLockComponent;
  let fixture: ComponentFixture<RegistryLockComponent>;
  let domainListService: DomainListService;
  let registryLockService: RegistryLockService;
  let backendService: BackendService;
  let snackBarOpenSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RegistryLockComponent, MaterialModule, NoopAnimationsModule],
      providers: [
        BackendService,
        DomainListService,
        RegistryLockService,
        {
          provide: RegistrarService,
          useValue: {
            registrarId: signal('reg-1'),
            registrar: signal({ registryLockAllowed: true }),
          },
        },
        {
          provide: UserDataService,
          useValue: {
            userData: signal({ isAdmin: false }),
          },
        },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    backendService = TestBed.inject(BackendService);
    domainListService = TestBed.inject(DomainListService);
    registryLockService = TestBed.inject(RegistryLockService);

    domainListService.selectedDomain.set('locked.tld');
    domainListService.activeActionComponent.set(RegistryLockComponent);
    registryLockService.domainsLocks.set([{ domainName: 'locked.tld' }]);

    fixture = TestBed.createComponent(RegistryLockComponent);
    component = fixture.componentInstance;
    snackBarOpenSpy = vi
      .spyOn(fixture.debugElement.injector.get(MatSnackBar), 'open')
      .mockReturnValue({} as any);
    fixture.detectChanges();
  });

  it('should compute isLocked from domainsLocks and selectedDomain', () => {
    expect(component.isLocked()).toBe(true);
  });

  it('should retrieve domains and execute bulk actions in DomainListService', () => {
    const mockResult = {
      checkpointTime: '2024-05-01T00:00:00Z',
      domains: [],
      totalResults: 0,
    };
    const getDomainsSpy = vi
      .spyOn(backendService, 'getDomains')
      .mockReturnValue(of(mockResult));
    const bulkSpy = vi
      .spyOn(backendService, 'bulkDomainAction')
      .mockReturnValue(of({}));

    domainListService.retrieveDomains(1, 25, 100, 'test').subscribe();
    expect(getDomainsSpy).toHaveBeenCalledWith(
      'reg-1',
      undefined,
      1,
      25,
      100,
      'test'
    );
    expect(domainListService.checkpointTime).toBe('2024-05-01T00:00:00Z');

    domainListService
      .bulkDomainAction(
        ['locked.tld'],
        'reason',
        'reg-1',
        BULK_ACTION_NAME.DELETE
      )
      .subscribe();
    expect(bulkSpy).toHaveBeenCalledWith(
      ['locked.tld'],
      'reason',
      BULK_ACTION_NAME.DELETE,
      'reg-1'
    );
  });

  it('should retrieve locks and submit lock/unlock requests via RegistryLockService', () => {
    const getLocksSpy = vi
      .spyOn(backendService, 'getLocks')
      .mockReturnValue(of([{ domainName: 'newlock.tld' }]));
    const lockDomainSpy = vi
      .spyOn(backendService, 'registryLockDomain')
      .mockReturnValue(of({}));

    registryLockService.retrieveLocks().subscribe();
    expect(getLocksSpy).toHaveBeenCalledWith('reg-1');
    expect(registryLockService.domainsLocks()).toEqual([
      { domainName: 'newlock.tld' },
    ]);

    registryLockService
      .registryLockDomain('newlock.tld', 'pass123', 3600000, false)
      .subscribe();
    expect(lockDomainSpy).toHaveBeenCalledWith(
      'newlock.tld',
      'pass123',
      3600000,
      'reg-1',
      false
    );
  });

  it('should submit lock and unlock forms in RegistryLockComponent and goBack on completion', () => {
    const lockSpy = vi
      .spyOn(registryLockService, 'registryLockDomain')
      .mockReturnValue(of({}));

    component.lockDomain.setValue({ password: 'lockPassword' });
    component.save(true);
    expect(lockSpy).toHaveBeenCalledWith(
      'locked.tld',
      'lockPassword',
      undefined,
      true
    );
    expect(domainListService.selectedDomain()).toBeUndefined();
    expect(domainListService.activeActionComponent()).toBeNull();

    domainListService.selectedDomain.set('locked.tld');
    component.unlockDomain.setValue({
      password: 'unlockPassword',
      relockTime: 3600000 as any,
    });
    component.save(false);
    expect(lockSpy).toHaveBeenCalledWith(
      'locked.tld',
      'unlockPassword',
      3600000,
      false
    );

    lockSpy.mockReturnValueOnce(
      throwError(
        () => new HttpErrorResponse({ error: 'Invalid registry lock password' })
      )
    );
    component.save(true);
    expect(snackBarOpenSpy).toHaveBeenCalledWith(
      'Invalid registry lock password'
    );
  });
});
