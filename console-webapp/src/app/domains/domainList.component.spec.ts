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

import { DomainListComponent } from './domainList.component';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { MaterialModule } from '../material.module';
import { BackendService } from '../shared/services/backend.service';
import { BrowserAnimationsModule } from '@angular/platform-browser/animations';

describe('DomainListComponent', () => {
  let component: DomainListComponent;
  let fixture: ComponentFixture<DomainListComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [DomainListComponent],
      imports: [HttpClientTestingModule, MaterialModule, BrowserAnimationsModule],
      providers: [BackendService],
    }).compileComponents();

    fixture = TestBed.createComponent(DomainListComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
