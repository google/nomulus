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
import { signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatDialog } from '@angular/material/dialog';
import { PageEvent } from '@angular/material/paginator';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, Subject, tap, throwError } from 'rxjs';
import { MaterialModule } from '../material.module';
import { RegistrarService } from '../registrar/registrar.service';
import { RESTRICTED_ELEMENTS } from '../shared/directives/userLevelVisiblity.directive';
import { BackendService } from '../shared/services/backend.service';
import {
  DomainListComponent,
  ReasonDialogComponent,
  ResponseDialogComponent,
} from './domainList.component';
import {
  BULK_ACTION_NAME,
  Domain,
  DomainListResult,
  DomainListService,
} from './domainList.service';
import { RegistryLockComponent } from './registryLock.component';
import { DomainLocksResult, RegistryLockService } from './registryLock.service';

describe('DomainListComponent', () => {
  let component: DomainListComponent;
  let fixture: ComponentFixture<DomainListComponent>;
  let backendService: BackendService;
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

    backendService = TestBed.inject(BackendService);
    domainListService = TestBed.inject(DomainListService);
    registryLockService = TestBed.inject(RegistryLockService);

    vi.spyOn(registryLockService, 'retrieveLocks').mockImplementation(() => {
      registryLockService.domainsLocks.set([{ domainName: 'example.tld' }]);
      return of(registryLockService.domainsLocks());
    });
    vi.spyOn(domainListService, 'retrieveDomains').mockImplementation(() => {
      domainListService.checkpointTime = '2024-06-01T00:00:00Z';
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
        close: vi.fn(),
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
      undefined,
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

  it('should toggle all rows without poisoning SelectionModel.compareWith on individual deselection', () => {
    expect(component.checkboxLabel()).toBe('select all');
    expect(component.checkboxLabel(activeDomain)).toBe(
      'select row example.tld'
    );

    component.toggleAllRows();
    expect(component.isAllSelected()).toBe(true);
    expect(component.isSomeSelected()).toBe(false);
    expect(component.selection.selected).toHaveLength(2);
    expect(component.checkboxLabel()).toBe('deselect all');
    expect(component.checkboxLabel(activeDomain)).toBe(
      'deselect row example.tld'
    );

    // Deselecting one row after select-all must leave the other row selected
    // and transition header checkbox to indeterminate (isAllSelected = false).
    component.selection.toggle(activeDomain);
    expect(component.isAllSelected()).toBe(false);
    expect(component.isSomeSelected()).toBe(true);
    expect(component.selection.selected).toEqual([suspendedDomain]);
    expect(component.isRowSelected(activeDomain)).toBe(false);
    expect(component.isRowSelected(suspendedDomain)).toBe(true);

    // Re-selecting the remaining row restores isAllSelected = true.
    component.selection.toggle(activeDomain);
    expect(component.isAllSelected()).toBe(true);
    expect(component.isSomeSelected()).toBe(false);

    component.toggleAllRows();
    expect(component.isAllSelected()).toBe(false);
    expect(component.selection.selected).toHaveLength(0);
  });

  it('should keep the search input mounted when a filtered search returns 0 results', () => {
    vi.useFakeTimers();
    vi.spyOn(domainListService, 'retrieveDomains').mockImplementationOnce(
      () => {
        domainListService.domainsList.set([]);
        return of({
          checkpointTime: '2024-06-02T00:00:00Z',
          domains: [],
          totalResults: 0,
        });
      }
    );

    component.onSearchInput('no-match');
    vi.advanceTimersByTime(500);
    fixture.detectChanges();

    expect(component.totalResults()).toBe(0);
    expect(component.isEmptyRegistrar()).toBe(false);
    expect(
      fixture.nativeElement.querySelector('input[type="search"]')
    ).not.toBeNull();
    expect(
      fixture.nativeElement.querySelector('.console-app__empty-domains')
    ).toBeNull();

    // Clearing the filter before debounce fires must still keep the search input mounted.
    component.onSearchInput('');
    fixture.detectChanges();
    expect(component.isEmptyRegistrar()).toBe(false);
    expect(
      fixture.nativeElement.querySelector('input[type="search"]')
    ).not.toBeNull();

    // Once the unfiltered query runs and returns 0 domains, show the empty registrar view.
    vi.spyOn(domainListService, 'retrieveDomains').mockImplementationOnce(
      () => {
        domainListService.domainsList.set([]);
        return of({
          checkpointTime: '2024-06-03T00:00:00Z',
          domains: [],
          totalResults: 0,
        });
      }
    );
    vi.advanceTimersByTime(500);
    fixture.detectChanges();
    expect(component.isEmptyRegistrar()).toBe(true);
    expect(
      fixture.nativeElement.querySelector('.console-app__empty-domains')
    ).not.toBeNull();
  });

  it('should update pagination, clear selection, and reset isAllSelected on page change', () => {
    component.toggleAllRows();
    expect(component.isAllSelected()).toBe(true);

    const pageEvent: PageEvent = {
      pageIndex: 2,
      pageSize: 25,
      length: 100,
    };

    component.onPageChange(pageEvent);

    expect(component.pageNumber()).toBe(2);
    expect(component.resultsPerPage()).toBe(25);
    expect(component.selection.selected).toHaveLength(0);
    expect(component.isAllSelected()).toBe(false);
    expect(domainListService.retrieveDomains).toHaveBeenLastCalledWith(
      2,
      25,
      2,
      undefined
    );
  });

  it('should debounce search input and reset stale pageNumber, checkpointTime, totalResults, and selection', () => {
    vi.useFakeTimers();
    component.onPageChange({ pageIndex: 3, pageSize: 25, length: 200 });
    component.toggleAllRows();
    domainListService.checkpointTime = '2024-06-01T00:00:00Z';

    const retrieveSpy = vi.spyOn(domainListService, 'retrieveDomains');
    const clearCursorSpy = vi.spyOn(domainListService, 'clearPaginationCursor');
    retrieveSpy.mockClear();

    component.onSearchInput('example');
    expect(retrieveSpy).not.toHaveBeenCalled();

    vi.advanceTimersByTime(500);
    expect(clearCursorSpy).toHaveBeenCalled();
    expect(component.pageNumber()).toBe(0);
    expect(component.selection.selected).toHaveLength(0);
    expect(component.isAllSelected()).toBe(false);
    expect(retrieveSpy).toHaveBeenCalledWith(0, 25, undefined, 'example');
  });

  it('should clear stale checkpointTime in DomainListService when searchTerm changes', () => {
    const getDomainsSpy = vi
      .spyOn(backendService, 'getDomains')
      .mockReturnValue(
        of({
          checkpointTime: '2024-06-01T00:00:00Z',
          domains: [activeDomain],
          totalResults: 1,
        })
      );
    (domainListService.retrieveDomains as any).mockRestore();

    domainListService.retrieveDomains(0, 50, undefined, undefined).subscribe();
    expect(domainListService.checkpointTime).toBe('2024-06-01T00:00:00Z');

    // Same search term keeps checkpointTime for stable pagination
    domainListService.retrieveDomains(1, 50, 1, undefined).subscribe();
    expect(getDomainsSpy).toHaveBeenLastCalledWith(
      'reg-1',
      '2024-06-01T00:00:00Z',
      1,
      50,
      1,
      undefined
    );

    // Changing searchTerm clears stale checkpointTime before calling backend
    domainListService.retrieveDomains(0, 50, undefined, 'example').subscribe();
    expect(getDomainsSpy).toHaveBeenLastCalledWith(
      'reg-1',
      undefined,
      0,
      50,
      undefined,
      'example'
    );
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

  it('should execute bulk delete via ReasonDialog, display ResponseDialog, and reset selection and pagination cursor', () => {
    const bulkResult = {
      'example.tld': { message: 'Deleted', responseCode: '1000' },
      'suspended.tld': { message: 'In use', responseCode: '2304' },
    };
    const bulkSpy = vi
      .spyOn(domainListService, 'bulkDomainAction')
      .mockReturnValue(of(bulkResult));
    const retrieveSpy = vi.spyOn(domainListService, 'retrieveDomains');
    const clearCursorSpy = vi.spyOn(domainListService, 'clearPaginationCursor');
    retrieveSpy.mockClear();

    component.onPageChange({ pageIndex: 2, pageSize: 50, length: 150 });
    retrieveSpy.mockClear();
    clearCursorSpy.mockClear();

    component.toggleAllRows();
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
    expect(component.isAllSelected()).toBe(false);
    expect(component.selection.selected).toHaveLength(0);
    expect(clearCursorSpy).toHaveBeenCalled();
    expect(retrieveSpy).toHaveBeenCalledWith(0, 50, undefined, undefined);
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

  it('should cancel in-flight bulk mutations and pending search debounce when switching registrars', () => {
    vi.useFakeTimers();
    const pendingBulkDelete$ = new Subject<Record<string, any>>();
    vi.spyOn(domainListService, 'bulkDomainAction').mockReturnValueOnce(
      pendingBulkDelete$.asObservable()
    );

    component.selection.select(activeDomain);
    component.deleteSelectedDomains();
    expect(component.isLoading()).toBe(true);
    expect(pendingBulkDelete$.observed).toBe(true);

    // Queue a debounced search before switching registrar
    component.onSearchInput('stale-search');

    const pendingReg2Domains$ = new Subject<DomainListResult>();
    const retrieveSpy = vi
      .spyOn(domainListService, 'retrieveDomains')
      .mockReturnValueOnce(pendingReg2Domains$.asObservable());
    dialogOpenSpy.mockClear();
    snackBarOpenSpy.mockClear();

    registrarIdSignal.set('reg-2');
    TestBed.flushEffects();

    // Old bulk delete subscription must be cancelled immediately via takeUntil
    expect(pendingBulkDelete$.observed).toBe(false);
    expect(component.searchTerm()).toBe('');
    expect(component.activeSearchTerm()).toBe('');

    // Late emission from cancelled reg-1 delete must NOT open ResponseDialog,
    // write operationResult, or clear reg-2's loading state.
    pendingBulkDelete$.next({
      'example.tld': { message: 'Deleted', responseCode: '1000' },
    });
    pendingBulkDelete$.complete();

    expect(dialogOpenSpy).not.toHaveBeenCalled();
    expect(snackBarOpenSpy).not.toHaveBeenCalled();
    expect(component.operationResult()).toBeUndefined();
    expect(component.isLoading()).toBe(true);

    // Advancing past debounce must NOT trigger the cancelled reg-1 search on reg-2
    const callsAfterSwitch = retrieveSpy.mock.calls.length;
    vi.advanceTimersByTime(500);
    expect(retrieveSpy.mock.calls.length).toBe(callsAfterSwitch);
  });

  it('should close an open ReasonDialog and ignore late reason emissions on registrar switch', () => {
    const dialogCloseSpy = vi.fn();
    const afterClosed$ = new Subject<string | undefined>();
    dialogOpenSpy.mockReturnValueOnce({
      afterClosed: () => afterClosed$.asObservable(),
      close: dialogCloseSpy,
    } as any);
    const bulkSpy = vi.spyOn(domainListService, 'bulkDomainAction');

    component.onSuspendClick('example.tld');
    expect(afterClosed$.observed).toBe(true);

    registrarIdSignal.set('reg-2');
    TestBed.flushEffects();

    expect(dialogCloseSpy).toHaveBeenCalled();
    expect(afterClosed$.observed).toBe(false);
    afterClosed$.next('stale reason');
    afterClosed$.complete();
    expect(bulkSpy).not.toHaveBeenCalled();
  });

  it('should reset stale domain, selection, and lock state when switching registrars', () => {
    component.onPageChange({ pageIndex: 2, pageSize: 50, length: 200 });
    component.toggleAllRows();
    component.operationResult.set({
      'example.tld': { message: 'Deleted', responseCode: '1000' },
    });
    domainListService.checkpointTime = '2024-06-01T00:00:00Z';
    component.openRegistryLock('example.tld');

    const pendingDomainsReg2$ = new Subject<DomainListResult>();
    const pendingLocksReg2$ = new Subject<DomainLocksResult[]>();
    const pendingDomainsReg3$ = new Subject<DomainListResult>();
    const pendingLocksReg3$ = new Subject<DomainLocksResult[]>();
    const retrieveDomainsSpy = vi.spyOn(domainListService, 'retrieveDomains');
    retrieveDomainsSpy.mockClear();
    retrieveDomainsSpy
      .mockReturnValueOnce(
        pendingDomainsReg2$.pipe(
          tap((res) => {
            domainListService.checkpointTime = res.checkpointTime;
            domainListService.domainsList.set(res.domains);
          })
        )
      )
      .mockReturnValueOnce(pendingDomainsReg3$.asObservable());
    const retrieveLocksSpy = vi.spyOn(registryLockService, 'retrieveLocks');
    retrieveLocksSpy.mockClear();
    retrieveLocksSpy
      .mockReturnValueOnce(
        pendingLocksReg2$.pipe(
          tap((locks) => registryLockService.domainsLocks.set(locks))
        )
      )
      .mockReturnValueOnce(pendingLocksReg3$.asObservable());

    registrarIdSignal.set('reg-2');
    TestBed.flushEffects();

    expect(component.pageNumber()).toBe(0);
    expect(component.totalResults()).toBeUndefined();
    expect(component.selection.selected).toEqual([]);
    expect(component.isAllSelected()).toBe(false);
    expect(component.operationResult()).toBeUndefined();
    expect(component.dataSource.data).toEqual([]);
    expect(domainListService.checkpointTime).toBeUndefined();
    expect(domainListService.selectedDomain()).toBeUndefined();
    expect(domainListService.activeActionComponent()).toBeNull();
    expect(domainListService.domainsList()).toEqual([]);
    expect(registryLockService.domainsLocks()).toEqual([]);
    expect(retrieveDomainsSpy).toHaveBeenCalledTimes(1);
    expect(retrieveLocksSpy).toHaveBeenCalledTimes(1);
    expect(pendingDomainsReg2$.observed).toBe(true);
    expect(pendingLocksReg2$.observed).toBe(true);

    // Switch to reg-3 before reg-2 requests resolve; reg-2 subscriptions must be cancelled
    registrarIdSignal.set('reg-3');
    TestBed.flushEffects();

    expect(retrieveDomainsSpy).toHaveBeenCalledTimes(2);
    expect(retrieveLocksSpy).toHaveBeenCalledTimes(2);
    expect(pendingDomainsReg2$.observed).toBe(false);
    expect(pendingLocksReg2$.observed).toBe(false);

    // Late emissions from stale reg-2 subjects must not overwrite reg-3 state or clear isLoading
    pendingDomainsReg2$.next({
      checkpointTime: '2024-07-01T00:00:00Z',
      domains: [
        {
          creationTime: { creationTime: '2024-01-01T00:00:00Z' },
          currentSponsorRegistrarId: 'reg-2',
          domainName: 'stale.tld',
          registrationExpirationTime: '2025-01-01T00:00:00Z',
          statuses: [],
        },
      ],
      totalResults: 99,
    });
    pendingDomainsReg2$.complete();
    pendingLocksReg2$.next([{ domainName: 'stale.tld' }]);
    pendingLocksReg2$.complete();

    expect(component.isLoading()).toBe(true);
    expect(component.dataSource.data).toEqual([]);
    expect(component.totalResults()).toBeUndefined();

    pendingDomainsReg3$.complete();
    pendingLocksReg3$.complete();

    retrieveDomainsSpy.mockClear();
    retrieveLocksSpy.mockClear();
    registrarIdSignal.set('');
    TestBed.flushEffects();

    expect(component.isLoading()).toBe(false);
    expect(retrieveDomainsSpy).not.toHaveBeenCalled();
    expect(retrieveLocksSpy).not.toHaveBeenCalled();
  });
});
