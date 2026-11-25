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

import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';
import { BackendService } from '../shared/services/backend.service';
import { GlobalLoaderService } from '../shared/services/globalLoader.service';
import { Registrar, RegistrarService } from './registrar.service';

describe('RegistrarService', () => {
  let service: RegistrarService;
  let backendService: BackendService;
  let globalLoader: GlobalLoaderService;
  let router: Router;
  let snackBarOpenSpy: ReturnType<typeof vi.spyOn>;
  let initialRegistrarsSubject: Subject<Registrar[]>;

  const mockRegistrars: Registrar[] = [
    {
      registrarId: 'reg-1',
      registrarName: 'Registrar One',
      icannReferralEmail: 'one@example.com',
      localizedAddress: { city: 'NYC' },
      url: 'https://one.example',
    },
    {
      registrarId: 'reg-2',
      registrarName: 'Registrar Two',
      icannReferralEmail: 'two@example.com',
      localizedAddress: { city: 'LA' },
      url: 'https://two.example',
    },
  ];

  beforeEach(() => {
    initialRegistrarsSubject = new Subject<Registrar[]>();
    TestBed.configureTestingModule({
      providers: [
        BackendService,
        GlobalLoaderService,
        MatSnackBar,
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    backendService = TestBed.inject(BackendService);
    globalLoader = TestBed.inject(GlobalLoaderService);
    router = TestBed.inject(Router);
    snackBarOpenSpy = vi
      .spyOn(TestBed.inject(MatSnackBar), 'open')
      .mockReturnValue({} as any);
    vi.spyOn(backendService, 'getRegistrars').mockReturnValue(
      initialRegistrarsSubject.asObservable()
    );
    service = TestBed.inject(RegistrarService);
    initialRegistrarsSubject.next(mockRegistrars);
    initialRegistrarsSubject.complete();
  });

  it('should load registrars on initialization and stop global loader', async () => {
    await service.registrarsLoaded;
    expect(service.registrars()).toEqual(mockRegistrars);
    expect(globalLoader.isLoading()).toBe(false);
  });

  it('should compute active registrar from registrarId signal', () => {
    service.registrarId.set('reg-2');
    expect(service.registrar()?.registrarName).toBe('Registrar Two');

    service.registrarId.set('unknown');
    expect(service.registrar()).toBeUndefined();
  });

  it('should update selected registrar and navigate with merged queryParams', () => {
    const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    service.registrarId.set('reg-1');

    service.updateSelectedRegistrar('reg-1');
    expect(navigateSpy).not.toHaveBeenCalled();

    service.updateSelectedRegistrar('reg-2');
    expect(service.registrarId()).toBe('reg-2');
    expect(navigateSpy).toHaveBeenCalledWith([], {
      queryParams: { registrarId: 'reg-2' },
      queryParamsHandling: 'merge',
    });
  });

  it('should create registrar and reload registrars list', () => {
    const newRegistrar: Registrar = {
      registrarId: 'reg-3',
      registrarName: 'Registrar Three',
      icannReferralEmail: 'three@example.com',
      localizedAddress: {},
      url: 'https://three.example',
    };
    const createSpy = vi
      .spyOn(backendService, 'createRegistrar')
      .mockReturnValue(of(newRegistrar));
    const updatedList = [...mockRegistrars, newRegistrar];
    vi.spyOn(backendService, 'getRegistrars').mockReturnValue(of(updatedList));

    service.createRegistrar(newRegistrar).subscribe();

    expect(createSpy).toHaveBeenCalledWith(newRegistrar);
    expect(service.registrars()).toEqual(updatedList);
  });

  it('should update an existing registrar in place in registrars signal', () => {
    const updatedReg1: Registrar = {
      ...mockRegistrars[0],
      registrarName: 'Updated Registrar One',
    };
    vi.spyOn(backendService, 'updateRegistrar').mockReturnValue(
      of(updatedReg1)
    );

    service.updateRegistrar(updatedReg1).subscribe();

    expect(service.registrars()[0].registrarName).toBe('Updated Registrar One');
    expect(service.registrars()[1].registrarName).toBe('Registrar Two');
  });

  it('should generate OTE and reload registrars', () => {
    const oteResponse = { password: 'secret' } as any;
    const generateSpy = vi
      .spyOn(backendService, 'generateOte')
      .mockReturnValue(of(oteResponse));
    const loadSpy = vi.spyOn(service, 'loadRegistrars');

    service.generateOte({ registrarId: 'reg-1' }, 'reg-1').subscribe();

    expect(generateSpy).toHaveBeenCalledWith({ registrarId: 'reg-1' }, 'reg-1');
    expect(loadSpy).toHaveBeenCalled();
  });

  it('should fetch OTE status for registrarId', () => {
    const statusResponse = [
      {
        description: 'domain creates',
        requirement: 1,
        timesPerformed: 1,
        completed: true,
      },
    ];
    const statusSpy = vi
      .spyOn(backendService, 'getOteStatus')
      .mockReturnValue(of(statusResponse));

    let received: any;
    service.oteStatus('reg-1').subscribe((res) => (received = res));

    expect(statusSpy).toHaveBeenCalledWith('reg-1');
    expect(received).toEqual(statusResponse);
  });

  it('should show snackbar on loadingTimeout()', () => {
    service.loadingTimeout();
    expect(snackBarOpenSpy).toHaveBeenCalledWith('Timeout loading registrars');
  });

  describe('initialization and teardown', () => {
    afterEach(() => {
      vi.useRealTimers();
    });

    it('should stop global loader when getRegistrars completes synchronously', async () => {
      vi.useFakeTimers();
      TestBed.resetTestingModule();
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
      vi.spyOn(backend, 'getRegistrars').mockReturnValue(of(mockRegistrars));
      const loader = TestBed.inject(GlobalLoaderService);
      const snackSpy = vi
        .spyOn(TestBed.inject(MatSnackBar), 'open')
        .mockReturnValue({} as never);

      const regService = TestBed.inject(RegistrarService);
      await regService.registrarsLoaded;

      expect(regService.registrars()).toEqual(mockRegistrars);
      expect(loader.isLoading()).toBe(false);
      vi.advanceTimersByTime(10000);
      expect(snackSpy).not.toHaveBeenCalled();
    });

    it('should stop global loader, resolve registrarsLoaded, and not throw on error', async () => {
      vi.useFakeTimers();
      TestBed.resetTestingModule();
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
      vi.spyOn(backend, 'getRegistrars').mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 500 }))
      );
      const loader = TestBed.inject(GlobalLoaderService);
      const snackSpy = vi
        .spyOn(TestBed.inject(MatSnackBar), 'open')
        .mockReturnValue({} as never);

      const regService = TestBed.inject(RegistrarService);
      await regService.registrarsLoaded;

      expect(loader.isLoading()).toBe(false);
      expect(() => vi.advanceTimersByTime(10000)).not.toThrow();
      expect(snackSpy).not.toHaveBeenCalled();
    });

    it('should stop global loader and resolve registrarsLoaded on ngOnDestroy while pending', async () => {
      vi.useFakeTimers();
      const pending$ = new Subject<Registrar[]>();
      TestBed.resetTestingModule();
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
      vi.spyOn(backend, 'getRegistrars').mockReturnValue(
        pending$.asObservable()
      );
      const loader = TestBed.inject(GlobalLoaderService);
      const snackSpy = vi
        .spyOn(TestBed.inject(MatSnackBar), 'open')
        .mockReturnValue({} as never);

      const regService = TestBed.inject(RegistrarService);
      expect(loader.isLoading()).toBe(true);

      regService.ngOnDestroy();
      await regService.registrarsLoaded;

      expect(loader.isLoading()).toBe(false);
      vi.advanceTimersByTime(10000);
      expect(snackSpy).not.toHaveBeenCalled();
    });
  });
});
