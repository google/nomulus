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
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { signal } from '@angular/core';
import { MaterialModule } from '../material.module';
import { Registrar, RegistrarService } from '../registrar/registrar.service';
import { BillingInfoComponent } from './billingInfo.component';

describe('BillingInfoComponent', () => {
  let component: BillingInfoComponent;
  let fixture: ComponentFixture<BillingInfoComponent>;
  let registrarSignal: ReturnType<
    typeof signal<Partial<Registrar> | undefined>
  >;
  let mockSnackBar: { open: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    registrarSignal = signal<Partial<Registrar> | undefined>({
      registrarId: 'reg-1',
      driveFolderId: 'drive-folder-abc',
    });

    await TestBed.configureTestingModule({
      imports: [BillingInfoComponent, MaterialModule],
      providers: [
        {
          provide: RegistrarService,
          useValue: {
            registrarId: signal('reg-1'),
            registrar: registrarSignal,
          },
        },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(BillingInfoComponent);
    mockSnackBar = {
      open: vi
        .spyOn(fixture.debugElement.injector.get(MatSnackBar), 'open')
        .mockReturnValue({} as any),
    };
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should compute Google Drive folder URL when driveFolderId is present', () => {
    expect(component.driveFolderUrl()).toBe(
      'https://drive.google.com/drive/folders/drive-folder-abc'
    );

    const clickEvent = { preventDefault: vi.fn() } as unknown as MouseEvent;
    component.openBillingDetails(clickEvent);
    expect(clickEvent.preventDefault).not.toHaveBeenCalled();
    expect(mockSnackBar.open).not.toHaveBeenCalled();
  });

  it('should return empty URL and show snackbar when driveFolderId is missing', () => {
    registrarSignal.set({ registrarId: 'reg-1' });
    expect(component.driveFolderUrl()).toBe('');

    const clickEvent = { preventDefault: vi.fn() } as unknown as MouseEvent;
    component.openBillingDetails(clickEvent);
    expect(clickEvent.preventDefault).toHaveBeenCalled();
    expect(mockSnackBar.open).toHaveBeenCalledWith(
      'Billing Folder ID has not been assigned'
    );
  });
});
