// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { BackendService } from '../shared/services/backend.service';
import { RegistrarService } from './registrar.service';

describe('RegistrarService', () => {
  let service: RegistrarService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [],
      providers: [
        BackendService,
        MatSnackBar,
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    service = TestBed.inject(RegistrarService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
