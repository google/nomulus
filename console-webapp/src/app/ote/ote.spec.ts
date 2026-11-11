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
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { signal } from '@angular/core';
import { of, throwError } from 'rxjs';
import { MaterialModule } from '../material.module';
import { Registrar, RegistrarService } from '../registrar/registrar.service';
import { NewOteComponent, OteCreateResponse } from './newOte.component';
import { OteStatusComponent, OteStatusResponse } from './oteStatus.component';

describe('NewOteComponent & OteStatusComponent', () => {
  let mockRegistrarService: any;
  let mockSnackBar: { open: ReturnType<typeof vi.fn> };

  const statusItems: OteStatusResponse[] = [
    {
      description: 'domain creates',
      requirement: 2,
      timesPerformed: 2,
      completed: true,
    },
    {
      description: 'domain deletes',
      requirement: 1,
      timesPerformed: 0,
      completed: false,
    },
  ];

  beforeEach(async () => {
    mockRegistrarService = {
      registrars: signal<Partial<Registrar>[]>([
        { registrarId: 'ote-reg-1', type: 'OTE' },
        { registrarId: 'real-reg', type: 'REAL' },
      ]),
      generateOte: vi.fn(),
      oteStatus: vi.fn().mockReturnValue(of(statusItems)),
    };

    await TestBed.configureTestingModule({
      imports: [NewOteComponent, OteStatusComponent, MaterialModule],
      providers: [
        { provide: RegistrarService, useValue: mockRegistrarService },
        {
          provide: ActivatedRoute,
          useValue: {
            paramMap: of(convertToParamMap({ registrarId: 'ote-reg-1' })),
          },
        },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();
  });

  describe('NewOteComponent', () => {
    let fixture: ComponentFixture<NewOteComponent>;
    let component: NewOteComponent;

    beforeEach(() => {
      fixture = TestBed.createComponent(NewOteComponent);
      mockSnackBar = {
        open: vi
          .spyOn(fixture.debugElement.injector.get(MatSnackBar), 'open')
          .mockReturnValue({} as any),
      };
      component = fixture.componentInstance;
      fixture.detectChanges();
    });

    it('should validate form, submit OTE generation, and format credentials response', () => {
      expect(component.oteCreateResponseFormatted()).toBeUndefined();

      component.onSubmit();
      expect(mockRegistrarService.generateOte).not.toHaveBeenCalled();

      const mockResponse = {
        password: 'otePassword123',
        'acme-1': 'tld1',
        'acme-2': 'tld2',
      } as unknown as OteCreateResponse;
      mockRegistrarService.generateOte.mockReturnValueOnce(of(mockResponse));

      component.createOte.setValue({
        registrarId: 'acme',
        registrarEmail: 'ote@acme.example',
      });
      component.onSubmit();

      expect(mockRegistrarService.generateOte).toHaveBeenCalledWith(
        {
          registrarId: 'acme',
          registrarEmail: 'ote@acme.example',
        },
        'acme'
      );
      expect(component.oteCreateResponseFormatted()).toBe(
        'Login: acme-1\t\tPassword: otePassword123\t\tTLD: tld1\n' +
          'Login: acme-2\t\tPassword: otePassword123\t\tTLD: tld2'
      );

      mockRegistrarService.generateOte.mockReturnValueOnce(
        throwError(() => new HttpErrorResponse({ error: 'OTE already exists' }))
      );
      component.onSubmit();
      expect(mockSnackBar.open).toHaveBeenCalledWith('OTE already exists');
    });
  });

  describe('OteStatusComponent', () => {
    let fixture: ComponentFixture<OteStatusComponent>;
    let component: OteStatusComponent;

    beforeEach(() => {
      fixture = TestBed.createComponent(OteStatusComponent);
      component = fixture.componentInstance;
      fixture.detectChanges();
    });

    it('should load OTE status from route param and partition completed vs unfinished', () => {
      expect(mockRegistrarService.oteStatus).toHaveBeenCalledWith('ote-reg-1');
      expect(component.isOte()).toBe(true);
      expect(component.oteStatusCompleted()).toEqual([statusItems[0]]);
      expect(component.oteStatusUnfinished()).toEqual([statusItems[1]]);

      component.registrarId.set('real-reg');
      expect(component.isOte()).toBe(false);
    });
  });
});
