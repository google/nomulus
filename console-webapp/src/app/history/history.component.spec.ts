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

import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatSnackBar } from '@angular/material/snack-bar';
import { signal } from '@angular/core';
import { of, throwError } from 'rxjs';
import { MaterialModule } from '../material.module';
import { BackendService } from '../shared/services/backend.service';
import { GlobalLoaderService } from '../shared/services/globalLoader.service';
import { UserDataService } from '../shared/services/userData.service';
import { RegistrarService } from '../registrar/registrar.service';
import { HistoryRecord, HistoryService } from './history.service';
import { HistoryComponent } from './history.component';
import { HistoryListComponent } from './historyList.component';
import { RESTRICTED_ELEMENTS } from '../shared/directives/userLevelVisiblity.directive';

describe('HistoryComponent, HistoryListComponent & HistoryService', () => {
  let backendService: BackendService;
  let globalLoader: GlobalLoaderService;
  let mockSnackBar: { open: ReturnType<typeof vi.fn> };

  const sampleRecords: HistoryRecord[] = [
    {
      modificationTime: '2025-01-01T12:00:00Z',
      type: 'REGISTRAR_UPDATE',
      description: 'Updated registrar|CLIENT_CERTIFICATE_UPDATED',
      actingUser: { emailAddress: 'admin@example.com' },
    },
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [
        HistoryComponent,
        HistoryListComponent,
        MaterialModule,
        NoopAnimationsModule,
      ],
      providers: [
        BackendService,
        HistoryService,
        GlobalLoaderService,
        {
          provide: RegistrarService,
          useValue: {
            registrarId: signal('reg-1'),
          },
        },
        {
          provide: UserDataService,
          useValue: {
            userData: signal({ globalRole: 'SUPPORT_ADMIN' }),
          },
        },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    backendService = TestBed.inject(BackendService);
    globalLoader = TestBed.inject(GlobalLoaderService);
    vi.spyOn(backendService, 'getHistoryLog').mockReturnValue(
      of(sampleRecords)
    );
  });

  it('should store registrar and user history records in HistoryService signals', () => {
    const historyService = TestBed.inject(HistoryService);

    historyService.getHistoryLog('reg-1').subscribe();
    expect(historyService.historyRecordsRegistrar()).toEqual(sampleRecords);

    historyService.getHistoryLog('reg-1', 'user@example.com').subscribe();
    expect(historyService.historyRecordsUser()).toEqual(sampleRecords);
  });

  it('should load history on registrarId effect, manage globalLoader, and handle errors', () => {
    const fixture = TestBed.createComponent(HistoryComponent);
    mockSnackBar = {
      open: vi
        .spyOn(fixture.debugElement.injector.get(MatSnackBar), 'open')
        .mockReturnValue({} as any),
    };
    const component = fixture.componentInstance;
    fixture.detectChanges();

    expect(backendService.getHistoryLog).toHaveBeenCalledWith('reg-1', '');
    expect(component.isLoading()).toBe(false);
    expect(globalLoader.isLoading()).toBe(false);
    expect(component.getElementIdForUserLog()).toBe(
      RESTRICTED_ELEMENTS.ACTIVITY_PER_USER
    );

    component.loadingTimeout();
    expect(mockSnackBar.open).toHaveBeenCalledWith(
      'Timeout loading records history'
    );

    vi.spyOn(backendService, 'getHistoryLog').mockReturnValueOnce(
      throwError(() => new HttpErrorResponse({ error: 'History fetch error' }))
    );
    component.loadHistory();
    expect(mockSnackBar.open).toHaveBeenCalledWith('History fetch error');
    expect(component.isLoading()).toBe(false);
  });

  it('should map icons, icon classes, and split descriptions in HistoryListComponent', () => {
    const fixture = TestBed.createComponent(HistoryListComponent);
    const component = fixture.componentInstance;

    expect(component.getIconForType('REGISTRAR_UPDATE')).toBe('edit');
    expect(component.getIconForType('REGISTRAR_SECURITY_UPDATE')).toBe(
      'security'
    );
    expect(component.getIconForType('OTHER')).toBe('history');

    expect(component.getIconClass('REGISTRAR_UPDATE')).toBe(
      'history-log__icon--update'
    );
    expect(component.getIconClass('REGISTRAR_SECURITY_UPDATE')).toBe(
      'history-log__icon--security'
    );
    expect(component.getIconClass('OTHER')).toBe('');

    expect(component.parseDescription('')).toEqual({
      main: 'N/A',
      detail: null,
    });
    expect(
      component.parseDescription('Updated registrar|CLIENT_CERT_ADDED')
    ).toEqual({
      main: 'Updated registrar',
      detail: 'CLIENT CERT ADDED',
    });
    expect(component.parseDescription('Simple event')).toEqual({
      main: 'Simple event',
      detail: 'Simple event',
    });
  });
});
