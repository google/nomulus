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

import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MaterialModule } from '../material.module';
import { BackendService } from '../shared/services/backend.service';
import { RegistrarSelectorComponent } from './registrarSelector.component';
import { FormsModule } from '@angular/forms';
import { Registrar, RegistrarService } from './registrar.service';
import { signal } from '@angular/core';

describe('RegistrarSelectorComponent', () => {
  let component: RegistrarSelectorComponent;
  let fixture: ComponentFixture<RegistrarSelectorComponent>;
  let mockRegistrarService: {
    registrarId: ReturnType<typeof signal<string>>;
    registrars: ReturnType<typeof signal<Partial<Registrar>[]>>;
    updateSelectedRegistrar: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    mockRegistrarService = {
      registrarId: signal('alpha'),
      registrars: signal([
        { registrarId: 'charlie' },
        { registrarId: 'alpha' },
        { registrarId: 'bravo' },
      ]),
      updateSelectedRegistrar: vi.fn(),
    };

    await TestBed.configureTestingModule({
      imports: [RegistrarSelectorComponent, MaterialModule, FormsModule],
      providers: [
        BackendService,
        { provide: RegistrarService, useValue: mockRegistrarService },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(RegistrarSelectorComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should compute sorted registrar IDs and filter by input', () => {
    expect(component.allRegistrarIds()).toEqual(['alpha', 'bravo', 'charlie']);
    expect(component.filteredOptions()).toEqual(['alpha']);

    component.registrarInput.set('R');
    TestBed.flushEffects();
    expect(component.filteredOptions()).toEqual(['bravo', 'charlie']);
  });

  it('should reset filteredOptions to all sorted registrar IDs on focus', () => {
    component.registrarInput.set('charlie');
    TestBed.flushEffects();
    expect(component.filteredOptions()).toEqual(['charlie']);

    component.onFocus();
    expect(component.filteredOptions()).toEqual(['alpha', 'bravo', 'charlie']);
  });

  it('should delegate selection to RegistrarService.updateSelectedRegistrar', () => {
    component.onSelect('bravo');
    expect(mockRegistrarService.updateSelectedRegistrar).toHaveBeenCalledWith(
      'bravo'
    );
  });
});
