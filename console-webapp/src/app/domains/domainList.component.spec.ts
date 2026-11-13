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

import { ComponentFixture, TestBed } from '@angular/core/testing';

import {
  HttpErrorResponse,
  HttpStatusCode,
  provideHttpClient,
} from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MaterialModule } from '../material.module';
import { BackendService } from '../shared/services/backend.service';
import {
  DomainListComponent,
  ReasonDialogComponent,
  ResponseDialogComponent,
} from './domainList.component';
import { FormsModule } from '@angular/forms';
import {
  BULK_ACTION_NAME,
  Domain,
  DomainListService,
} from './domainList.service';
import { RegistryLockService } from './registryLock.service';
import { RegistrarService } from '../registrar/registrar.service';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatDialog } from '@angular/material/dialog';
import { signal } from '@angular/core';
import { of, throwError } from 'rxjs';
import { PageEvent } from '@angular/material/paginator';
import { RegistryLockComponent } from './registryLock.component';
import { RESTRICTED_ELEMENTS } from '../shared/directives/userLevelVisiblity.directive';

describe('DomainListComponent', () => {
  let component: DomainListComponent;
  let fixture: ComponentFixture<DomainListComponent>;
  let domainListService: DomainListService;
  let registryLockService: RegistryLockService;
  let registrarIdSignal: ReturnType<typeof signal<string>>;
  let snackBarOpenSpy: ReturnType<typeof vi.spyOn>;
  let dialogOpenSpy: ReturnType<typeof vi.spyOn>;

  const activeDomain: Domain = {
    domainName: 'example.tld',
    currentSponsorRegistrarId: 'reg-1',
    creationTime: { creationTime: '2024-01-01T00:00:00Z' },
    registrationExpirationTime: '2025-01-01T00:00:00Z',
    statuses: ['OK'],
  };

  const suspendedDomain: Domain = {
    domainName: 'suspended.tld',
    currentSponsorRegistrarId: 'reg-1',
    creationTime: { creationTime: '2024-01-01T00:00:00Z' },
    registrationExpirationTime: '2025-01-01T00:00:00Z',
    statuses: [
      'SERVER_RENEW_PROHIBITED',
      'SERVER_TRANSFER_PROHIBITED',
      'SERVER_UPDATE_PROHIBITED',
      'SERVER_DELETE_PROHIBITED',
      'SERVER_HOLD',
    ],
  };

  beforeEach(async () => {
    registrarIdSignal = signal('reg-1');

    await TestBed.configureTestingModule({
      imports: [DomainListComponent, MaterialModule, FormsModule],
      providers: [
        BackendService,
        DomainListService,
        RegistryLockService,
        {
          provide: RegistrarService,
          useValue: {
            registrarId: registrarIdSignal,
            registrars: signal([]),
            registrar: signal(undefined),
          },
        },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    domainListService = TestBed.inject(DomainListService);
    registryLockService = TestBed.inject(RegistryLockService);

    vi.spyOn(registryLockService, 'retrieveLocks').mockImplementation(() => {
      registryLockService.domainsLocks.set([{ domainName: 'example.tld' }]);
      return of(registryLockService.domainsLocks());
    });
    vi.spyOn(domainListService, 'retrieveDomains').mockImplementation(() => {
      domainListService.domainsList.set([activeDomain, suspendedDomain]);
      return of({
        checkpointTime: '2024-06-01T00:00:00Z',
        domains: domainListService.domainsList(),
        totalResults: 2,
      });
    });

    fixture = TestBed.createComponent(DomainListComponent);
    component = fixture.componentInstance;
    snackBarOpenSpy = vi
      .spyOn(fixture.debugElement.injector.get(MatSnackBar), 'open')
      .mockReturnValue({} as any);
    dialogOpenSpy = vi
      .spyOn(fixture.debugElement.injector.get(MatDialog), 'open')
      .mockReturnValue({
        afterClosed: () => of('admin policy reason'),
      } as any);
    fixture.detectChanges();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('should load domains and registry locks when registrarId is present', () => {
    expect(registryLockService.retrieveLocks).toHaveBeenCalled();
    expect(domainListService.retrieveDomains).toHaveBeenCalledWith(
      0,
      50,
      0,
      undefined
    );
    expect(component.dataSource.data).toEqual([activeDomain, suspendedDomain]);
    expect(component.totalResults()).toBe(2);
    expect(component.isLoading()).toBe(false);
  });

  it('should suppress snackbar on 403 Forbidden from retrieveLocks but display other errors', () => {
    vi.spyOn(registryLockService, 'retrieveLocks').mockReturnValueOnce(
      throwError(
        () =>
          new HttpErrorResponse({
            status: HttpStatusCode.Forbidden,
            statusText: 'Forbidden',
          })
      )
    );
    component.loadLocks();
    expect(snackBarOpenSpy).not.toHaveBeenCalled();

    vi.spyOn(registryLockService, 'retrieveLocks').mockReturnValueOnce(
      throwError(
        () =>
          new HttpErrorResponse({
            status: HttpStatusCode.InternalServerError,
            statusText: 'Server Error',
          })
      )
    );
    component.loadLocks();
    expect(snackBarOpenSpy).toHaveBeenCalled();
  });

  it('should identify locked and unsuspendable domains accurately', () => {
    expect(component.isDomainLocked('example.tld')).toBe(true);
    expect(component.isDomainLocked('suspended.tld')).toBe(false);

    expect(component.isDomainUnsuspendable(activeDomain)).toBe(false);
    expect(component.isDomainUnsuspendable(suspendedDomain)).toBe(true);
  });

  it('should toggle all rows selection and compute checkbox labels', () => {
    expect(component.checkboxLabel()).toBe('select all');
    expect(component.checkboxLabel(activeDomain)).toBe(
      'select row example.tld'
    );

    component.toggleAllRows();
    expect(component.isAllSelected).toBe(true);
    expect(component.selection.selected).toHaveLength(2);
    expect(component.checkboxLabel()).toBe('deselect all');
    expect(component.checkboxLabel(activeDomain)).toBe(
      'deselect row example.tld'
    );

    component.toggleAllRows();
    expect(component.isAllSelected).toBe(false);
    expect(component.selection.selected).toHaveLength(0);
  });

  it('should update pagination, clear selection, and reload domains on page change', () => {
    component.selection.select(activeDomain);
    const pageEvent: PageEvent = {
      pageIndex: 2,
      pageSize: 25,
      length: 100,
    };

    component.onPageChange(pageEvent);

    expect(component.pageNumber).toBe(2);
    expect(component.resultsPerPage).toBe(25);
    expect(component.selection.selected).toHaveLength(0);
    expect(domainListService.retrieveDomains).toHaveBeenLastCalledWith(
      2,
      25,
      2,
      undefined
    );
  });

  it('should debounce search input before reloading domains', () => {
    vi.useFakeTimers();
    const retrieveSpy = vi.spyOn(domainListService, 'retrieveDomains');
    retrieveSpy.mockClear();

    component.searchTerm = 'example';
    component.sendInput();
    expect(retrieveSpy).not.toHaveBeenCalled();

    vi.advanceTimersByTime(500);
    expect(retrieveSpy).toHaveBeenCalledWith(0, 50, 2, 'example');
  });

  it('should open registry lock view for selected domain', () => {
    component.openRegistryLock('example.tld');
    expect(domainListService.selectedDomain()).toBe('example.tld');
    expect(domainListService.activeActionComponent()).toBe(
      RegistryLockComponent
    );
  });

  it('should return restricted element IDs for bulk delete and suspend', () => {
    expect(component.getElementIdForBulkDelete()).toBe(
      RESTRICTED_ELEMENTS.BULK_DELETE
    );
    expect(component.getElementIdForSuspendUnsuspend()).toBe(
      RESTRICTED_ELEMENTS.SUSPEND
    );
  });

  it('should execute bulk delete via ReasonDialog and display ResponseDialog summary', () => {
    const bulkResult = {
      'example.tld': { message: 'Deleted', responseCode: '1000' },
      'suspended.tld': { message: 'In use', responseCode: '2304' },
    };
    const bulkSpy = vi
      .spyOn(domainListService, 'bulkDomainAction')
      .mockReturnValue(of(bulkResult));

    component.selection.select(activeDomain, suspendedDomain);
    component.deleteSelectedDomains();

    expect(dialogOpenSpy).toHaveBeenCalledWith(ReasonDialogComponent, {
      data: { operation: 'deleting' },
    });
    expect(bulkSpy).toHaveBeenCalledWith(
      ['example.tld', 'suspended.tld'],
      'admin policy reason',
      'reg-1',
      BULK_ACTION_NAME.DELETE
    );
    expect(dialogOpenSpy).toHaveBeenCalledWith(ResponseDialogComponent, {
      data: {
        title: 'Domain Deletion Results',
        content: [
          'Successfully deleted - 1 domain(s)',
          'Failed to delete - 1 domain(s)',
          'Some domains could not be deleted due to ongoing processes or server errors. Please check the table for more information.',
        ],
      },
    });
    expect(component.getOperationMessage('suspended.tld')).toBe('In use');
    expect(component.getOperationMessage('other.tld')).toBe('');
  });

  it('should execute suspend and unsuspend requests and handle 2xxx error codes', () => {
    const bulkSpy = vi
      .spyOn(domainListService, 'bulkDomainAction')
      .mockReturnValueOnce(
        of({
          'example.tld': { message: 'Suspended OK', responseCode: '1000' },
        })
      )
      .mockReturnValueOnce(
        of({
          'suspended.tld': {
            message: 'Cannot unsuspend',
            responseCode: '2304',
          },
        })
      );

    component.onSuspendClick('example.tld');
    expect(bulkSpy).toHaveBeenCalledWith(
      ['example.tld'],
      'admin policy reason',
      'reg-1',
      BULK_ACTION_NAME.SUSPEND
    );

    component.onUnsuspendClick('suspended.tld');
    expect(bulkSpy).toHaveBeenCalledWith(
      ['suspended.tld'],
      'admin policy reason',
      'reg-1',
      BULK_ACTION_NAME.UNSUSPEND
    );
    expect(snackBarOpenSpy).toHaveBeenCalledWith('Cannot unsuspend');
  });
});
