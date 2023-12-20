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

import { ComponentFixture, TestBed } from '@angular/core/testing';

import WhoisComponent from './whois.component';
import { MaterialModule } from 'src/app/material.module';
import { BackendService } from 'src/app/shared/services/backend.service';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { RegistrarService } from 'src/app/registrar/registrar.service';
import { BrowserAnimationsModule } from '@angular/platform-browser/animations';

describe('WhoisComponent', () => {
  let component: WhoisComponent;
  let fixture: ComponentFixture<WhoisComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [WhoisComponent],
      imports: [HttpClientTestingModule, MaterialModule, BrowserAnimationsModule],
      providers: [BackendService,  { provide: RegistrarService, useValue: { registrar: {} } }],
    })
    .compileComponents();

    fixture = TestBed.createComponent(WhoisComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
