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

import { Component, OnInit } from '@angular/core';
import { Registrar, RegistrarService } from './registrar.service';
import { MatChipInputEvent } from '@angular/material/chips';
import { DialogBottomSheetContent } from '../shared/components/dialogBottomSheet.component';
import { ActivatedRoute, ParamMap } from '@angular/router';

@Component({
  selector: 'app-registrar-details',
  templateUrl: './registrarDetails.component.html',
  styleUrls: ['./registrarDetails.component.scss'],
})
export class RegistrarDetailsComponent implements OnInit {
  public static PATH = 'registrars/:id';
  registrarInEdit!: Registrar;

  constructor(
    protected registrarService: RegistrarService,
    private route: ActivatedRoute
  ) {}

  ngOnInit(): void {
    this.route.paramMap.subscribe((params: ParamMap) => {
      console.log(params.get('id'));
    });
  }

  addTLD(e: MatChipInputEvent) {
    this.removeTLD(e.value); // Prevent dups
    this.registrarInEdit.allowedTlds = this.registrarInEdit.allowedTlds?.concat(
      [e.value.toLowerCase()]
    );
  }

  removeTLD(tld: string) {
    this.registrarInEdit.allowedTlds = this.registrarInEdit.allowedTlds?.filter(
      (v) => v != tld
    );
  }
}
