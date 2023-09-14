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

import { Component } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription } from 'rxjs';

import { RegistrarService } from './registrar.service';

@Component({
  selector: 'app-empty-registrar',
  templateUrl: './emptyRegistrar.component.html',
  styleUrls: ['./emptyRegistrar.component.scss'],
})
export class EmptyRegistrar {
  private registrarIdChangeSubscription?: Subscription;

  constructor(
    private route: ActivatedRoute,
    protected registrarService: RegistrarService,
    private router: Router
  ) {
    this.registrarIdChangeSubscription =
      registrarService.activeRegistrarIdChange.subscribe((newRegistrarId) => {
        if (newRegistrarId) {
          this.router.navigate([this.route.snapshot.paramMap.get('nextUrl')]);
        }
      });
  }

  ngOnDestroy() {
    this.registrarIdChangeSubscription?.unsubscribe();
  }
}
