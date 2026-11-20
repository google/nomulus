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

import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, Subject, throwError } from 'rxjs';
import { BackendService } from './backend.service';
import { GlobalLoaderService } from './globalLoader.service';
import { UserData, UserDataService } from './userData.service';

describe('UserDataService', () => {
  const mockUserData: UserData = {
    globalRole: 'NONE',
    isAdmin: false,
    productName: 'Nomulus',
    supportEmail: 'support@example.com',
    supportPhoneNumber: '+1.5555555555',
    technicalDocsUrl: 'https://example.com/docs',
  };

  afterEach(() => {
    vi.useRealTimers();
  });

  it('should cancel loading timeout when TestBed is destroyed while request is pending', () => {
    vi.useFakeTimers();
    const pendingUserData$ = new Subject<UserData>();

    TestBed.configureTestingModule({
      providers: [
        BackendService,
        GlobalLoaderService,
        MatSnackBar,
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    const backend = TestBed.inject(BackendService);
    vi.spyOn(backend, 'getUserData').mockReturnValue(
      pendingUserData$.asObservable()
    );
    const snackBarSpy = vi.spyOn(TestBed.inject(MatSnackBar), 'open');

    const service = TestBed.inject(UserDataService);
    const timeoutSpy = vi.spyOn(service, 'loadingTimeout');

    TestBed.resetTestingModule();

    expect(() => vi.advanceTimersByTime(10000)).not.toThrow();
    expect(timeoutSpy).not.toHaveBeenCalled();
    expect(snackBarSpy).not.toHaveBeenCalled();
  });

  it('should stop global loader on ngOnDestroy while request is pending', () => {
    vi.useFakeTimers();
    const pendingUserData$ = new Subject<UserData>();

    TestBed.configureTestingModule({
      providers: [
        BackendService,
        GlobalLoaderService,
        MatSnackBar,
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    const backend = TestBed.inject(BackendService);
    vi.spyOn(backend, 'getUserData').mockReturnValue(
      pendingUserData$.asObservable()
    );
    const globalLoader = TestBed.inject(GlobalLoaderService);
    const snackBarSpy = vi
      .spyOn(TestBed.inject(MatSnackBar), 'open')
      .mockReturnValue({} as never);

    const service = TestBed.inject(UserDataService);
    expect(globalLoader.isLoading()).toBe(true);

    service.ngOnDestroy();
    expect(globalLoader.isLoading()).toBe(false);

    vi.advanceTimersByTime(10000);
    expect(snackBarSpy).not.toHaveBeenCalled();
  });

  it('should stop global loader when getUserData completes synchronously', () => {
    vi.useFakeTimers();

    TestBed.configureTestingModule({
      providers: [
        BackendService,
        GlobalLoaderService,
        MatSnackBar,
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    const backend = TestBed.inject(BackendService);
    vi.spyOn(backend, 'getUserData').mockReturnValue(of(mockUserData));
    const globalLoader = TestBed.inject(GlobalLoaderService);
    const snackBarSpy = vi
      .spyOn(TestBed.inject(MatSnackBar), 'open')
      .mockReturnValue({} as never);

    const service = TestBed.inject(UserDataService);
    expect(service.userData()).toEqual(mockUserData);
    expect(globalLoader.isLoading()).toBe(false);

    vi.advanceTimersByTime(10000);
    expect(snackBarSpy).not.toHaveBeenCalled();
  });

  it('should open snackbar on loadingTimeout if request does not resolve in time', () => {
    vi.useFakeTimers();
    const pendingUserData$ = new Subject<UserData>();

    TestBed.configureTestingModule({
      providers: [
        BackendService,
        GlobalLoaderService,
        MatSnackBar,
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    const backend = TestBed.inject(BackendService);
    vi.spyOn(backend, 'getUserData').mockReturnValue(
      pendingUserData$.asObservable()
    );
    const snackBarSpy = vi
      .spyOn(TestBed.inject(MatSnackBar), 'open')
      .mockReturnValue({} as never);

    TestBed.inject(UserDataService);
    vi.advanceTimersByTime(10000);

    expect(snackBarSpy).toHaveBeenCalledWith('Timeout loading user data');
  });

  it('should stop global loader and not throw unhandled error when getUserData errors', () => {
    vi.useFakeTimers();

    TestBed.configureTestingModule({
      providers: [
        BackendService,
        GlobalLoaderService,
        MatSnackBar,
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    const backend = TestBed.inject(BackendService);
    vi.spyOn(backend, 'getUserData').mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 500 }))
    );
    const globalLoader = TestBed.inject(GlobalLoaderService);
    const snackBarSpy = vi
      .spyOn(TestBed.inject(MatSnackBar), 'open')
      .mockReturnValue({} as never);

    const service = TestBed.inject(UserDataService);
    expect(service.userData()).toBeUndefined();
    expect(globalLoader.isLoading()).toBe(false);

    expect(() => vi.advanceTimersByTime(10000)).not.toThrow();
    expect(snackBarSpy).not.toHaveBeenCalled();
  });
});
