// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

import { RegistrarGuard } from './registrar.guard';
import { Router, RouterStateSnapshot } from '@angular/router';
import { RegistrarService } from './registrar.service';

describe('RegistrarGuard', () => {
  let guard: RegistrarGuard;
  let dummyRegistrarService: RegistrarService;
  let routeSpy: Router;
  let dummyRoute: RouterStateSnapshot = {} as RouterStateSnapshot;

  beforeEach(() => {
    routeSpy = jasmine.createSpyObj<Router>('Router', ['navigate']);
    dummyRegistrarService = { activeRegistrarId: '' } as RegistrarService;

    TestBed.configureTestingModule({
      providers: [
        RegistrarGuard,
        { provide: Router, useValue: routeSpy },
        { provide: RegistrarService, useValue: dummyRegistrarService },
      ],
    });
  });

  it('should not be able to activate when activeRegistrarId is empty', () => {
    guard = TestBed.inject(RegistrarGuard);
    const res = guard.canActivate();
    expect(res).toBeFalsy();
  });

  it('should be able to activate when activeRegistrarId is not empty', () => {
    TestBed.overrideProvider(RegistrarService, {
      useValue: { activeRegistrarId: 'value' },
    });
    guard = TestBed.inject(RegistrarGuard);
    const res = guard.canActivate();
    expect(res).toBeTrue();
  });

  it('should navigate to registrars when activeRegistrarId is empty', () => {
    const dummyRoute = { url: '/value' } as RouterStateSnapshot;
    guard = TestBed.inject(RegistrarGuard);
    guard.canActivate();
    expect(routeSpy.navigate).toHaveBeenCalledOnceWith([
      '/registrars',
      { nextUrl: '/value' },
    ]);
  });
});
