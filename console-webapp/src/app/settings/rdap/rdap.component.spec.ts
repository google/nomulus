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
import { BrowserAnimationsModule } from '@angular/platform-browser/animations';
import { MaterialModule } from 'src/app/material.module';
import { RegistrarService } from 'src/app/registrar/registrar.service';
import { BackendService } from 'src/app/shared/services/backend.service';
import RdapComponent from './rdap.component';

describe('RdapComponent', () => {
  let component: RdapComponent;
  let fixture: ComponentFixture<RdapComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [RdapComponent],
      imports: [MaterialModule, BrowserAnimationsModule],
      providers: [
        BackendService,
        {
          provide: RegistrarService,
          useValue: {
            registrar: function () {
              return {};
            },
          },
        },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(RdapComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
